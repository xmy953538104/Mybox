package io.github.xmy953538104.mybox.engine;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static junit.framework.Assert.*;

/** Actual launcher gestures, without adopting shortcut permissions or starting the target ourselves. */
final class ShortcutLauncherProbe {
    static String open(Instrumentation instrumentation, Context host) throws Exception {
        UiAutomation ui = instrumentation.getUiAutomation();
        ResolveInfo home = host.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        assertNotNull("No real launcher", home); assertNotNull(home.activityInfo);
        String launcher = home.activityInfo.packageName;
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME);
        long until = SystemClock.elapsedRealtime() + 10_000; boolean foreground = false;
        while (SystemClock.elapsedRealtime() < until) {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null) try { foreground = launcher.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()); }
            finally { root.recycle(); }
            if (foreground) break; Thread.sleep(40);
        }
        assertTrue("System launcher did not become foreground", foreground);
        WebInputRuntimeTest.syncInput(ui);
        if (visibleControl(ui, "Mybox") == null) {
            Bitmap display = ui.takeScreenshot(); assertNotNull(display);
            int width = display.getWidth(), height = display.getHeight(); display.recycle();
            long down = SystemClock.uptimeMillis();
            for (int step = 0; step <= 12; step++) {
                MotionEvent event = WebInputRuntimeTest.fingerEvent(down, SystemClock.uptimeMillis(), step == 0 ? MotionEvent.ACTION_DOWN
                        : step == 12 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, width * .5f, height * (.8f - .55f * step / 12));
                try { assertTrue(ui.injectInputEvent(event, true)); } finally { event.recycle(); }
                if (step < 12) Thread.sleep(25);
            }
        }
        await(ui, "Mybox"); retain(ui, host, "launcher");
        Rect icon = await(ui, "Mybox");
        WebInputRuntimeTest.tap(ui, icon.centerX(), icon.centerY(), ViewConfiguration.getLongPressTimeout() + 150);
        await(ui, "Updated shortcut"); retain(ui, host, "popup");
        Rect shortcut = await(ui, "Updated shortcut");
        WebInputRuntimeTest.tap(ui, shortcut.centerX(), shortcut.centerY(), 50);
        return launcher;
    }

    static void focused(UiAutomation ui, Context host) throws Exception {
        await(ui, "Activated fixture shortcut"); WebInputRuntimeTest.syncInput(ui); retain(ui, host, "focused");
    }

    private static Rect await(UiAutomation ui, String label) throws Exception {
        long until = SystemClock.elapsedRealtime() + 10_000; Rect found = null;
        while (SystemClock.elapsedRealtime() < until) {
            found = visibleControl(ui, label); if (found != null) break; Thread.sleep(40);
        }
        assertNotNull("Visible launcher control was absent: " + label, found); return found;
    }
    private static Rect visibleControl(UiAutomation ui, String label) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow(); if (root == null) return null;
        List<Rect> matches = new ArrayList<>();
        try { find(root, label, matches, new int[]{512}, 0); } finally { root.recycle(); }
        // Prediction and alphabetical rows may both show this publisher; the popup and fresh target nonce verify the selected app.
        if (!"Mybox".equals(label)) assertTrue("Ambiguous visible launcher control: " + label, matches.size() <= 1);
        return matches.isEmpty() ? null : matches.get(0);
    }
    private static void find(AccessibilityNodeInfo node, String label, List<Rect> matches, int[] budget, int depth) {
        if (depth > 30 || budget[0]-- <= 0) return;
        if (node.isVisibleToUser() && (label.contentEquals(node.getText() == null ? "" : node.getText())
                || label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && !matches.contains(bounds)) matches.add(bounds);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { find(child, label, matches, budget, depth + 1); } finally { child.recycle(); }
        }
    }

    static void retain(UiAutomation ui, Context host, String phase) throws Exception {
        Bitmap display = ui.takeScreenshot(); assertNotNull("Cannot capture actual shortcut UI", display);
        try (FileOutputStream output = host.openFileOutput("shortcut-ui-" + phase + ".png", Context.MODE_PRIVATE)) {
            assertTrue(display.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { display.recycle(); }
        StringBuilder out = new StringBuilder(); AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root != null) try { structure(root, out, new int[]{512}, 0); } finally { root.recycle(); }
        Files.write(new File(host.getFilesDir(), "shortcut-ui-" + phase + ".txt").toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void structure(AccessibilityNodeInfo node, StringBuilder out, int[] budget, int depth) {
        if (depth > 30 || budget[0]-- <= 0) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds); String label = "redacted";
        for (String safe : new String[]{"Mybox", "Updated shortcut", "Activated fixture shortcut", "Host sentinel"})
            if (safe.contentEquals(node.getText() == null ? "" : node.getText())
                    || safe.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) label = safe;
        out.append(depth).append(' ').append(node.getClassName()).append(' ').append(bounds).append(" visible=")
                .append(node.isVisibleToUser()).append(" text=").append(label).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { structure(child, out, budget, depth + 1); } finally { child.recycle(); }
        }
    }
    private ShortcutLauncherProbe() { }
}
