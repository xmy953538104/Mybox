package io.github.xmy953538104.mybox.engine;

import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/** Window attribution boundaries plus actual system windows and physical touches, not just API returns. */
public final class WindowRuntimeTest extends InstrumentationTestCase {
    public static final class ToastService {
        int calls;
        Object[] arguments;
        RuntimeException failure;
        public boolean enqueueTextToast(String pkg, IBinder token, CharSequence text, int duration,
                boolean ui, int display, Object callback) {
            record(pkg, token, text, duration, ui, display, callback); return false;
        }
        public void enqueueToast(String pkg, IBinder token, Object callback, int duration, boolean ui, int display) {
            record(pkg, token, callback, duration, ui, display);
        }
        public void cancelToast(String pkg, IBinder token) { record(pkg, token); }
        public void finishToken(String pkg, IBinder token) { record(pkg, token); }
        private void record(Object... args) {
            calls++; arguments = args;
            if (failure != null) throw failure;
        }
    }

    public void testToastAllowsLocalGuestAndRecoveryHostWithoutLosingOsSemantics() throws Throwable {
        ToastService original = new ToastService();
        IBinder token = new Binder(); Object callback = new Object();
        CharSequence text = new StringBuilder("recovery toast");
        Method[] methods = {
            ToastService.class.getMethod("enqueueTextToast", String.class, IBinder.class, CharSequence.class,
                    int.class, boolean.class, int.class, Object.class),
            ToastService.class.getMethod("enqueueToast", String.class, IBinder.class, Object.class,
                    int.class, boolean.class, int.class),
            ToastService.class.getMethod("cancelToast", String.class, IBinder.class),
            ToastService.class.getMethod("finishToken", String.class, IBinder.class)
        };
        Object[][] tail = {{token, text, 1, true, 731, callback}, {token, callback, 1, true, 731}, {token}, {token}};
        int uid = android.os.Process.myUid(), pid = android.os.Process.myPid();
        for (int i = 0; i < methods.length; i++) {
            for (String pkg : new String[]{"fixture.guest", "physical.host"}) {
                Object[] args = new Object[tail[i].length + 1]; args[0] = pkg;
                System.arraycopy(tail[i], 0, args, 1, tail[i].length);
                Object result = GuestNotificationCalls.toast(original, methods[i], args,
                        "fixture.guest", "physical.host", uid, pid);
                assertEquals(i == 0 ? Boolean.FALSE : null, result);
                assertEquals(pkg, args[0]); assertEquals("physical.host", original.arguments[0]);
                for (int j = 1; j < args.length; j++) assertEquals(args[j], original.arguments[j]);
                assertSame(token, original.arguments[1]);
                if (i == 0) { assertSame(text, original.arguments[2]); assertSame(callback, original.arguments[6]); }
                if (i == 1) assertSame(callback, original.arguments[2]);
            }
            int before = original.calls;
            for (Object[] rejected : new Object[][]{
                    {"foreign.package", uid, pid}, {"physical.host", uid + 1, pid},
                    {"fixture.guest", uid, pid + 1}, {null, uid, pid}}) {
                Object[] args = new Object[tail[i].length + 1]; args[0] = rejected[0];
                System.arraycopy(tail[i], 0, args, 1, tail[i].length);
                try {
                    GuestNotificationCalls.toast(original, methods[i], args, "fixture.guest", "physical.host",
                            (Integer) rejected[1], (Integer) rejected[2]);
                    fail("Foreign or nonlocal Toast reached Android");
                } catch (SecurityException expected) { }
            }
            assertEquals(before, original.calls);
        }
        original.failure = new SecurityException("Actual OS Toast rejection");
        try {
            GuestNotificationCalls.toast(original, methods[2], new Object[]{"physical.host", token},
                    "fixture.guest", "physical.host", uid, pid);
            fail("Actual OS rejection was swallowed");
        } catch (SecurityException expected) { assertSame(original.failure, expected); }
    }

