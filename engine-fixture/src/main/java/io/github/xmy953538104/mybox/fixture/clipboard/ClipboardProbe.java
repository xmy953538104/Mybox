package io.github.xmy953538104.mybox.fixture.clipboard;

import android.app.Activity;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** Uses only a test-created sentinel; reports booleans without saving clipboard contents. */
public final class ClipboardProbe {
    public static void run(Activity activity) {
        try {
            File request = new File(activity.getFilesDir(), "clipboard-request.json");
            JSONObject input = new JSONObject(new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8));
            if (!request.delete()) throw new java.io.IOException("Cannot consume clipboard request");
            String run = input.getString("run");
            String sentinel = input.getString("sentinel");
            new Check(activity, run, sentinel).start();
        } catch (Exception failure) {
            throw new IllegalStateException("Clipboard fixture setup failed", failure);
        }
    }

    private static final class Check implements Runnable {
        final Activity activity;
        final String run;
        final String sentinel;
        final Handler handler;
        final ClipboardManager manager;
        final JSONObject result = new JSONObject();
        final long deadline = SystemClock.elapsedRealtime() + 25_000;
        boolean foreground;

        Check(Activity activity, String run, String sentinel) throws Exception {
            this.activity = activity; this.run = run; this.sentinel = sentinel;
            handler = new Handler(activity.getMainLooper());
            manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            result.put("run", run);
        }

        void start() { handler.post(this); }

        @Override public void run() {
            try {
                if (SystemClock.elapsedRealtime() >= deadline) throw new java.util.concurrent.TimeoutException("Clipboard phase did not complete");
                if (!foreground) {
                    if (!activity.hasWindowFocus()) { handler.postDelayed(this, 20); return; }
                    // Listener registration is intentionally outside the newly adapted query.
                    ClipboardManager.OnPrimaryClipChangedListener listener = () -> { };
                    manager.addPrimaryClipChangedListener(listener);
                    manager.removePrimaryClipChangedListener(listener);
                    result.put("listenerRoundTrip", true);
                    ClipDescription description = manager.getPrimaryClipDescription();
                    result.put("realSentinel", description != null && sentinel.contentEquals(description.getLabel())
                            && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN));
                    result.put("hasRealClip", manager.hasPrimaryClip());
                    android.content.ClipData clip = manager.getPrimaryClip();
                    result.put("readRealClip", clip != null && clip.getItemCount() == 1
                            && "CI synthetic clipboard fixture".contentEquals(clip.getItemAt(0).getText()));
                    manager.setPrimaryClip(android.content.ClipData.newPlainText(sentinel, "CI synthetic clipboard fixture"));
                    result.put("writeRealClip", manager.hasPrimaryClip());
                    result.put("foregroundFocused", activity.hasWindowFocus());
                    Object service = field(ClipboardManager.class, "mService").get(manager);
                    Class<?> contract = Class.forName("android.content.IClipboard");
                    Class<?>[] signature = Build.VERSION.SDK_INT >= 34
                            ? new Class<?>[]{String.class, String.class, int.class, int.class}
                            : Build.VERSION.SDK_INT >= 29 ? new Class<?>[]{String.class, int.class} : new Class<?>[]{String.class};
                    Method query = contract.getMethod("getPrimaryClipDescription", signature);
                    Object[] args = arguments(activity);
                    Object[] foreign = args.clone(); foreign[0] = "io.github.xmy953538104.mybox.unknown";
                    result.put("foreignPackageDenied", denied(service, query, foreign, SecurityException.class));
                    if (Build.VERSION.SDK_INT >= 29) {
                        Object[] user = args.clone(); user[Build.VERSION.SDK_INT >= 34 ? 2 : 1] = 1;
                        result.put("otherUserDenied", denied(service, query, user, SecurityException.class));
                    }
                    if (Build.VERSION.SDK_INT >= 34) {
                        Object[] device = args.clone(); device[3] = ((Integer) args[3]) + 1;
                        result.put("otherDeviceDenied", denied(service, query, device, SecurityException.class));
                        Object[] tag = args.clone(); tag[1] = "unmapped-fixture-attribution";
                        result.put("unmappedAttributionDenied", denied(service, query, tag, UnsupportedOperationException.class));
                    }
                    foreground = true;
                    publish(activity, "clipboard-foreground-" + run + ".json", result);
                }
                File signal = new File(activity.getFilesDir(), "clipboard-background-" + run);
                if (!signal.isFile() || activity.hasWindowFocus()) { handler.postDelayed(this, 20); return; }
                result.put("backgroundUnfocused", !activity.hasWindowFocus());
                result.put("backgroundDenied", manager.getPrimaryClipDescription() == null);
                result.put("backgroundReadDenied", !manager.hasPrimaryClip() && manager.getPrimaryClip() == null);
                boolean writeDenied = false;
                try { manager.setPrimaryClip(android.content.ClipData.newPlainText("must-not-write", "denied")); }
                catch (SecurityException expected) { writeDenied = true; }
                result.put("backgroundWriteDenied", writeDenied);
                publish(activity, "clipboard-result-" + run + ".json", result);
                activity.finish();
            } catch (Throwable failure) {
                try {
                    result.put("failure", failure.getClass().getSimpleName());
                    publish(activity, "clipboard-result-" + run + ".json", result);
                } catch (Exception publication) { throw new IllegalStateException("Cannot publish clipboard fixture failure", publication); }
                activity.finish();
            }
        }
    }

    private static Object[] arguments(Activity activity) {
        if (Build.VERSION.SDK_INT >= 34) return new Object[]{activity.getPackageName(), null, 0, activity.getDeviceId()};
        if (Build.VERSION.SDK_INT >= 29) return new Object[]{activity.getPackageName(), 0};
        return new Object[]{activity.getPackageName()};
    }

    private static boolean denied(Object service, Method method, Object[] args, Class<? extends Throwable> expected) throws Exception {
        try { method.invoke(service, args); return false; }
        catch (InvocationTargetException failure) {
            if (expected.isInstance(failure.getCause())) return true;
            throw failure;
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }

    private static void publish(Activity activity, String name, JSONObject value) throws Exception {
        File target = new File(activity.getFilesDir(), name);
        File pending = new File(activity.getFilesDir(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(value.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private ClipboardProbe() { }
}
