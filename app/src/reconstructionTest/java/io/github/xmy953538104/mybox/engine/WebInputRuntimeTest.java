package io.github.xmy953538104.mybox.engine;

import android.app.UiAutomation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;

/** Disposable CI device only: proves actual IME visibility and Chromium's long-press Paste path. */
public final class WebInputRuntimeTest extends InstrumentationTestCase {
    public void testWebViewShowsRealKeyboardAndLongPressPastesWithoutDeath() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Engine engine = Engine.get();
        InstalledPackage installed = engine.packages().get("io.github.xmy953538104.mybox.fixture");
        assertNotNull("The preceding clipboard group must install the current fixture", installed);
        File ready = new File(installed.dataDir, "files/web-input-ready");
        File pasted = new File(installed.dataDir, "files/web-input-pasted");
        for (File file : new File[]{ready, pasted}) if (file.exists() && !file.delete()) throw new java.io.IOException("Cannot reset synthetic input result");
        UiAutomation automation = getInstrumentation().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
        int flags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("mylite-test", "mylite_synthetic_input"));
        try {
            FixtureRun.prepare(installed, "web-input"); engine.launch(installed.packageName);
            awaitFile(ready);
            AccessibilityNodeInfo editor = awaitNode(automation, true);
            Rect bounds = new Rect();
            try {
                editor.getBoundsInScreen(bounds);
                tap(automation, bounds.centerX(), bounds.centerY(), 50);
            } finally { editor.recycle(); }
            long deadline = SystemClock.elapsedRealtime() + 15000;
            boolean shown = false;
            do {
                for (AccessibilityWindowInfo window : automation.getWindows()) {
                    shown |= window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD;
                    window.recycle();
                }
                if (!shown) Thread.sleep(100);
            } while (!shown && SystemClock.elapsedRealtime() < deadline);
            assertTrue("The actual Android input-method window never appeared", shown);
            editor = awaitNode(automation, true);
            try { editor.getBoundsInScreen(bounds); } finally { editor.recycle(); }
            tap(automation, bounds.centerX(), bounds.centerY(), android.view.ViewConfiguration.getLongPressTimeout() + 250);
            AccessibilityNodeInfo paste = awaitNode(automation, false);
            try { assertTrue("Real Chromium Paste action was refused", paste.performAction(AccessibilityNodeInfo.ACTION_CLICK)); }
            finally { paste.recycle(); }
            awaitFile(pasted);
            assertEquals("true", new String(java.nio.file.Files.readAllBytes(pasted.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            Thread.sleep(1000);
            awaitNode(automation, true).recycle();
            System.out.println("MYLITE_WEB_INPUT keyboard=true longPress=true paste=true survived=true");
        } finally {
            clipboard.clearPrimaryClip();
            info = automation.getServiceInfo(); info.flags = flags; automation.setServiceInfo(info);
        }
    }
    private static AccessibilityNodeInfo awaitNode(UiAutomation automation, boolean editor) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot(); window.recycle();
                if (root == null) continue;
                AccessibilityNodeInfo found;
                try { found = find(root, editor); } finally { root.recycle(); }
                if (found != null) return found;
            }
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError(editor ? "Synthetic WebView editor disappeared" : "Long-press did not expose the real Paste menu");
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, boolean editor) {
        if (node.isVisibleToUser() && (editor ? node.isEditable() && "android.widget.EditText".contentEquals(node.getClassName())
                : node.getText() != null && "Paste".equalsIgnoreCase(node.getText().toString()))) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { AccessibilityNodeInfo found = find(child, editor); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }
    private static void tap(UiAutomation automation, float x, float y, int hold) throws Exception {
        long down = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(automation.injectInputEvent(event, true)); } finally { event.recycle(); }
        Thread.sleep(hold);
        event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(automation.injectInputEvent(event, true)); } finally { event.recycle(); }
    }
    private static void awaitFile(File file) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (!file.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertTrue("Synthetic input phase did not complete: " + file.getName(), file.isFile());
    }
}