    public static final class Session {
        final IBinder binder = new Binder();
        int calls;
        Object callback, output;
        WindowManager.LayoutParams attributes;
        int[] asyncParameters;
        RuntimeException failure;
        public IBinder asBinder() { return binder; }
        public int addToDisplay(Object callback, WindowManager.LayoutParams attrs, int display, Object output) {
            calls++; this.callback = callback; this.attributes = attrs; this.output = output;
            if (failure != null) throw failure;
            return -8;
        }
        public int relayout(Object callback, WindowManager.LayoutParams attrs, int display, Object output) {
            return addToDisplay(callback, attrs, display, output);
        }
        public void relayoutAsync(Object callback, WindowManager.LayoutParams attrs,
                int width, int height, int visibility, int flags, int seq, int lastSyncSeqId) {
            calls++; this.callback = callback; this.attributes = attrs;
            asyncParameters = new int[]{width, height, visibility, flags, seq, lastSyncSeqId};
        }
        public int relayoutAsync(Object callback, WindowManager.LayoutParams attrs, int width) { calls++; return 1; }
        public void relayoutAsync(Object callback, WindowManager.LayoutParams attrs,
                int width, int height, int visibility, long flags, int seq, int lastSyncSeqId) { calls++; }
        public void relayout(Object callback, WindowManager.LayoutParams first, WindowManager.LayoutParams second) { calls++; }
        public void unknownWindowMethod(WindowManager.LayoutParams attrs) { calls++; }
    }

    public void testAsyncRelayoutPreservesVoidReturnCallbackAndAllSequenceParameters() throws Throwable {
        Session original = new Session();
        GuestWindowCalls adapter = new GuestWindowCalls(original, "fixture.guest", "physical.host", () -> { });
        WindowManager.LayoutParams source = new WindowManager.LayoutParams();
        source.packageName = "fixture.guest"; source.token = new Binder();
        Object callback = new Object();
        Object[] arguments = {callback, source, 317, 211, 4, 8, 19, -1};
        Method method = Session.class.getMethod("relayoutAsync", Object.class, WindowManager.LayoutParams.class,
                int.class, int.class, int.class, int.class, int.class, int.class);
        assertNull("A oneway relayout must not invent a success result", adapter.invoke(null, method, arguments));
        assertEquals(1, original.calls); assertSame(callback, original.callback);
        assertSame(source, arguments[1]); assertNotSame(source, original.attributes);
        assertEquals("fixture.guest", source.packageName); assertEquals("physical.host", original.attributes.packageName);
        assertSame(source.token, original.attributes.token);
        assertTrue(java.util.Arrays.equals(new int[]{317, 211, 4, 8, 19, -1}, original.asyncParameters));
        try {
            adapter.invoke(null, Session.class.getMethod("relayoutAsync", Object.class, WindowManager.LayoutParams.class, int.class),
                    new Object[]{callback, source, 317});
            fail("An unknown asynchronous signature reached the real session");
        } catch (UnsupportedOperationException expected) { }
        try {
            adapter.invoke(null, Session.class.getMethod("relayoutAsync", Object.class, WindowManager.LayoutParams.class,
                    int.class, int.class, int.class, long.class, int.class, int.class),
                    new Object[]{callback, source, 317, 211, 4, 8L, 19, -1});
            fail("An unknown asynchronous parameter type reached the real session");
        } catch (UnsupportedOperationException expected) { }
        assertEquals(1, original.calls);
    }

