package io.github.xmy953538104.googlecheck;

import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import io.github.xmy953538104.mybox.engine.BundledMicrog;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.GoogleComponents;
import io.github.xmy953538104.mybox.engine.WebInputRuntimeTest;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Real signed Store and online Google form; no account, password or authentication success is synthesized. */
public final class GooglePlayJourneyTest extends InstrumentationTestCase {
    private static final String GMS = "com.google.android.gms", LOGIN = "org.microg.gms.auth.login.LoginActivity";
    public void testRealStoreOpensGoogleEditorAndSystemGesturesReturnToStore() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        assertEquals("The disposable journey requires virtual/physical user zero", 0, android.os.Process.myUid() / 100000);
        try {
            android.content.pm.PackageInfo source = context.getPackageManager().getPackageInfo("com.android.vending", android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            System.out.println("MYLITE_STORE_SOURCE version=" + source.getLongVersionCode() + " current="
                    + certificates(source.signingInfo.getApkContentsSigners()) + " history=" + certificates(source.signingInfo.getSigningCertificateHistory()));
        } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
            System.out.println("MYLITE_STORE_SOURCE physicalPackageAbsent=true bundledSourceRequired=true");
        }
        InstalledPackage microg = BundledMicrog.install(), store = GoogleComponents.installStore();
        assertEquals("com.android.vending", store.packageName);
        assertEquals("Retry replaced the existing Store generation", store.baseApk, GoogleComponents.installStore().baseApk);
        UiAutomation automation = getInstrumentation().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
        int originalFlags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        android.content.ClipboardManager clipboard = context.getSystemService(android.content.ClipboardManager.class);
        GoogleRuntimeTest.ActivityInspector inspector = null;
        boolean loginReached = false;
        Throwable primary = null;
        try {
            inspector = new GoogleRuntimeTest.ActivityInspector();
            Engine.get().launch(store.packageName);
            try { inspector.awaitResumed(null, store); }
            catch (AssertionError original) {
                try { inspector.diagnoseActivityWait(store); }
                catch (Throwable diagnostic) { original.addSuppressed(diagnostic); }
                throw original;
            }
            touch(automation, context, false);
            Bundle login = inspector.awaitResumed(LOGIN, microg);
            loginReached = true;
            AccessibilityNodeInfo intro = awaitControl(automation, context, false, "next_button");
            try {
                Rect bounds = new Rect(); intro.getBoundsInScreen(bounds);
                WebInputRuntimeTest.tap(automation, bounds.centerX(), bounds.centerY(), 50);
            } finally { intro.recycle(); }
            AccessibilityNodeInfo editor = awaitControl(automation, context, true);
            Rect editorBounds = new Rect();
            try {
                editor.getBoundsInScreen(editorBounds);
                WebInputRuntimeTest.tap(automation, editorBounds.centerX(), editorBounds.centerY(), 50);
            } finally { editor.recycle(); }
            awaitKeyboard(automation, context, true);
            retain(automation, context, "google-editor-keyboard");
            String synthetic = "mylite-" + java.util.UUID.randomUUID() + "@example.invalid";
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("mylite-online-input", synthetic));
            WebInputRuntimeTest.syncInput(automation);
            Rect beforeIme = new Rect(editorBounds);
            editor = awaitControl(automation, context, true);
            try { editorBounds = WebInputRuntimeTest.editorTouchBounds(automation, editor); }
            finally { editor.recycle(); }
            System.out.println("MYLITE_GOOGLE_EDITOR_TOUCH beforeIme=" + beforeIme + " currentUnobscured=" + editorBounds);
            WebInputRuntimeTest.tap(automation, editorBounds.centerX(), editorBounds.centerY(), android.view.ViewConfiguration.getLongPressTimeout() + 250);
            WebInputRuntimeTest.syncInput(automation);
            AccessibilityNodeInfo paste = WebInputRuntimeTest.awaitNode(automation, false);
            try {
                Rect bounds = new Rect(); paste.getBoundsInScreen(bounds); assertFalse("Actual Paste control has no touch target", bounds.isEmpty());
                System.out.println("MYLITE_GOOGLE_PASTE_TARGET bounds=" + bounds + " enabled=" + paste.isEnabled()
                        + " clickable=" + paste.isClickable() + " focused=" + paste.isFocused());
                retain(automation, context, "google-editor-paste-menu");
                WebInputRuntimeTest.retainInputRouting(automation, context, "play-journey-paste-menu");
                AccessibilityNodeInfo parent = paste.getParent();
                try {
                    if (parent != null) {
                        Rect parentBounds = new Rect(); parent.getBoundsInScreen(parentBounds);
                        System.out.println("MYLITE_PASTE_PARENT bounds=" + parentBounds + " enabled=" + parent.isEnabled()
                                + " clickable=" + parent.isClickable() + " class=" + parent.getClassName());
                    }
                } finally { if (parent != null) parent.recycle(); }
                WebInputRuntimeTest.touchPaste(automation, paste);
            } finally { paste.recycle(); }
            awaitSyntheticText(automation, context, synthetic);
            assertEquals("Pasting killed or replaced the login Activity", login.getInt("pid"), inspector.awaitResumed(LOGIN, microg).getInt("pid"));
            retain(automation, context, "google-editor-pasted");
            swipeBack(automation, context);
            awaitKeyboard(automation, context, false);
            assertEquals("Closing the keyboard killed the login Activity", login.getInt("pid"), inspector.awaitResumed(LOGIN, microg).getInt("pid"));
            swipeBack(automation, context);
            inspector.awaitGone(login.getLong("record_id"));
            inspector.awaitResumed(null, store);
            awaitControl(automation, context, false).recycle();
            retain(automation, context, "store-returned");
            System.out.println("MYLITE_REAL_PLAY_JOURNEY storeImported=true storeClicked=true googleEditor=true keyboard=true longPressPaste=true syntheticInput=true gestureImeDismiss=true gestureAccountCancel=true storeReturned=true credentialsEntered=false authenticationVerified=false downloadVerified=false");
        } catch (Exception | AssertionError failure) {
            primary = failure;
            try { retain(automation, context, "failure"); } catch (Throwable diagnostic) { failure.addSuppressed(diagnostic); }
            try { WebInputRuntimeTest.retainInputRouting(automation, context, "play-journey-failure"); }
            catch (Throwable diagnostic) { failure.addSuppressed(diagnostic); }
            if (loginReached && inspector != null) try { inspector.diagnoseActivityWait(microg); } catch (Throwable diagnostic) { failure.addSuppressed(diagnostic); }
            throw failure;
        } finally {
            RuntimeException cleanup = null;
            try { clipboard.clearPrimaryClip(); } catch (RuntimeException failure) { cleanup = failure; }
            try { info = automation.getServiceInfo(); info.flags = originalFlags; automation.setServiceInfo(info); }
            catch (RuntimeException failure) { if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure); }
            if (cleanup != null) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
        }
    }
    private static void awaitSyntheticText(UiAutomation automation, Context context, String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot(); window.recycle(); if (root == null) continue;
                AccessibilityNodeInfo editor;
                try { editor = find(root, context.getPackageName(), true, null); } finally { root.recycle(); }
                if (editor != null) try { if (expected.contentEquals(editor.getText() == null ? "" : editor.getText())) return; }
                finally { editor.recycle(); }
            }
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        retain(automation, context, "failure"); throw new AssertionError("The actual Google editor did not contain the synthetic pasted value");
    }
    private static void touch(UiAutomation automation, Context context, boolean editor) throws Exception {
        AccessibilityNodeInfo node = awaitControl(automation, context, editor);
        try { Rect bounds = new Rect(); node.getBoundsInScreen(bounds); assertFalse(bounds.isEmpty()); WebInputRuntimeTest.tap(automation, bounds.centerX(), bounds.centerY(), 50); }
        finally { node.recycle(); }
    }
    private static AccessibilityNodeInfo awaitControl(UiAutomation automation, Context context, boolean editor) throws Exception {
        return awaitControl(automation, context, editor, null);
    }
    private static AccessibilityNodeInfo awaitControl(UiAutomation automation, Context context, boolean editor, String requiredId) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 60000;
        do {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                try { AccessibilityNodeInfo found = find(root, context.getPackageName(), editor, requiredId); if (found != null) return found; }
                finally { root.recycle(); }
            }
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        retain(automation, context, "failure");
        throw new AssertionError(editor ? "The real Google editor did not appear; UI retained without account entry" : "The real Sign in control did not appear");
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String host, boolean editor, String requiredId) {
        CharSequence owner = node.getPackageName();
        boolean allowed = owner != null && (host.contentEquals(owner) || GMS.contentEquals(owner) || !editor && "com.android.vending".contentEquals(owner));
        boolean email = node.isEditable() && ("Email or phone".contentEquals(node.getHintText() == null ? "" : node.getHintText())
                || "Email or phone".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())
                || "Email or phone".contentEquals(node.getText() == null ? "" : node.getText())
                || "电子邮件地址或电话号码".contentEquals(node.getHintText() == null ? "" : node.getHintText())
                || "电子邮件地址或手机号".contentEquals(node.getHintText() == null ? "" : node.getHintText())
                || "identifierId".equals(node.getViewIdResourceName()) || node.getViewIdResourceName() != null && node.getViewIdResourceName().endsWith("/identifierId"));
        boolean signIn = node.getText() != null && ("Sign in".equalsIgnoreCase(node.getText().toString()) || "登录".contentEquals(node.getText()) || "登入".contentEquals(node.getText()));
        boolean correctControl = requiredId == null || node.getViewIdResourceName() != null && node.getViewIdResourceName().endsWith("/" + requiredId);
        if (allowed && correctControl && node.isVisibleToUser() && node.isEnabled() && (editor ? email : signIn)) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { AccessibilityNodeInfo found = find(child, host, editor, requiredId); if (found != null) return found; } finally { child.recycle(); }
        }
        return null;
    }
    private static void awaitKeyboard(UiAutomation automation, Context context, boolean expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            boolean shown = false;
            for (AccessibilityWindowInfo window : automation.getWindows()) { shown |= window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD; window.recycle(); }
            if (shown == expected) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        retain(automation, context, "failure");
        throw new AssertionError("Real IME visibility did not become " + expected);
    }
    private static void swipeBack(UiAutomation automation, Context context) throws Exception {
        android.graphics.Bitmap display = automation.takeScreenshot(); assertNotNull("Cannot measure physical gesture coordinates", display);
        int width = display.getWidth(), height = display.getHeight(); display.recycle();
        // Y700's left edge has an OEM sidebar; exercise the actual right-edge system back gesture.
        long down = SystemClock.uptimeMillis();
        for (int step = 0; step <= 10; step++) {
            int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, width - 2 - width * 0.4f * step / 10, height * 0.25f, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { assertTrue("Real edge gesture injection failed", automation.injectInputEvent(event, true)); } finally { event.recycle(); }
            Thread.sleep(30);
        }
    }
    private static void retain(UiAutomation automation, Context context, String phase) throws Exception {
        android.graphics.Bitmap bitmap = automation.takeScreenshot(); assertNotNull(bitmap);
        try (java.io.FileOutputStream output = context.openFileOutput("play-journey-" + phase + ".png", Context.MODE_PRIVATE)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
    private static String certificates(android.content.pm.Signature[] signatures) throws Exception {
        java.util.List<String> hashes = new java.util.ArrayList<>();
        if (signatures != null) for (android.content.pm.Signature signature : signatures) {
            StringBuilder hash = new StringBuilder();
            for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            hashes.add(hash.toString());
        }
        return hashes.toString();
    }
}
