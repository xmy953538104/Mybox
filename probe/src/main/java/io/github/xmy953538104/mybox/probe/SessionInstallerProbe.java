package io.github.xmy953538104.mybox.probe;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs inside the guest and uses only the standard Android PackageInstaller API. */
public final class SessionInstallerProbe {
    public static final String TARGET = "io.github.xmy953538104.mybox.sessiontarget";
    private static final Uri STATE = Uri.parse("content://" + TARGET + ".state");
    public interface Callback { void completed(JSONObject report); }

    public static void run(Context context, Callback completed) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            JSONObject report = new JSONObject();
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            Journal journal = new Journal();
            try {
                try {
                    app.getPackageManager().getPackageInfo(TARGET, 0);
                    throw new IllegalStateException("Remove the old session fixture before this test");
                } catch (PackageManager.NameNotFoundException freshFixture) { }
                installer.registerSessionCallback(journal, main);
                install(app, installer, journal, "v1", true, PackageInstaller.STATUS_SUCCESS);
                Bundle baseline = app.getContentResolver().call(STATE, "writeMarker", "preserve-this-marker", null);
                require(baseline != null && baseline.getInt("version") == 1, "Baseline fixture is missing");
                require(baseline.getBoolean("featureCode"), "Split dex was not loaded: " + baseline.getString("error"));
                require(baseline.getBoolean("featureResources"), "Split resources were not loaded");
                report.put("installAndSplitCodeResources", true);

                install(app, installer, journal, "badSigner", true, PackageInstaller.STATUS_FAILURE_CONFLICT);
                requireState(app, 1);
                report.put("badSignerRejectedWithoutDataLoss", true);

                install(app, installer, journal, "v2", false, PackageInstaller.STATUS_FAILURE_INVALID);
                requireState(app, 1);
                report.put("missingSplitRejectedWithoutDataLoss", true);

                corrupt(app, installer, journal);
                requireState(app, 1);
                report.put("corruptApkRejectedWithoutDataLoss", true);

                install(app, installer, journal, "v2", true, PackageInstaller.STATUS_SUCCESS);
                requireState(app, 2);
                report.put("updatePreservedData", true);

                PackageInstaller.SessionParams params = params();
                int abandoned = installer.createSession(params);
                installer.abandonSession(abandoned);
                require(installer.getSessionInfo(abandoned) == null, "Abandoned session still visible");
                journal.requireFinished(abandoned, false);
                report.put("abandon", true);
                report.put("standardIntentSenderAndSessionCallback", true);
                report.put("passed", true);
            } catch (Throwable error) {
                try { report.put("passed", false); report.put("error", error.getClass().getName() + ": " + error.getMessage()); }
                catch (Exception ignored) { }
            } finally {
                try { installer.unregisterSessionCallback(journal); } catch (Exception ignored) { }
            }
            main.post(() -> completed.completed(report));
        }, "Mybox-session-probe").start();
    }

    private static PackageInstaller.SessionParams params() {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(TARGET);
        return params;
    }

    private static void install(Context app, PackageInstaller installer, Journal journal,
                                String fixture, boolean feature, int expected) throws Exception {
        int id = installer.createSession(params());
        try (PackageInstaller.Session session = installer.openSession(id)) {
            writeAsset(app, session, "base.apk", fixture + "-base.apk");
            if (feature) writeAsset(app, session, "feature.apk", fixture + "-feature.apk");
            require(session.getNames().length == (feature ? 2 : 1), "Session file enumeration is wrong");
            try (InputStream stored = session.openRead("base.apk")) {
                require(stored.read() == 'P' && stored.read() == 'K', "Session openRead did not return the APK");
            }
            int status = commitAndWait(app, session, id);
            require(status == expected, "Unexpected install status for " + fixture + ": " + status + " expected " + expected);
            journal.requireFinished(id, expected == PackageInstaller.STATUS_SUCCESS);
        } catch (Exception e) {
            try { installer.abandonSession(id); } catch (Exception ignored) { }
            throw e;
        }
    }

    private static void corrupt(Context app, PackageInstaller installer, Journal journal) throws Exception {
        int id = installer.createSession(params());
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream output = session.openWrite("base.apk", 0, 3)) {
                output.write(new byte[]{1, 2, 3});
                session.fsync(output);
            }
            require(commitAndWait(app, session, id) == PackageInstaller.STATUS_FAILURE_INVALID, "Corrupt APK reported success");
            journal.requireFinished(id, false);
        }
    }

    private static void writeAsset(Context app, PackageInstaller.Session session, String name, String asset) throws Exception {
        try (InputStream input = app.getAssets().open("session-fixtures/" + asset);
             OutputStream output = session.openWrite(name, 0, -1)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            session.fsync(output);
        }
    }

    private static int commitAndWait(Context app, PackageInstaller.Session session, int id) throws Exception {
        String action = app.getPackageName() + ".SESSION_RESULT." + UUID.randomUUID();
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Intent> result = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                result.set(intent);
                received.countDown();
            }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, new IntentFilter(action));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(app, id, new Intent(action).setPackage(app.getPackageName()), flags);
        try {
            session.commit(pending.getIntentSender());
            require(received.await(90, TimeUnit.SECONDS), "Session IntentSender callback timed out");
            Intent status = result.get();
            require(status != null && status.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == id, "Wrong session callback identity");
            return status.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        } finally {
            pending.cancel();
            app.unregisterReceiver(receiver);
        }
    }

    private static void requireState(Context app, int version) {
        Bundle state = app.getContentResolver().call(STATE, "read", null, null);
        require(state != null && state.getInt("version") == version, "Unexpected installed version after update");
        require("preserve-this-marker".equals(state.getString("marker")), "Update destroyed app data");
        require(state.getBoolean("featureCode") && state.getBoolean("featureResources"), "Updated split is not usable");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class Journal extends PackageInstaller.SessionCallback {
        private final Map<Integer, Boolean> finished = new ConcurrentHashMap<>();
        @Override public void onCreated(int id) { }
        @Override public void onBadgingChanged(int id) { }
        @Override public void onActiveChanged(int id, boolean active) { }
        @Override public void onProgressChanged(int id, float progress) { }
        @Override public void onFinished(int id, boolean success) { finished.put(id, success); }
        void requireFinished(int id, boolean success) throws InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (!finished.containsKey(id) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
            require(finished.containsKey(id) && finished.get(id) == success, "SessionCallback.onFinished missing or incorrect");
        }
    }
}