    public void testAttributionCopiesParametersAndPreservesBinderTokensResultsAndOsDenial() throws Throwable {
        Session original = new Session();
        GuestWindowCalls adapter = new GuestWindowCalls(original, "fixture.guest", "physical.host", () -> { });
        WindowManager.LayoutParams source = new WindowManager.LayoutParams(321, 123,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, -3);
        source.packageName = "fixture.guest"; source.token = new Binder(); source.setTitle("guest popup");
        source.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        Object callback = new Object(), output = new Object();
        Object[] args = {callback, source, 7, output};
        Method add = Session.class.getMethod("addToDisplay", Object.class, WindowManager.LayoutParams.class, int.class, Object.class);
        assertEquals(-8, adapter.invoke(null, add, args));
        assertSame(source, args[1]); assertNotSame(source, original.attributes);
        assertEquals("fixture.guest", source.packageName); assertEquals("physical.host", original.attributes.packageName);
        assertSame(source.token, original.attributes.token); assertSame(callback, original.callback); assertSame(output, original.output);
        assertEquals(source.type, original.attributes.type); assertEquals(source.flags, original.attributes.flags);
        assertEquals(source.softInputMode, original.attributes.softInputMode);
        assertEquals(source.width, original.attributes.width); assertEquals(source.height, original.attributes.height);
        assertEquals(source.getTitle(), original.attributes.getTitle());
        assertSame(original.binder, adapter.invoke(null, Session.class.getMethod("asBinder"), null));
        original.failure = new WindowManager.BadTokenException("Real OS token rejection");
        try { adapter.invoke(null, add, args); fail("OS rejection disappeared"); }
        catch (WindowManager.BadTokenException expected) { assertSame(original.failure, expected); }
        assertEquals(2, original.calls);

        source.packageName = "another.guest";
        try { adapter.invoke(null, add, args); fail("Foreign package reached OS session"); }
        catch (SecurityException expected) { }
        GuestWindowCalls incoming = new GuestWindowCalls(original, "fixture.guest", "physical.host",
                () -> { throw new SecurityException("Incoming Binder caller"); });
        source.packageName = "fixture.guest";
        try { incoming.invoke(null, add, args); fail("Incoming caller borrowed local window attribution"); }
        catch (SecurityException expected) { }
        try { adapter.invoke(null, Session.class.getMethod("unknownWindowMethod", WindowManager.LayoutParams.class), new Object[]{source}); fail("Unknown attributes were silently adapted"); }
        catch (UnsupportedOperationException expected) { }
        try { adapter.invoke(null, add, null); fail("Missing window arguments reached the backend"); }
        catch (IllegalArgumentException expected) { }
        try { adapter.invoke(null, add, new Object[]{callback, source}); fail("Truncated window arguments reached the backend"); }
        catch (IllegalArgumentException expected) { }
        try {
            adapter.invoke(null, Session.class.getMethod("relayout", Object.class, WindowManager.LayoutParams.class, WindowManager.LayoutParams.class),
                    new Object[]{callback, source, source});
            fail("Ambiguous window attributes reached the backend");
        } catch (UnsupportedOperationException expected) { }
        assertEquals(2, original.calls);
        original.failure = null;
        adapter.invoke(null, Session.class.getMethod("relayout", Object.class, WindowManager.LayoutParams.class, int.class, Object.class),
                new Object[]{callback, null, 7, output});
        assertNull("Null relayout attributes must retain platform meaning", original.attributes);
        for (int type : new int[]{WindowManager.LayoutParams.TYPE_SYSTEM_ALERT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY}) {
            source.type = type;
            assertEquals(-8, adapter.invoke(null, add, args));
            assertEquals("The adapter must not upgrade an overlay type or bypass the original rejection", type, original.attributes.type);
            assertEquals(type, source.type);
        }
    }

    public void testRealPopupAndDialogCanBeTouchedAndDismissed() throws Exception {
        InstalledPackage installed = launchFixture();
        UiAutomation automation = getInstrumentation().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
        int flags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        try {
            click(automation, "Open Mylite popup");
            awaitNode(automation, "Dismiss Mylite popup").recycle();
            screenshot(automation, "popup");
            click(automation, "Dismiss Mylite popup");
            awaitFile(installed, "popup-clicked"); awaitFile(installed, "popup-dismissed");
            assertGone(automation, "Dismiss Mylite popup");
            click(automation, "Open Mylite dialog");
            awaitNode(automation, "This is a real guest dialog window").recycle();
            screenshot(automation, "dialog");
            click(automation, "Close Mylite dialog");
            awaitFile(installed, "dialog-clicked"); awaitFile(installed, "dialog-dismissed");
            assertGone(automation, "Close Mylite dialog");
            awaitNode(automation, "Open Mylite popup").recycle();
            System.out.println("MYLITE_WINDOWS popupDisplayed=true popupTouched=true popupDismissed=true dialogDisplayed=true dialogTouched=true dialogDismissed=true");
        } finally { info.flags = flags; automation.setServiceInfo(info); }
    }

