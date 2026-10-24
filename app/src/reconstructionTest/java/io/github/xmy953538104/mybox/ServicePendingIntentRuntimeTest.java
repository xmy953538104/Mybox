package io.github.xmy953538104.mybox;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;

/** Requires physical Android Service records; a virtual boolean or OnFinished cannot satisfy it. */
public final class ServicePendingIntentRuntimeTest extends InstrumentationTestCase {
    public void testTypedServiceCapabilitiesAndRealForegroundStart() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ((MyboxApplication) context.getApplicationContext()).requireEngine();
        if (Build.VERSION.SDK_INT >= 33) getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = Engine.get().packages().get(name);
        if (installed == null) {
            File apk = new File(context.getCacheDir(), "service-pending-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) { MainActivity.copyApk(input, apk); }
                installed = Engine.get().install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        File files = new File(installed.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare Service PendingIntent fixture");
        String run = UUID.randomUUID().toString();
        Files.write(new File(files, "service-pending-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(installed, "service-pending");
        Engine.get().launch(name);
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        ActivityManager activities = context.getSystemService(ActivityManager.class);
        String ordinaryComponent = null, foregroundComponent = null;
        for (String phase : new String[]{"ordinary", "both", "cancelled-live", "stopped"}) {
            JSONObject state = waitPhase(files, run, phase);
            int pid = state.getInt("pid");
            long deadline = SystemClock.elapsedRealtime() + 15_000;
            boolean matched = false;
            String last = "No Android service snapshot";
            do {
                int running = 0, foreground = 0;
                String ordinary = null, promoted = null;
                for (ActivityManager.RunningServiceInfo info : activities.getRunningServices(100)) {
                    if (info.pid != pid || !info.service.getClassName().contains(".GuestServiceStub$")) continue;
                    running++;
                    if (info.foreground) { foreground++; promoted = info.service.flattenToString(); }
                    else ordinary = info.service.flattenToString();
                }
                StatusBarNotification notification = null;
                for (StatusBarNotification item : notifications.getActiveNotifications()) {
                    CharSequence title = item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
                    if (("PI FGS " + run).contentEquals(title == null ? "" : title)) notification = item;
                }
                last = "phase=" + phase + ", running=" + running + ", foreground=" + foreground + ", notification=" + (notification != null);
                if (phase.equals("ordinary")) {
                    matched = running == 1 && foreground == 0 && notification == null;
                    if (matched) ordinaryComponent = ordinary;
                } else if (phase.equals("stopped")) matched = running == 0 && notification == null;
                else {
                    matched = running == 2 && foreground == 1 && notification != null;
                    if (matched) {
                        assertEquals("Ordinary Service was converted or replaced", ordinaryComponent, ordinary);
                        assertFalse("Ordinary and foreground Services shared an OS record", ordinary.equals(promoted));
                        if (foregroundComponent == null) foregroundComponent = promoted;
                        else assertEquals("Cancel changed the running FGS", foregroundComponent, promoted);
                        assertEquals(context.getPackageName(), notification.getPackageName());
                        assertTrue((notification.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);
                        assertNotNull(notifications.getNotificationChannel(notification.getNotification().getChannelId()));
                        assertNotNull(notification.getNotification().getSmallIcon().loadDrawable(context));
                    }
                }
                if (!matched) Thread.sleep(50);
            } while (!matched && SystemClock.elapsedRealtime() < deadline);
            assertTrue("Actual Service PendingIntent OS state mismatch: " + last, matched);
            Files.write(new File(files, "service-pending-" + run + "-ack-" + phase).toPath(), new byte[]{1});
        }
        File result = new File(files, "service-pending-result-" + run + ".json");
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (!result.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
        assertTrue("Service PendingIntent probe did not complete", result.isFile());
        JSONObject report = new JSONObject(new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8));
        assertEquals(run, report.getString("runId")); assertFalse(report.toString(), report.has("failure"));
        for (String check : new String[]{"kindIdentity", "installerBroadcastBoundary", "actualOrdinary", "actualForeground", "updateCurrent", "completionScope",
                "immutable", "transfer", "oneShot", "cancel", "cancelCurrent", "failureCallback", "scope", "implicitRejected", "actualStop"}) {
            assertTrue(check + ": " + report, report.getBoolean(check));
        }
        assertNotNull(ordinaryComponent); assertNotNull(foregroundComponent);
    }

    private static JSONObject waitPhase(File files, String run, String phase) throws Exception {
        File state = new File(files, "service-pending-phase-" + run + ".json");
        File result = new File(files, "service-pending-result-" + run + ".json");
        long deadline = SystemClock.elapsedRealtime() + 45_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (result.isFile()) {
                JSONObject failure = new JSONObject(new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8));
                if (failure.has("failure")) throw new AssertionError(failure.toString());
            }
            if (state.isFile()) {
                JSONObject value = new JSONObject(new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8));
                if (run.equals(value.optString("runId")) && phase.equals(value.optString("phase"))) return value;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Service PendingIntent probe did not reach " + phase);
    }
}
