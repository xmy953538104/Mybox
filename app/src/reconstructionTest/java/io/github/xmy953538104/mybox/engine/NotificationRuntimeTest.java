package io.github.xmy953538104.mybox.engine;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.test.InstrumentationTestCase;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;

/** Actual SystemUI touch and guest callback, independently observed from the host. */
public final class NotificationRuntimeTest extends InstrumentationTestCase {
    public void testPhysicalNotificationContentAndActionOpenGuest() throws Exception {
        Context host = getInstrumentation().getTargetContext();
        Engine engine = Engine.get();
        UiAutomation automation = getInstrumentation().getUiAutomation();
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(host.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        InstalledPackage installed = engine.packages().get("io.github.xmy953538104.mybox.fixture");
        if (installed == null) {
            File apk = new File(host.getCacheDir(), "notification-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                    Files.copy(input, apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                installed = engine.install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        String run = UUID.randomUUID().toString(), title = "Mylite notice " + run;
        File files = new File(installed.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare notification fixture");
        Files.write(new File(files, "notification-pending-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(installed, "notification-pending"); engine.launch(installed.packageName);
        await(new File(files, "notification-pending-posted-" + run + ".json"));
        NotificationManager manager = host.getSystemService(NotificationManager.class);
        StatusBarNotification posted = null;
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        do {
            for (StatusBarNotification candidate : manager.getActiveNotifications()) {
                if (title.contentEquals(candidate.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE, ""))) posted = candidate;
            }
            if (posted == null) Thread.sleep(50);
        } while (posted == null && SystemClock.elapsedRealtime() < deadline);
        assertNotNull("Android did not actually post the guest notification", posted);
        Notification notification = posted.getNotification();
        assertEquals(host.getPackageName(), posted.getPackageName());
        assertTrue(notification.contentIntent.isActivity());
        assertEquals(host.getPackageName(), notification.contentIntent.getCreatorPackage());
        assertEquals(1, notification.actions.length); assertTrue(notification.actions[0].actionIntent.isActivity());
        assertNotNull(notification.getSmallIcon().loadDrawable(host));
        assertFalse("Posting launched the target", new File(files, "notification-pending-event-" + run + "-content.json").exists());
        android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
        int flags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        try {
            shell(automation, "cmd statusbar expand-notifications");
            touch(automation, "Mylite action");
            assertEquals("action", await(new File(files, "notification-pending-event-" + run + "-action.json")).getString("kind"));
            shell(automation, "cmd statusbar expand-notifications");
            touch(automation, title);
            JSONObject result = await(new File(files, "notification-pending-event-" + run + "-content.json"));
            assertEquals("content", result.getString("kind")); assertEquals(installed.packageName, result.getString("package"));
            assertTrue(result.getInt("pid") > 0);
            System.out.println("MYLITE_NOTIFICATION physicalPost=true systemUiActionTouch=true systemUiContentTouch=true guestCallbacks=true");
        } finally {
            manager.cancel(posted.getId()); info.flags = flags; automation.setServiceInfo(info);
            shell(automation, "cmd statusbar collapse");
        }
    }

    private static void shell(UiAutomation automation, String command) throws Exception {
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024]; while (input.read(buffer) != -1) { }
        }
    }
    private static JSONObject await(File file) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (!file.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertTrue("No real notification callback: " + file.getName(), file.isFile());
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    private static void touch(UiAutomation automation, String text) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        do {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot(); window.recycle();
                if (root == null) continue;
                Rect bounds;
                try { bounds = find(root, text); } finally { root.recycle(); }
                if (bounds != null && !bounds.isEmpty()) {
                    WebInputRuntimeTest.tap(automation, bounds.centerX(), bounds.centerY(), 50); return;
                }
            }
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Actual SystemUI touch target absent: " + text);
    }
    private static Rect find(AccessibilityNodeInfo node, String text) {
        if (node.isVisibleToUser() && text.contentEquals(node.getText() == null ? "" : node.getText())) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds); return bounds;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { Rect found = find(child, text); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }
}
