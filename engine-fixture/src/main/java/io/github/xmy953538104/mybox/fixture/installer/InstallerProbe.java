package io.github.xmy953538104.mybox.fixture.installer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Ordinary APK code exercising public PackageInstaller, FileBridge, callbacks and virtual broadcasts. */
public final class InstallerProbe {
    public static final String TARGET = "io.github.xmy953538104.mybox.installtarget";
    private static final String ACTION = "io.github.xmy953538104.mybox.fixture.INSTALL_STATUS";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    public static void run(Context context) {
        File marker = new File(context.getFilesDir(), "installer-run");
        if (!marker.isFile() || !RUNNING.compareAndSet(false, true)) return;
        if (!marker.delete()) { RUNNING.set(false); throw new IllegalStateException("Cannot consume installer fixture marker"); }
        new Thread(() -> probe(context), "InstallerFixture").start();
    }
    private static void probe(Context context) {
        JSONObject report = new JSONObject(); String stage = "start";
        PackageInstaller installer = null; boolean registered = false, callbackRegistered = false;
        Map<Integer, CountDownLatch> waiting = new ConcurrentHashMap<>();
        Map<Integer, Integer> statuses = new ConcurrentHashMap<>();
        Map<Integer, Boolean> finished = new ConcurrentHashMap<>();
        Map<Integer, CountDownLatch> finishedWaiting = new ConcurrentHashMap<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent result) {
                int id = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
                int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
                statuses.put(id, status); CountDownLatch latch = waiting.get(id); if (latch != null) latch.countDown();
            }
        };
        PackageInstaller.SessionCallback callback = new PackageInstaller.SessionCallback() {
            @Override public void onCreated(int id) { }
            @Override public void onBadgingChanged(int id) { }
            @Override public void onActiveChanged(int id, boolean active) { }
            @Override public void onProgressChanged(int id, float value) { }
            @Override public void onFinished(int id, boolean success) {
                finished.put(id, success); CountDownLatch latch = finishedWaiting.get(id); if (latch != null) latch.countDown();
            }
        };
        try {
            installer = context.getPackageManager().getPackageInstaller();
            IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, filter);
            registered = true;
            installer.registerSessionCallback(callback, new Handler(Looper.getMainLooper())); callbackRegistered = true;
            stage = "unsupported-mode";
            try { installer.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_INHERIT_EXISTING)); throw new AssertionError("Inherit mode was accepted"); }
            catch (UnsupportedOperationException expected) { }
            report.put("inheritRejected", true);
            stage = "explicit-constraints";
            rejectedParameter(installer, "installFlags", Integer.valueOf(0x00400100));
            rejectedParameter(installer, "abiOverride", "unsupported-fixture-abi");
            rejectedParameter(installer, "volumeUuid", "fixture-volume");
            if (Build.VERSION.SDK_INT >= 31) {
                rejectedParameter(installer, "requiredInstalledVersionCode", Long.valueOf(1));
                rejectedParameter(installer, "requireUserAction", Integer.valueOf(1));
            }
            if (Build.VERSION.SDK_INT >= 34) {
                PackageInstaller.SessionParams permissions = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                Field states = PackageInstaller.SessionParams.class.getDeclaredField("mPermissionStates"); states.setAccessible(true);
                @SuppressWarnings("unchecked") Map<String, Integer> values = (Map<String, Integer>) states.get(permissions);
                values.put("android.permission.CAMERA", 1);
                try { installer.createSession(permissions); throw new AssertionError("Permission states were silently ignored"); }
                catch (UnsupportedOperationException expected) { }
            }
            report.put("explicitConstraintsRejected", true);
            android.content.pm.PackageInfo before = context.getPackageManager().getPackageInfo(TARGET, android.content.pm.PackageManager.GET_SIGNATURES);
            requireInventory(context, before);
            stage = "pfd-update-create-session";
            PackageInstaller.SessionParams update = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            update.setAppPackageName(TARGET);
            android.graphics.Bitmap initialIcon = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888);
            initialIcon.eraseColor(0xff336699); update.setAppIcon(initialIcon);
            if (Build.VERSION.SDK_INT >= 34) update.setRequestUpdateOwnership(true);
            int id = installer.createSession(update);
            require(installer.getSessionInfo(id).getAppIcon().getPixel(0, 0) == 0xff336699, "Initial session icon was lost");
            android.graphics.Bitmap changedIcon = android.graphics.Bitmap.createBitmap(256, 128, android.graphics.Bitmap.Config.ARGB_8888);
            changedIcon.eraseColor(0xff993366); installer.updateSessionAppIcon(id, changedIcon);
            installer.updateSessionAppLabel(id, "Updated icon fixture");
            android.graphics.Bitmap icon = installer.getSessionInfo(id).getAppIcon();
            require(icon != null && icon.getWidth() == 96 && icon.getHeight() == 48 && icon.getPixel(0, 0) == 0xff993366, "Updated session icon changed");
            installer.updateSessionAppIcon(id, null); require(installer.getSessionInfo(id).getAppIcon() == null, "Null session icon did not clear");
            initialIcon.recycle(); changedIcon.recycle(); report.put("sessionIconUpdated", true);
            if (Build.VERSION.SDK_INT >= 34) require(!installer.getSessionInfo(id).isRequestUpdateOwnership(), "Declined update ownership appeared in SessionInfo");
            stage = "pfd-update-open-session";
            try (PackageInstaller.Session session = installer.openSession(id)) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Field remoteField = PackageInstaller.Session.class.getDeclaredField("mSession"); remoteField.setAccessible(true);
                    Object remote = remoteField.get(session);
                    require(Class.forName("android.content.pm.IPackageInstallerSession").getMethod("getDataLoaderParams").invoke(remote) == null,
                            "Ordinary session fabricated a DataLoader");
                    report.put("nonStreamingDataLoaderAbsent", true);
                }
                if (Build.VERSION.SDK_INT >= 34) require(!session.isRequestUpdateOwnership(), "Declined update ownership appeared in Session");
                stage = "pfd-update-read-apk-asset";
                byte[] apk = asset(context, "installer-target-v2.apk"); int middle = apk.length / 2;
                stage = "pfd-update-open-write-first";
                try (OutputStream output = session.openWrite("download-base", 0, apk.length)) {
                    stage = "pfd-update-write-first"; output.write(apk, 0, middle);
                    stage = "pfd-update-fsync-first"; session.fsync(output);
                    stage = "pfd-update-close-first";
                }
                stage = "pfd-update-open-write-resume";
                try (OutputStream output = session.openWrite("download-base", middle, apk.length)) {
                    stage = "pfd-update-write-resume"; output.write(apk, middle, apk.length - middle);
                    stage = "pfd-update-fsync-resume"; session.fsync(output);
                    stage = "pfd-update-close-resume";
                }
                stage = "pfd-update-open-read";
                try (InputStream input = session.openRead("download-base")) {
                    stage = "pfd-update-read-and-compare";
                    require(Arrays.equals(hash(apk), hash(read(input))), "PFD read/resume changed APK bytes");
                    stage = "pfd-update-close-read";
                }
                stage = "pfd-update-get-names";
                require(Arrays.asList(session.getNames()).contains("download-base"), "Staged filename missing");
                report.put("pfdRoundTrip", true);
                stage = "pfd-update-dex-metadata-and-checksums";
                byte[] metadata = "fixture-dex-metadata-not-an-apk".getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = session.openWrite("download-base.dm", 0, metadata.length)) { output.write(metadata); session.fsync(output); }
                try (InputStream input = session.openRead("download-base.dm")) { require(Arrays.equals(metadata, read(input)), "Staging metadata changed"); }
                require(Arrays.asList(session.getNames()).contains("download-base.dm"), "Metadata disappeared from staging");
                if (Build.VERSION.SDK_INT >= 31) {
                    Api31.checksums(session, apk);
                    report.put("checksumHintsAccepted", true);
                }
                report.put("dexMetadataStaged", true);
                stage = "pfd-update-seal";
                boolean explicitSeal = seal(session);
                report.put("explicitSealApi", explicitSeal);
                if (explicitSeal) {
                    stage = "pfd-update-reject-sealed-write";
                    rejectSealedWrite(session);
                }
                stage = "pfd-update-commit-and-await-status";
                commit(context, session, id, waiting, statuses, finishedWaiting, finished, PackageInstaller.STATUS_SUCCESS);
                stage = "pfd-update-reject-committed-write";
                rejectSealedWrite(session);
                report.put("sealed", true);
                stage = "pfd-update-close-session";
            }
            stage = "pfd-update-query-published-package";
            require(context.getPackageManager().getPackageInfo(TARGET, 0).versionCode == 2, "Successful commit did not install version 2");
            android.content.pm.PackageInfo published = context.getPackageManager().getPackageInfo(TARGET, android.content.pm.PackageManager.GET_SIGNATURES);
            require(!before.applicationInfo.sourceDir.equals(published.applicationInfo.sourceDir), "Successful commit reused the old generation");
            requireInventory(context, published); report.put("publishedInventoryFresh", true);
            require(installer.getSessionInfo(id).isSealed(), "Committed session did not report sealed");
            if (Build.VERSION.SDK_INT >= 34) {
                require(context.getPackageManager().getInstallSourceInfo(TARGET).getUpdateOwnerPackageName() == null,
                        "An optional request fabricated an update owner");
                report.put("updateOwnershipDeclined", true);
            }
            report.put("realCommit", true); report.put("statusBroadcast", true); report.put("sessionListener", true);
            stage = "downgrade";
            failed(context, installer, TARGET, "installer-target-v1.apk", waiting, statuses, finishedWaiting, finished);
            require(context.getPackageManager().getPackageInfo(TARGET, 0).versionCode == 2, "Downgrade replaced installed code");
            report.put("downgradeRejected", true);
            stage = "target-mismatch";
            failed(context, installer, "org.mybox.fixture.wrongtarget", "installer-target-v2.apk", waiting, statuses, finishedWaiting, finished);
            require(context.getPackageManager().getPackageInfo(TARGET, 0).versionCode == 2, "Failed target validation replaced old package");
            report.put("targetMismatchRejected", true);
            stage = "path-and-abandon";
            int abandoned = create(installer, TARGET);
            try (PackageInstaller.Session session = installer.openSession(abandoned)) {
                try { session.openWrite("../escaped.apk", 0, 10); throw new AssertionError("Unsafe entry name was accepted"); }
                catch (IllegalArgumentException expected) { }
                try (OutputStream output = session.openWrite("revoked", 0, 3)) { output.write(new byte[]{1, 2, 3}); session.fsync(output); }
                InputStream read = session.openRead("revoked");
                session.abandon();
                try { read.read(); throw new AssertionError("Abandoned descriptor remained readable"); }
                catch (java.io.IOException expected) { }
                finally { read.close(); }
            }
            report.put("abandonRevokesPfd", true); report.put("pathRejected", true);
            stage = "abandon-after-close";
            int closedId = create(installer, TARGET);
            PackageInstaller.Session closed = installer.openSession(closedId);
            closed.close(); closed.abandon();
            require(installer.getSessionInfo(closedId).isSealed(), "Closed-session abandon did not finish");
            report.put("abandonAfterClose", true);
        } catch (Throwable failure) {
            recordFailure(report, stage, failure);
        } finally {
            try { if (callbackRegistered) installer.unregisterSessionCallback(callback); if (registered) context.unregisterReceiver(receiver); }
            catch (RuntimeException failure) {
                if (!report.has("failure")) recordFailure(report, "callback-cleanup", failure);
                else try { report.put("cleanupTrace", trace(failure)); } catch (Exception ignored) { }
            }
        }
        try {
            File temporary = new File(context.getFilesDir(), "installer-result.json.tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(report.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
            if (!temporary.renameTo(new File(context.getFilesDir(), "installer-result.json"))) throw new IllegalStateException("Installer report publication failed");
        } catch (Exception failure) { throw new IllegalStateException("Installer report publication failed", failure); }
        finally { RUNNING.set(false); }
    }

    @android.annotation.TargetApi(31)
    private static final class Api31 {
        static void checksums(PackageInstaller.Session session, byte[] apk) throws Exception {
            session.setChecksums("download-base", java.util.Collections.singletonList(
                    new android.content.pm.Checksum(android.content.pm.Checksum.TYPE_WHOLE_SHA256, hash(apk))), null);
            try {
                session.setChecksums("../escaped.apk", java.util.Collections.singletonList(
                        new android.content.pm.Checksum(android.content.pm.Checksum.TYPE_WHOLE_SHA256, hash(apk))), null);
                throw new AssertionError("Unsafe checksum entry was accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static void requireInventory(Context context, android.content.pm.PackageInfo expected) throws Exception {
        int matches = 0;
        for (android.content.pm.PackageInfo info : context.getPackageManager().getInstalledPackages(android.content.pm.PackageManager.GET_SIGNATURES)) {
            if (!TARGET.equals(info.packageName)) continue;
            matches++;
            require(info.versionCode == expected.versionCode && info.applicationInfo.sourceDir.equals(expected.applicationInfo.sourceDir)
                    && java.util.Arrays.equals(info.signatures, expected.signatures), "Published package inventory has stale metadata or signer");
        }
        require(matches == 1, "Published package inventory is absent or duplicated");
        matches = 0;
        for (android.content.pm.ApplicationInfo info : context.getPackageManager().getInstalledApplications(0)) {
            if (TARGET.equals(info.packageName)) {
                matches++; require(info.sourceDir.equals(expected.applicationInfo.sourceDir), "Published application inventory is stale");
            }
        }
        require(matches == 1, "Published application inventory is absent or duplicated");
    }

    private static void failed(Context context, PackageInstaller installer, String expectedPackage, String asset,
            Map<Integer, CountDownLatch> waiting, Map<Integer, Integer> statuses, Map<Integer, CountDownLatch> finishedWaiting,
            Map<Integer, Boolean> finished) throws Exception {
        int id = create(installer, expectedPackage);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            byte[] apk = asset(context, asset);
            try (OutputStream output = session.openWrite("base", 0, apk.length)) { output.write(apk); session.fsync(output); }
            commit(context, session, id, waiting, statuses, finishedWaiting, finished, PackageInstaller.STATUS_FAILURE_INVALID);
        }
    }
    private static void commit(Context context, PackageInstaller.Session session, int id, Map<Integer, CountDownLatch> waiting,
            Map<Integer, Integer> statuses, Map<Integer, CountDownLatch> finishedWaiting, Map<Integer, Boolean> finished, int expected) throws Exception {
        CountDownLatch delivered = new CountDownLatch(1), completed = new CountDownLatch(1); waiting.put(id, delivered); finishedWaiting.put(id, completed);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent result = PendingIntent.getBroadcast(context, id, new Intent(ACTION).setPackage(context.getPackageName()), flags);
        try {
            session.commit(result.getIntentSender());
            require(delivered.await(25, TimeUnit.SECONDS), "Installation status broadcast was not delivered");
            require(statuses.get(id) == expected, "Installation status did not match real publication");
            require(completed.await(10, TimeUnit.SECONDS), "Installation session callback was not delivered");
            require(finished.get(id) == (expected == PackageInstaller.STATUS_SUCCESS), "Session callback disagreed with publication");
        } finally { result.cancel(); waiting.remove(id); finishedWaiting.remove(id); }
    }
    private static int create(PackageInstaller installer, String packageName) throws Exception {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName); params.setAppLabel("Mybox installer fixture"); return installer.createSession(params);
    }
    private static void rejectedParameter(PackageInstaller installer, String name, Object value) throws Exception {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        Field field = PackageInstaller.SessionParams.class.getDeclaredField(name); field.setAccessible(true); field.set(params, value);
        try { installer.createSession(params); throw new AssertionError("Explicit session constraint was silently ignored: " + name); }
        catch (UnsupportedOperationException expected) { }
    }
    private static boolean seal(PackageInstaller.Session session) throws Exception {
        Field field = PackageInstaller.Session.class.getDeclaredField("mSession"); field.setAccessible(true); Object remote = field.get(session);
        try { Class.forName("android.content.pm.IPackageInstallerSession").getMethod("seal").invoke(remote); return true; }
        catch (NoSuchMethodException absent) { if (Build.VERSION.SDK_INT >= 33) throw absent; return false; }
        catch (InvocationTargetException failure) { if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause(); throw failure; }
    }
    private static void rejectSealedWrite(PackageInstaller.Session session) throws Exception {
        try { session.openWrite("after-seal", 0, 1); throw new AssertionError("Sealed session remained writable"); }
        catch (SecurityException expected) { }
    }
    private static byte[] asset(Context context, String name) throws Exception { try (InputStream input = context.getAssets().open(name)) { return read(input); } }
    private static byte[] read(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int count;
        while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count); return output.toByteArray();
    }
    private static byte[] hash(byte[] value) throws Exception { return MessageDigest.getInstance("SHA-256").digest(value); }
    private static void recordFailure(JSONObject report, String stage, Throwable failure) {
        // This fixture uses only its own generated APKs and synthetic session metadata.
        try { report.put("failure", failure.getClass().getSimpleName()); report.put("stage", stage); report.put("trace", trace(failure)); }
        catch (Exception ignored) { }
    }
    private static String trace(Throwable failure) {
        StringWriter text = new StringWriter(); failure.printStackTrace(new PrintWriter(text)); return text.toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private InstallerProbe() { }
}