    public void testRealToastIsReportedShownAndHiddenByAndroid() throws Exception {
        InstalledPackage installed = launchFixture();
        UiAutomation automation = getInstrumentation().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
        int flags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        try {
            Rect control = new Rect(); AccessibilityNodeInfo button = awaitNode(automation, "Show Mylite toast");
            try { button.getBoundsInScreen(control); } finally { button.recycle(); }
            // AOSP excludes non-touchable Toast windows from getWindows; it sends this event instead.
            android.view.accessibility.AccessibilityEvent event = automation.executeAndWaitForEvent(
                    () -> {
                        try { WebInputRuntimeTest.tap(automation, control.centerX(), control.centerY(), 50); }
                        catch (Exception failure) { throw new IllegalStateException("Actual Toast control touch failed", failure); }
                    },
                    value -> value.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                            && "android.widget.Toast".contentEquals(value.getClassName() == null ? "" : value.getClassName())
                            && value.getText().stream().anyMatch(text -> text != null && "Mylite window toast".contentEquals(text)), 10000);
            try {
                assertNotNull(event.getPackageName());
                assertTrue("Toast event belongs to another application", installed.packageName.contentEquals(event.getPackageName())
                        || getInstrumentation().getTargetContext().getPackageName().contentEquals(event.getPackageName()));
            } finally { event.recycle(); }
            awaitFile(installed, "toast-attached");
            if (Build.VERSION.SDK_INT >= 30) awaitFile(installed, "toast-shown");
            screenshot(automation, "toast");
            if (Build.VERSION.SDK_INT >= 30) awaitFile(installed, "toast-hidden");
            awaitFile(installed, "toast-detached");
            screenshot(automation, "toast-hidden");
            awaitNode(automation, "Show Mylite toast").recycle();
            System.out.println("MYLITE_WINDOWS realToastShown=true realToastHidden=true");
        } catch (Exception | AssertionError failure) {
            try { screenshot(automation, "toast-timeout"); }
            catch (Throwable diagnostic) { failure.addSuppressed(diagnostic); }
            throw failure;
        } finally { info.flags = flags; automation.setServiceInfo(info); }
    }

    private InstalledPackage launchFixture() throws Exception {
        Engine engine = Engine.get();
        Context context = getInstrumentation().getTargetContext();
        InstalledPackage installed = engine.packages().get("io.github.xmy953538104.mybox.fixture");
        if (installed == null) {
            File apk = new File(context.getCacheDir(), "window-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                     FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[16384];
                    for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                }
                installed = engine.install(Collections.singletonList(apk));
            } finally { if (apk.exists() && !apk.delete()) throw new java.io.IOException("Cannot remove staged fixture APK"); }
        }
        for (String phase : new String[]{"ready", "popup-clicked", "popup-dismissed", "dialog-clicked", "dialog-dismissed", "toast-shown", "toast-hidden", "toast-attached", "toast-detached"}) {
            File result = new File(installed.dataDir, "files/window-" + phase);
            if (result.exists() && !result.delete()) throw new java.io.IOException("Cannot reset window result");
        }
        FixtureRun.prepare(installed, "windows"); engine.launch(installed.packageName);
        awaitFile(installed, "ready");
        return installed;
    }

    private static void awaitFile(InstalledPackage installed, String phase) throws Exception {
        File result = new File(installed.dataDir, "files/window-" + phase);
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!result.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
        assertTrue("Actual window phase did not occur: " + phase, result.isFile());
        assertEquals("true", new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8));
    }

    private static AccessibilityNodeInfo awaitNode(UiAutomation automation, String text) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            AccessibilityNodeInfo found = find(automation, text);
            if (found != null) return found;
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Actual visible window control did not appear: " + text);
    }

    private static AccessibilityNodeInfo find(UiAutomation automation, String text) {
        java.util.List<AccessibilityWindowInfo> windows = automation.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try { AccessibilityNodeInfo found = find(root, text); if (found != null) return found; }
                finally { root.recycle(); }
            }
            return null;
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node.isVisibleToUser() && node.getText() != null && text.contentEquals(node.getText())) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { AccessibilityNodeInfo found = find(child, text); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }

    private static void click(UiAutomation automation, String text) throws Exception {
        AccessibilityNodeInfo node = awaitNode(automation, text);
        Rect bounds = new Rect();
        try { node.getBoundsInScreen(bounds); } finally { node.recycle(); }
        assertFalse("Window control has no actual touch target", bounds.isEmpty());
        WebInputRuntimeTest.tap(automation, bounds.centerX(), bounds.centerY(), 50);
    }

    private static void assertGone(UiAutomation automation, String text) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            AccessibilityNodeInfo node = find(automation, text);
            if (node == null) return;
            node.recycle(); Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Window remained visible after dismissal: " + text);
    }

    private void screenshot(UiAutomation automation, String phase) throws Exception {
        android.graphics.Bitmap bitmap = automation.takeScreenshot();
        assertNotNull("Cannot retain actual window evidence", bitmap);
        try (FileOutputStream output = getInstrumentation().getTargetContext().openFileOutput("window-" + phase + ".png", Context.MODE_PRIVATE)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
}
