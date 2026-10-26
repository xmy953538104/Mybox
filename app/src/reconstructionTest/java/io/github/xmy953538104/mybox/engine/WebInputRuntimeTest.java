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

/** Synthetic clipboard only: proves actual IME visibility and Chromium's long-press Paste path. */
public final class WebInputRuntimeTest extends InstrumentationTestCase {
    public void testInjectedTouchRetainsFingerTypeAndStreamCoordinates() {
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
            MotionEvent event = fingerEvent(down, down + 20, action, 42, 73);
            try {
                assertEquals(InputDevice.SOURCE_TOUCHSCREEN, event.getSource());
                assertEquals(MotionEvent.TOOL_TYPE_FINGER, event.getToolType(0));
                assertEquals(1, event.getPointerCount()); assertEquals(0, event.getPointerId(0));
                assertEquals(action, event.getAction()); assertEquals(down, event.getDownTime());
                assertEquals(down + 20, event.getEventTime());
                assertEquals(42f, event.getX(), 0f); assertEquals(73f, event.getY(), 0f);
            } finally { event.recycle(); }
        }
    }
    public void testClipboardPreviewDetectionMatchesTheTouchableWindowNotItsGestureMonitor() {
        assertTrue(hasClipboardPreview("      3: name='3944c72 ClipboardOverlay', id=268, displayId=0, inputConfig=NOT_FOCUSABLE"));
        assertFalse(hasClipboardPreview("      0: name='[Gesture Monitor] clipboard overlay', id=267"));
        assertFalse(hasClipboardPreview("FocusedWindow=ClipboardOverlay\n  6: name='c3c18b3 PopupWindow:3dd9db', id=270"));
    }
    public void testTouchRectangleExcludesActualKeyboardOcclusionWithoutChangingTheTarget() {
        Rect target = new Rect(0, 0, 100, 100);
        assertEquals(new Rect(0, 0, 100, 60), outside(target, new Rect(0, 60, 100, 120)));
        Rect floating = new Rect(40, 40, 60, 60), safe = outside(target, floating);
        assertEquals(new Rect(0, 0, 100, 40), safe); assertFalse(Rect.intersects(safe, floating));
        assertTrue(outside(target, new Rect(-1, -1, 101, 101)).isEmpty());
        assertEquals(target, outside(target, new Rect(200, 0, 300, 100)));
        assertEquals(new Rect(0, 0, 100, 100), target);
    }
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
            screenshot(automation, context, "keyboard");
            syncInput(automation);
            editor = awaitNode(automation, true);
            try { bounds.set(editorTouchBounds(automation, editor)); } finally { editor.recycle(); }
            tap(automation, bounds.centerX(), bounds.centerY(), android.view.ViewConfiguration.getLongPressTimeout() + 250);
            syncInput(automation);
            AccessibilityNodeInfo paste = awaitNode(automation, false);
            try {
                paste.getBoundsInScreen(bounds);
                assertFalse("The actual Paste control has no touch target", bounds.isEmpty());
                screenshot(automation, context, "paste-menu");
                retainInputRouting(automation, context, "web-input-paste-menu");
                touchPaste(automation, paste);
            }
            finally { paste.recycle(); }
            awaitFile(pasted);
            assertEquals("true", new String(java.nio.file.Files.readAllBytes(pasted.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            Thread.sleep(1000);
            awaitNode(automation, true).recycle();
            screenshot(automation, context, "pasted");
            System.out.println("MYLITE_WEB_INPUT keyboard=true longPress=true paste=true survived=true");
        } finally {
            clipboard.clearPrimaryClip();
            info = automation.getServiceInfo(); info.flags = flags; automation.setServiceInfo(info);
        }
    }
    public static AccessibilityNodeInfo awaitNode(UiAutomation automation, boolean editor) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                if (!editor && window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) { window.recycle(); continue; }
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
                : isPasteText(node.getText()))) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { AccessibilityNodeInfo found = find(child, editor); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }
    private static boolean isPasteText(CharSequence text) {
        return text != null && ("Paste".equalsIgnoreCase(text.toString())
                || android.content.res.Resources.getSystem().getString(android.R.string.paste).contentEquals(text));
    }
    public static void touchPaste(UiAutomation automation, AccessibilityNodeInfo paste) throws Exception {
        syncInput(automation);
        assertTrue("The actual Paste node disappeared before touch", paste.refresh());
        Rect bounds = new Rect(); paste.getBoundsInScreen(bounds);
        assertFalse("The current Paste node has no touch bounds", bounds.isEmpty());
        System.out.println("MYLITE_PASTE_TOUCH bounds=" + bounds + " window=" + paste.getWindowId());
        try {
            android.view.accessibility.AccessibilityEvent event = automation.executeAndWaitForEvent(() -> {
                try { tap(automation, bounds.centerX(), bounds.centerY(), 50); }
                catch (Exception failure) { throw new IllegalStateException("Actual Paste touch failed", failure); }
            }, value -> value.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED
                    && value.getText().stream().anyMatch(WebInputRuntimeTest::isPasteText), 2000);
            try { System.out.println("MYLITE_PASTE_CLICK eventObserved=true package=" + event.getPackageName() + " class=" + event.getClassName()); }
            finally { event.recycle(); }
        } catch (java.util.concurrent.TimeoutException unobserved) {
            // Diagnostic only: actual full text readback remains mandatory, regardless of this event.
            System.out.println("MYLITE_PASTE_CLICK eventObserved=false");
        }
    }
    public static void syncInput(UiAutomation automation) throws Exception {
        // Synchronize before obtaining coordinates; DOWN's own sync may otherwise move that target.
        RuntimeReflection.invoke(automation, RuntimeReflection.method(UiAutomation.class, "syncInputTransactions"));
    }
    public static void awaitRealClipboardPreviewDismissal(UiAutomation automation) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        long start = SystemClock.elapsedRealtime(), deadline = start + 20000;
        boolean observed = false;
        do {
            boolean present = hasClipboardPreview(inputState(automation));
            if (present && !observed) { observed = true; System.out.println("MYLITE_CLIPBOARD_PREVIEW observed=true"); }
            if (observed && !present) {
                System.out.println("MYLITE_CLIPBOARD_PREVIEW actuallyRemoved=true elapsedMs=" + (SystemClock.elapsedRealtime()-start));
                return;
            }
            if (!observed && SystemClock.elapsedRealtime()-start > 5000) {
                throw new AssertionError("This cloud image did not expose the expected real clipboard preview");
            }
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("The actual system clipboard preview still obscures the interaction");
    }
    private static boolean hasClipboardPreview(String input) {
        return java.util.regex.Pattern.compile("(?m)^\\s*\\d+: name='[^'\\r\\n]* ClipboardOverlay',").matcher(input).find();
    }
    private static String inputState(UiAutomation automation) throws Exception {
        try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys input"));
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size()+count > 1024*1024) throw new java.io.IOException("Input state exceeds bound");
                output.write(buffer,0,count);
            }
            return new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    public static void retainInputRouting(UiAutomation automation, Context context, String phase) throws Exception {
        for (String service : new String[]{"input", "window windows"}) {
            String suffix = service.startsWith("window") ? "window" : "input";
            try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys " + service));
                    java.io.FileOutputStream output = context.openFileOutput(phase + "-" + suffix + ".txt", Context.MODE_PRIVATE)) {
                byte[] buffer = new byte[8192]; int count, total = 0;
                while ((count = input.read(buffer)) != -1) {
                    if ((total += count) > 1024 * 1024) throw new java.io.IOException("Input routing diagnostic exceeds bound");
                    output.write(buffer, 0, count);
                }
            }
        }
    }
    public static MotionEvent fingerEvent(long down, long time, int action, float x, float y) {
        // DocumentsUI routes gestures by tool type; setSource alone leaves TOOL_TYPE_UNKNOWN.
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0; pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = x; coordinates.y = y; coordinates.size = 1;
        coordinates.pressure = action == MotionEvent.ACTION_UP ? 0 : 1;
        return MotionEvent.obtain(down, time, action, 1, new MotionEvent.PointerProperties[]{pointer},
                new MotionEvent.PointerCoords[]{coordinates}, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
    public static void tap(UiAutomation automation, float x, float y, int hold) throws Exception {
        long down = SystemClock.uptimeMillis();
        MotionEvent event = fingerEvent(down, down, MotionEvent.ACTION_DOWN, x, y);
        try { assertTrue(automation.injectInputEvent(event, true)); } finally { event.recycle(); }
        Thread.sleep(hold);
        event = fingerEvent(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
        try { assertTrue(automation.injectInputEvent(event, true)); } finally { event.recycle(); }
    }
    public static Rect editorTouchBounds(UiAutomation automation, AccessibilityNodeInfo editor) {
        Rect bounds = new Rect(); editor.getBoundsInScreen(bounds);
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                Rect keyboard = new Rect(); window.getBoundsInScreen(keyboard);
                Rect original = new Rect(bounds);
                bounds = outside(bounds, keyboard);
                System.out.println("MYLITE_EDITOR_OCCLUSION editor=" + original + " keyboard=" + keyboard + " touch=" + bounds);
            } finally { window.recycle(); }
        }
        assertFalse("The actual editor is fully obscured by the IME", bounds.isEmpty());
        return bounds;
    }
    private static Rect outside(Rect target, Rect blocker) {
        if (!Rect.intersects(target, blocker)) return new Rect(target);
        Rect[] candidates = {
            new Rect(target.left, target.top, target.right, Math.min(target.bottom, blocker.top)),
            new Rect(target.left, Math.max(target.top, blocker.bottom), target.right, target.bottom),
            new Rect(target.left, target.top, Math.min(target.right, blocker.left), target.bottom),
            new Rect(Math.max(target.left, blocker.right), target.top, target.right, target.bottom)
        };
        Rect best = new Rect(); long area = 0;
        for (Rect candidate : candidates) if (!candidate.isEmpty() && (long) candidate.width() * candidate.height() > area) {
            best = candidate; area = (long) candidate.width() * candidate.height();
        }
        return best;
    }
    private static void awaitFile(File file) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (!file.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertTrue("Synthetic input phase did not complete: " + file.getName(), file.isFile());
    }
    private static void screenshot(UiAutomation automation, Context context, String phase) throws Exception {
        android.graphics.Bitmap bitmap = automation.takeScreenshot();
        assertNotNull("Cannot retain the actual input interaction", bitmap);
        try (java.io.FileOutputStream output = context.openFileOutput("web-input-" + phase + ".png", Context.MODE_PRIVATE)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
}
