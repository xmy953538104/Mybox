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
                        if (phase.equals("both")) {
                            String actionRun = UUID.nameUUIDFromBytes((run + ":fgs-action").getBytes(StandardCharsets.UTF_8)).toString();
                            assertNotNull(notification.getNotification().actions);
                            notification.getNotification().actions[0].actionIntent.send();
                            File delivered = new File(files, "pending-manifest-" + actionRun + ".json");
                            long deliveredBy = SystemClock.elapsedRealtime() + 10000;
                            while (!delivered.isFile() && SystemClock.elapsedRealtime() < deliveredBy) Thread.sleep(25);
                            assertTrue("Actual OS implicit notification action did not complete", delivered.isFile());
                            JSONObject action = new JSONObject(new String(Files.readAllBytes(delivered.toPath()), StandardCharsets.UTF_8));
                            assertEquals(actionRun, action.getString("runId")); assertEquals(7, action.getInt("value"));
                            assertTrue("Logical implicit Intent was rewritten", action.getBoolean("logicalComponentAbsent"));
                            assertTrue(action.getBoolean("async"));
                        }
                    }
                }
                if (!matched) Thread.sleep(50);
            } while (!matched && SystemClock.elapsedRealtime() < deadline);
            assertTrue("Actual Service PendingIntent OS state mismatch: " + last, matched);
            if (phase.equals("both")) {
                long wall = System.currentTimeMillis();
                for (String value : new String[]{ordinaryComponent, foregroundComponent}) {
                    android.content.ComponentName component = android.content.ComponentName.unflattenFromString(value);
                    assertNotNull(component);
                    assertEquals(component, context.startService(new android.content.Intent().setComponent(component)));
                }
                String since = String.format(java.util.Locale.ROOT, "%d.%03d", wall / 1000, wall % 1000);
                String command = "logcat -b main -d -v brief --pid=" + pid + " -T " + since + " MyliteService:W *:S";
                long until = SystemClock.elapsedRealtime() + 5000; int refused = 0;
                do {
                    try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                            getInstrumentation().getUiAutomation().executeShellCommand(command));
                         java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            assertTrue(bytes.size() + count <= 1024 * 1024); bytes.write(buffer, 0, count);
                        }
                        String text = bytes.toString("UTF-8"), marker = "Physical Service start refused without its current lease";
                        refused = 0;
                        for (int at = text.indexOf(marker); at >= 0; at = text.indexOf(marker, at + marker.length())) refused++;
                    }
                    if (refused >= 2) break;
                    Thread.sleep(50);
                } while (SystemClock.elapsedRealtime() < until);
                assertEquals("Unleased OS starts did not reach the actual rejection boundary", 2, refused);
                int active = 0;
                for (ActivityManager.RunningServiceInfo service : activities.getRunningServices(100))
                    if (service.pid == pid && (service.service.flattenToString().equals(ordinaryComponent)
                            || service.service.flattenToString().equals(foregroundComponent))) active++;
                assertEquals("Unleased starts stopped/crashed an already authorized Service", 2, active);
            }
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
