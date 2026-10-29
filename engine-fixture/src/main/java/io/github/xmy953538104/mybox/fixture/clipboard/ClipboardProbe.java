package io.github.xmy953538104.mybox.fixture.clipboard;

import android.app.Activity;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
            new Check(activity, run, sentinel, input.optBoolean("recovery", false)).start();
        } catch (Exception failure) {
            throw new IllegalStateException("Clipboard fixture setup failed", failure);
        }
    }

    private static final class Check implements Runnable {
        final Activity activity;
        final String run;
        final String sentinel;
        final boolean recovery;
        final Handler handler;
        final ClipboardManager manager;
        final JSONObject result = new JSONObject();
        final long deadline = SystemClock.elapsedRealtime() + 25_000;
        int phase;
        final AtomicInteger firstCalls = new AtomicInteger(), secondCalls = new AtomicInteger();
        final AtomicBoolean wrongCallbackThread = new AtomicBoolean();
        final ClipboardManager.OnPrimaryClipChangedListener first = () -> record(firstCalls);
        final ClipboardManager.OnPrimaryClipChangedListener second = () -> record(secondCalls);
        Object bridge;
        int removedFirst, secondBaseline;
        long backgroundReceived, backgroundAccepted, backgroundFiltered;
        volatile boolean stopped;
        Thread removal;

        Check(Activity activity, String run, String sentinel, boolean recovery) throws Exception {
            this.activity = activity; this.run = run; this.sentinel = sentinel; this.recovery = recovery;
            handler = new Handler(activity.getMainLooper());
            manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            result.put("run", run);
        }

        void start() { handler.post(this); }

        @Override public void run() {
            try {
                if (signal("abort").isFile()) { finish(); activity.finish(); return; }
                if (SystemClock.elapsedRealtime() >= deadline) throw new java.util.concurrent.TimeoutException("Clipboard phase did not complete");
                if (phase == 0) {
                    if (!activity.hasWindowFocus()) { handler.postDelayed(this, 20); return; }
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
                    manager.addPrimaryClipChangedListener(first);
                    manager.addPrimaryClipChangedListener(second);
                    bridge = onlyBridge(manager);
                    verifyListenerBoundary(service, bridge);
                    result.put("pid", android.os.Process.myPid());
                    phase = 1;
                    publish(activity, "clipboard-foreground-" + run + ".json", result);
                } else if (phase == 1 && firstCalls.get() > 0 && secondCalls.get() > 0) {
                    result.put("realListenerCallbacks", received(bridge) > 0 && accepted(bridge) > 0 && !wrongCallbackThread.get());
                    if (recovery) {
                        finish(); publish(activity, "clipboard-result-" + run + ".json", result);
                        activity.finish(); return;
                    }
                    manager.removePrimaryClipChangedListener(first);
                    removedFirst = firstCalls.get(); secondBaseline = secondCalls.get();
                    phase = 2;
                    publish(activity, "clipboard-one-removed-" + run + ".json", result);
                } else if (phase == 2 && secondCalls.get() > secondBaseline) {
                    result.put("removedListenerSilent", firstCalls.get() == removedFirst);
                    result.put("remainingListenerWorks", true);
                    phase = 3;
                    publish(activity, "clipboard-remaining-" + run + ".json", result);
                } else if (phase == 3 && signal("block-main").isFile()) {
                    queuedRemoval();
                    manager.addPrimaryClipChangedListener(second);
                    Object next = onlyBridge(manager);
                    result.put("newRegistrationBinder", next != bridge);
                    bridge = next;
                    phase = 4;
                    publish(activity, "clipboard-reregistered-" + run + ".json", result);
                } else if (phase == 4 && signal("background").isFile() && !activity.hasWindowFocus()) {
                    result.put("backgroundUnfocused", true);
                    result.put("backgroundDenied", manager.getPrimaryClipDescription() == null);
                    result.put("backgroundReadDenied", !manager.hasPrimaryClip() && manager.getPrimaryClip() == null);
                    boolean writeDenied = false;
                    try { manager.setPrimaryClip(android.content.ClipData.newPlainText("must-not-write", "denied")); }
                    catch (SecurityException expected) { writeDenied = true; }
                    result.put("backgroundWriteDenied", writeDenied);
                    backgroundReceived = received(bridge); backgroundAccepted = accepted(bridge); backgroundFiltered = filtered(bridge);
                    phase = 5;
                    publish(activity, "clipboard-background-ready-" + run + ".json", result);
                } else if (phase == 5 && received(bridge) > backgroundReceived && filtered(bridge) > backgroundFiltered) {
                    // This proves a real OS event reached this process; it was not a local synthetic callback.
                    result.put("backgroundRealOsReceipt", true);
                    result.put("backgroundNotAccepted", accepted(bridge) == backgroundAccepted);
                    result.put("registeredUntilDeath", ((AtomicBoolean) field(bridge.getClass(), "active").get(bridge)).get());
                    phase = 6;
                    publish(activity, "clipboard-result-" + run + ".json", result);
                }
                handler.postDelayed(this, 20);
            } catch (Throwable failure) {
                try { finish(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                try {
                    result.put("failure", failure.getClass().getSimpleName());
                    publish(activity, "clipboard-result-" + run + ".json", result);
                } catch (Exception publication) { throw new IllegalStateException("Cannot publish clipboard fixture failure", publication); }
                activity.finish();
            }
        }

        void record(AtomicInteger count) {
            if (android.os.Looper.myLooper() != activity.getMainLooper()) wrongCallbackThread.set(true);
            count.incrementAndGet();
        }

        File signal(String phase) { return new File(activity.getFilesDir(), "clipboard-" + phase + "-" + run); }

        void finish() {
            stopped = true;
            if (removal != null) removal.interrupt();
            try { manager.removePrimaryClipChangedListener(first); }
            finally { manager.removePrimaryClipChangedListener(second); }
        }

        void queuedRemoval() throws Exception {
            final Object old = bridge;
            final long beforeReceived = received(old), beforeAccepted = accepted(old);
            final int beforeCalls = secondCalls.get();
            final long until = Math.min(deadline, SystemClock.elapsedRealtime() + 2_500);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            removal = new Thread(() -> {
                try {
                    while (!stopped && received(old) == beforeReceived && SystemClock.elapsedRealtime() < until) Thread.sleep(5);
                    if (stopped || received(old) == beforeReceived) throw new AssertionError("No actual OS callback reached the blocked main thread");
                    manager.removePrimaryClipChangedListener(second);
                    if (((AtomicBoolean) field(old.getClass(), "active").get(old)).get()) throw new AssertionError("Removed bridge still active");
                    publish(activity, "clipboard-queue-removed-" + run + ".json", new JSONObject().put("run", run));
                } catch (Throwable thrown) { failure.set(thrown); }
            }, "clipboard-removal-fixture");
            removal.start();
            publish(activity, "clipboard-queue-ready-" + run + ".json", new JSONObject().put("run", run));
            // A deliberately bounded main-thread barrier; only a real OS receipt releases the removal worker.
            while (!signal("release-main").isFile() && failure.get() == null && SystemClock.elapsedRealtime() < until) Thread.sleep(5);
            if (failure.get() != null) throw new AssertionError("Clipboard removal worker failed", failure.get());
            if (!signal("release-main").isFile()) throw new AssertionError("Clipboard main barrier was not released");
            removal.join(Math.max(1, until - SystemClock.elapsedRealtime()));
            if (removal.isAlive()) throw new AssertionError("Clipboard removal did not finish");
            result.put("queuedEventInvalidated", received(old) > beforeReceived && accepted(old) == beforeAccepted && secondCalls.get() == beforeCalls);
        }

        void verifyListenerBoundary(Object service, Object entry) throws Exception {
            Class<?> listener = Class.forName("android.content.IOnPrimaryClipChangedListener");
            Class<?>[] layout = Build.VERSION.SDK_INT >= 34 ? new Class<?>[]{listener, String.class, String.class, int.class, int.class}
                    : Build.VERSION.SDK_INT >= 29 ? new Class<?>[]{listener, String.class, int.class} : new Class<?>[]{listener, String.class};
            Method add = Class.forName("android.content.IClipboard").getMethod("addPrimaryClipChangedListener", layout);
            Object target = field(entry.getClass(), "target").get(entry);
            Object[] args = Build.VERSION.SDK_INT >= 34 ? new Object[]{target, activity.getPackageName(), null, 0, activity.getDeviceId()}
                    : Build.VERSION.SDK_INT >= 29 ? new Object[]{target, activity.getPackageName(), 0} : new Object[]{target, activity.getPackageName()};
            Object[] foreign = args.clone(); foreign[1] = "io.github.xmy953538104.mybox.unknown";
            result.put("foreignListenerDenied", denied(service, add, foreign, SecurityException.class));
            if (Build.VERSION.SDK_INT >= 29) {
                Object[] user = args.clone(); user[Build.VERSION.SDK_INT >= 34 ? 3 : 2] = 1;
                result.put("listenerUserDenied", denied(service, add, user, SecurityException.class));
            }
            if (Build.VERSION.SDK_INT >= 34) {
                Object[] tag = args.clone(); tag[2] = "unmapped";
                result.put("listenerTagDenied", denied(service, add, tag, UnsupportedOperationException.class));
                Object[] device = args.clone(); device[4] = ((Integer) args[4]) + 1;
                result.put("listenerDeviceDenied", denied(service, add, device, SecurityException.class));
            }
            long before = received(entry);
            int code = field(Class.forName("android.content.IOnPrimaryClipChangedListener$Stub"), "TRANSACTION_dispatchPrimaryClipChanged").getInt(null);
            boolean registeredDenied = rejectsForgedCallback(entry, code);
            result.put("registeredForgedCallbackRejected", registeredDenied);
            result.put("registeredSystemReceiptsDuringForgery", received(entry) - before);
            // A registered bridge can concurrently receive genuine OS classification callbacks.
            Object adapter = Proxy.getInvocationHandler(service);
            java.lang.reflect.Constructor<?> constructor = entry.getClass().getDeclaredConstructor(adapter.getClass(), Object.class, Class.class);
            constructor.setAccessible(true);
            Object isolated = constructor.newInstance(adapter, target, listener);
            boolean isolatedDenied;
            try {
                if (received(isolated) != 0) throw new AssertionError("Unregistered clipboard bridge already received an event");
                isolatedDenied = rejectsForgedCallback(isolated, code);
                result.put("isolatedForgedCallbackRejected", isolatedDenied);
                result.put("isolatedForgedReceiptCount", received(isolated));
                result.put("forgedCallbackDenied", registeredDenied && isolatedDenied && received(isolated) == 0);
            } finally { ((AtomicBoolean) field(isolated.getClass(), "active").get(isolated)).set(false); }
            Parcel data = Parcel.obtain(); boolean denied = false;
            try {
                data.writeInterfaceToken("android.content.IClipboard");
                int rawCode = field(Class.forName("android.content.IClipboard$Stub"), "TRANSACTION_addPrimaryClipChangedListener").getInt(null);
                ((IInterface) service).asBinder().transact(rawCode, data, null, 0);
            } catch (UnsupportedOperationException expected) { denied = true; }
            finally { data.recycle(); }
            result.put("rawListenerUnsupported", denied);
        }

        boolean rejectsForgedCallback(Object entry, int code) throws Exception {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken("android.content.IOnPrimaryClipChangedListener");
                ((IBinder) entry).transact(code, data, null, IBinder.FLAG_ONEWAY);
                return false;
            } catch (SecurityException expected) { return true; }
            finally { data.recycle(); }
        }
    }

    private static Object onlyBridge(ClipboardManager manager) throws Exception {
        Object service = field(ClipboardManager.class, "mService").get(manager);
        Object adapter = Proxy.getInvocationHandler(service);
        Map<?, ?> entries = (Map<?, ?>) field(adapter.getClass(), "listeners").get(adapter);
        synchronized (entries) {
            if (entries.size() != 1) throw new AssertionError("Expected one actual framework listener registration");
            return entries.values().iterator().next();
        }
    }
    private static long received(Object entry) throws Exception { return ((AtomicLong) field(entry.getClass(), "received").get(entry)).get(); }
    private static long accepted(Object entry) throws Exception { return ((AtomicLong) field(entry.getClass(), "accepted").get(entry)).get(); }
    private static long filtered(Object entry) throws Exception { return ((AtomicLong) field(entry.getClass(), "filtered").get(entry)).get(); }

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
