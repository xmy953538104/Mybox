package io.github.xmy953538104.mybox;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Icon;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

/** Independent OS observations prevent a virtual foreground flag from passing this regression. */
public final class ForegroundRuntimeTest extends InstrumentationTestCase {
    public void testIndependentPhysicalForegroundServicesAndNotifications() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ((MyboxApplication) context.getApplicationContext()).requireEngine();
        if (Build.VERSION.SDK_INT >= 33) getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        String fixtureName = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage fixture = Engine.get().packages().get(fixtureName);
        if (fixture == null) {
            File apk = new File(context.getCacheDir(), "engine-fixture-foreground.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) { MainActivity.copyApk(input, apk); }
                fixture = Engine.get().install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        File files = new File(fixture.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare foreground fixture");
        String run = UUID.randomUUID().toString();
        Files.write(new File(files, "foreground-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(fixture, "foreground");
        Engine.get().launch(fixtureName);
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        ActivityManager activities = context.getSystemService(ActivityManager.class);
        int physicalA = 0, physicalB = 0;
        for (String phase : new String[]{"both", "removed", "detached", "cancelled"}) {
            JSONObject state = waitPhase(files, run, phase);
            int pid = state.getInt("pid");
            long deadline = SystemClock.elapsedRealtime() + 15_000;
            String last = "No snapshot";
            boolean matched = false;
            do {
                List<ActivityManager.RunningServiceInfo> services = new ArrayList<>();
                for (ActivityManager.RunningServiceInfo service : activities.getRunningServices(100)) {
                    if (service.pid == pid && service.service.getClassName().contains(".GuestServiceStub$")) services.add(service);
                }
                StatusBarNotification a = notification(notifications, run, "A"), b = notification(notifications, run, "B");
                int foreground = 0;
                for (ActivityManager.RunningServiceInfo service : services) if (service.foreground) foreground++;
                last = "phase=" + phase + ", running=" + services.size() + ", foreground=" + foreground + ", A=" + (a != null) + ", B=" + (b != null);
                if (phase.equals("both")) {
                    matched = services.size() == 2 && foreground == 2 && a != null && b != null;
                    if (matched) {
                        assertFalse("Two guest Services shared one real ServiceRecord", services.get(0).service.equals(services.get(1).service));
                        physicalA = a.getId(); physicalB = b.getId();
                        assertTrue(physicalA != physicalB && physicalA != 0 && physicalB != 0);
                        verifyNotification(context, notifications, a, "fixture-fgs-" + run, 2);
                        verifyNotification(context, notifications, b, "fixture-fgs-" + run, 1);
                    }
                } else if (phase.equals("removed")) {
                    matched = services.size() == 1 && foreground == 1 && a == null && b != null;
                    if (matched) assertEquals("Remaining foreground notification was remapped", physicalB, b.getId());
                } else if (phase.equals("detached")) {
                    matched = services.isEmpty() && a == null && b != null;
                    if (matched) assertEquals("Detached notification was replaced", physicalB, b.getId());
                } else matched = services.isEmpty() && a == null && b == null;
                if (!matched) Thread.sleep(100);
            } while (!matched && SystemClock.elapsedRealtime() < deadline);
            assertTrue("Real Android state mismatch: " + last, matched);
            Files.write(new File(files, "foreground-ack-" + run + "-" + phase).toPath(), new byte[]{1});
        }
        File result = new File(files, "foreground-result-" + run + ".json");
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (!result.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertTrue("Foreground fixture did not finish", result.isFile());
        JSONObject report = new JSONObject(new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8));
        assertEquals(run, report.getString("runId")); assertFalse(report.toString(), report.has("failure"));
        for (String check : new String[]{"startId", "capacityRejected", "explicitFailures", "channelAndTag", "removeIndependent", "detachRetained", "destroyExactlyOnce"}) {
            assertTrue(check + ": " + report, report.getBoolean(check));
        }
        assertTrue("Real foreground notifications were never observed", physicalA != 0 && physicalB != 0);
    }

    private static JSONObject waitPhase(File files, String run, String phase) throws Exception {
        File state = new File(files, "foreground-phase-" + run + ".json"), result = new File(files, "foreground-result-" + run + ".json");
        long deadline = SystemClock.elapsedRealtime() + 45_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (result.isFile()) {
                JSONObject report = new JSONObject(new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8));
                if (report.has("failure")) throw new AssertionError(report.toString());
            }
            if (state.isFile()) {
                JSONObject value = new JSONObject(new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8));
                if (run.equals(value.optString("runId")) && phase.equals(value.optString("phase"))) return value;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Foreground fixture did not reach phase " + phase);
    }

    private static StatusBarNotification notification(NotificationManager manager, String run, String label) {
        for (StatusBarNotification item : manager.getActiveNotifications()) {
            CharSequence title = item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
            if (("FG " + run + " " + label).contentEquals(title == null ? "" : title)) return item;
        }
        return null;
    }

    private static void verifyNotification(Context host, NotificationManager manager, StatusBarNotification posted, String guestChannel, int progress) {
        Notification notification = posted.getNotification();
        assertEquals(host.getPackageName(), posted.getPackageName());
        assertTrue("Notification is not owned by a real FGS", (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);
        assertFalse("Guest channel was sent raw to Android", guestChannel.equals(notification.getChannelId()));
        assertNotNull("Physical channel is missing", manager.getNotificationChannel(notification.getChannelId()));
        assertEquals(progress, notification.extras.getInt(Notification.EXTRA_PROGRESS));
        assertEquals(10, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX));
        assertNotNull(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        assertNotNull("Guest resource icon was not usable by the host", notification.getSmallIcon().loadDrawable(host));
        if (Build.VERSION.SDK_INT >= 28) assertEquals(Icon.TYPE_BITMAP, notification.getSmallIcon().getType());
    }
}
