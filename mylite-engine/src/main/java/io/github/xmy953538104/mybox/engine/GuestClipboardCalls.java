package io.github.xmy953538104.mybox.engine;

import android.content.ClipDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Clipboard access retains the actual OS checks and requires this guest's own foreground focus. */
final class GuestClipboardCalls implements InvocationHandler {
    private static final String LISTENER = "android.content.IOnPrimaryClipChangedListener";
    private static volatile GuestClipboardCalls installed;
    private final AtomicLongArray reads = new AtomicLongArray(20);
    private final AtomicLongArray readBoundaries = new AtomicLongArray(32);
    private final Object original;
    private final String guestPackage;
    private final String hostPackage;
    private final String hostAttribution;
    private final int hostUser;
    private final int hostDevice;
    private final int sdk;
    private final BooleanSupplier focused;
    private final Runnable requireLocalCaller;
    private final Map<IBinder, Listener> listeners = new HashMap<>();
    private IBinder facade;
    private Handler listenerHandler;

    GuestClipboardCalls(Object original, String guestPackage, String hostPackage, String hostAttribution,
            int hostUser, int hostDevice, int sdk, BooleanSupplier focused, Runnable requireLocalCaller) {
        this.original = original;
        this.guestPackage = guestPackage;
        this.hostPackage = hostPackage;
        this.hostAttribution = hostAttribution;
        this.hostUser = hostUser;
        this.hostDevice = hostDevice;
        this.sdk = sdk;
        this.focused = focused;
        this.requireLocalCaller = requireLocalCaller;
    }

    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        Context host = engine.hostContext();
        int uid = Process.myUid();
        int user = (Integer) RuntimeReflection.invoke(null,
                RuntimeReflection.method(android.os.UserHandle.class, "getUserId", int.class), uid);
        int contextUser = (Integer) RuntimeReflection.invoke(host,
                RuntimeReflection.method(Context.class, "getUserId"));
        if (host.getApplicationInfo().uid != uid || contextUser != user) {
            throw new SecurityException("Clipboard host context does not belong to this process user");
        }
        int device = Build.VERSION.SDK_INT >= 34 ? host.getDeviceId() : 0;
        String attribution = Build.VERSION.SDK_INT >= 30 ? host.getAttributionTag() : null;
        ClipboardManager current = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
        Object original = RuntimeReflection.get(current, "mService");
        Class<?> contract = Class.forName("android.content.IClipboard");
        GuestClipboardCalls calls = new GuestClipboardCalls(original, runtime.packageInfo().packageName,
                host.getOpPackageName(), attribution, user, device, Build.VERSION.SDK_INT,
                runtime::hasFocusedGuestActivity, () -> {
                    if (Binder.getCallingUid() != Process.myUid() || Binder.getCallingPid() != Process.myPid()) {
                        throw new SecurityException("Clipboard metadata requires a local guest call");
                    }
                });
        calls.requireSignature(contract.getMethod("getPrimaryClipDescription", signature(Build.VERSION.SDK_INT)));
        Class<?> listener = Class.forName(LISTENER);
        calls.requireListenerSignature(contract.getMethod("addPrimaryClipChangedListener", listenerSignature(Build.VERSION.SDK_INT, false, listener)));
        calls.requireListenerSignature(contract.getMethod("removePrimaryClipChangedListener", listenerSignature(Build.VERSION.SDK_INT, true, listener)));
        Class<?> stub = Class.forName("android.content.IClipboard$Stub");
        int addListener = RuntimeReflection.field(stub, "TRANSACTION_addPrimaryClipChangedListener").getInt(null);
        int removeListener = RuntimeReflection.field(stub, "TRANSACTION_removePrimaryClipChangedListener").getInt(null);
        Map<Integer, Integer> rawReads = new HashMap<>();
        for (String name : new String[]{"getPrimaryClip", "getPrimaryClipDescription", "hasPrimaryClip", "hasClipboardText"}) {
            rawReads.put(RuntimeReflection.field(stub, "TRANSACTION_" + name).getInt(null), readOffset(name) / 5 * 8 + 7);
        }
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        IBinder physical = ((IInterface) original).asBinder();
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == addListener || code == removeListener) throw new UnsupportedOperationException("Raw clipboard listener transactions are not adapted");
                Integer read = rawReads.get(code);
                if (read != null) calls.readBoundaries.incrementAndGet(read);
                // Other raw calls retain the prior OS checks and arguments.
                return physical.transact(code, data, reply, flags);
            }
        };
        local.attachInterface(proxy, "android.content.IClipboard");
        calls.facade = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(
                Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put(Context.CLIPBOARD_SERVICE, local);
        RuntimeReflection.field(ClipboardManager.class, "mService").set(current, proxy);
        if (cache.get(Context.CLIPBOARD_SERVICE) != local || RuntimeReflection.get(current, "mService") != proxy) {
            throw new EngineException("Clipboard metadata boundary was not installed");
        }
        installed = calls;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object[] forwarded = args;
        String name = method.getName();
        if (name.equals("asBinder") && facade != null) return facade;
        if (name.equals("addPrimaryClipChangedListener") || name.equals("removePrimaryClipChangedListener")) {
            return listener(method, args);
        }
        boolean read = name.equals("getPrimaryClipDescription") || name.equals("getPrimaryClip")
                || name.equals("hasPrimaryClip") || name.equals("hasClipboardText");
        boolean write = name.equals("setPrimaryClip") || name.equals("clearPrimaryClip");
        int observation = -1;
        if (read || write) {
            int boundary = read ? readOffset(name) / 5 * 8 : -1, stage = 1;
            if (read) readBoundaries.incrementAndGet(boundary);
            try {
                requireSignature(method);
                stage = 2;
                int packageIndex = name.equals("setPrimaryClip") ? 1 : 0;
                if (args == null || args.length != method.getParameterTypes().length || !guestPackage.equals(args[packageIndex])) {
                    throw new SecurityException("Clipboard metadata package is not the bound guest");
                }
                stage = 3;
                int userIndex = sdk >= 34 ? 2 : sdk >= 29 ? 1 : -1;
                if (userIndex >= 0) userIndex += packageIndex;
                if (userIndex >= 0 && !Integer.valueOf(0).equals(args[userIndex])) {
                    throw new SecurityException("Clipboard metadata supports only virtual user zero");
                }
                if (sdk >= 34) {
                    stage = 4;
                    if (args[packageIndex + 1] != null) throw new UnsupportedOperationException("Guest clipboard attribution tags are not mapped");
                    stage = 5;
                    if (!Integer.valueOf(hostDevice).equals(args[packageIndex + 3])) {
                        throw new SecurityException("Clipboard metadata device differs from the host context");
                    }
                }
                stage = 6;
                requireLocalCaller.run();
            } catch (RuntimeException failure) {
                if (read) readBoundaries.incrementAndGet(boundary + stage);
                throw failure;
            }
            if (read) { observation = readOffset(name); reads.incrementAndGet(observation); }
            // Same-UID processes share the OS focus check. An unfocused guest may not borrow that focus.
            if (!focused.getAsBoolean()) {
                if (write) throw new SecurityException("An unfocused guest cannot replace the clipboard");
                reads.incrementAndGet(observation + 1);
                return method.getReturnType() == boolean.class ? false : null;
            }
            forwarded = args.clone();
            int packageIndex = name.equals("setPrimaryClip") ? 1 : 0;
            int userIndex = sdk >= 34 ? 2 : sdk >= 29 ? 1 : -1;
            if (userIndex >= 0) userIndex += packageIndex;
            forwarded[packageIndex] = hostPackage;
            if (userIndex >= 0) forwarded[userIndex] = hostUser;
            if (sdk >= 34) forwarded[packageIndex + 1] = hostAttribution;
        }
        try {
            Object result = method.invoke(original, forwarded);
            if (observation >= 0) reads.incrementAndGet(observation + (result == null || Boolean.FALSE.equals(result) ? 2 : 3));
            return result;
        } catch (InvocationTargetException failure) {
            if (observation >= 0) reads.incrementAndGet(observation + 4);
            throw failure.getCause();
        }
    }

    private static int readOffset(String method) {
        switch (method) {
            case "getPrimaryClip": return 0;
            case "getPrimaryClipDescription": return 5;
            case "hasPrimaryClip": return 10;
            case "hasClipboardText": return 15;
            default: throw new IllegalArgumentException("Unknown clipboard read");
        }
    }
    long[] readObservations() {
        long[] result = new long[reads.length()];
        for (int i = 0; i < result.length; i++) result[i] = reads.get(i);
        return result;
    }

    long[] readBoundaryObservations() {
        long[] result = new long[readBoundaries.length()];
        for (int i = 0; i < result.length; i++) result[i] = readBoundaries.get(i);
        return result;
    }
    static void logDiagnostics() {
        GuestClipboardCalls calls = installed;
        if (calls != null) android.util.Log.i("MyliteClipboard", "Read counters [clip,description,present,text] x [attempt,focusDenied,osEmpty,osValue,osError]="
                + Arrays.toString(calls.readObservations()) + " focused=" + calls.focused.getAsBoolean()
                + " boundaries [clip,description,present,text] x [entry,signature,pkg,user,attribution,device,local,raw]="
                + Arrays.toString(calls.readBoundaryObservations()));
    }

    private Object listener(Method method, Object[] args) throws Throwable {
        requireListenerSignature(method);
        if (args == null || args.length != method.getParameterTypes().length) throw new IllegalArgumentException("Invalid clipboard listener arguments");
        requireLocalCaller.run();
        if (!(args[0] instanceof IInterface)) throw new IllegalArgumentException("Clipboard listener is missing");
        IBinder callback = ((IInterface) args[0]).asBinder();
        if (!(callback instanceof Binder) || !LISTENER.equals(callback.getInterfaceDescriptor())) {
            throw new SecurityException("Clipboard listener must belong to this guest process");
        }
        boolean remove = method.getName().equals("removePrimaryClipChangedListener");
        Object[] mapped = args.clone();
        if (mapped.length > 1) {
            if (!guestPackage.equals(mapped[1])) throw new SecurityException("Clipboard listener package is not the bound guest");
            mapped[1] = hostPackage;
            if (sdk >= 29) {
                int user = sdk >= 34 ? 3 : 2;
                if (!Integer.valueOf(0).equals(mapped[user])) throw new SecurityException("Clipboard listener supports only virtual user zero");
                mapped[user] = hostUser;
            }
            if (sdk >= 34) {
                if (mapped[2] != null) throw new UnsupportedOperationException("Guest clipboard attribution tags are not mapped");
                if (!Integer.valueOf(hostDevice).equals(mapped[4])) throw new SecurityException("Clipboard listener device differs from the host context");
                mapped[2] = hostAttribution;
            }
        }
        for (;;) {
            Listener entry; boolean created = false;
            synchronized (listeners) {
                entry = listeners.get(callback);
                if (entry == null && !remove) {
                    if (listenerHandler == null) listenerHandler = new Handler(Looper.getMainLooper());
                    entry = new Listener(args[0], method.getParameterTypes()[0]);
                    entry.operation.lock(); listeners.put(callback, entry); created = true;
                }
            }
            if (entry == null) return invokeOriginal(method, mapped);
            if (!created) entry.operation.lock();
            try {
                synchronized (listeners) { if (listeners.get(callback) != entry) continue; }
                if (!remove && !entry.active.get()) {
                    // A previous failed remove still owns this exact OS Binder; clear it before a new incarnation.
                    invokeOriginal(entry.remove, entry.removeArgs);
                    synchronized (listeners) { if (listeners.get(callback) == entry) listeners.remove(callback); }
                    continue;
                }
                Object[] physical = mapped.clone(); physical[0] = entry.proxy;
                if (remove) {
                    entry.active.set(false); listenerHandler.removeCallbacksAndMessages(entry);
                } else {
                    entry.remove = method.getDeclaringClass().getMethod("removePrimaryClipChangedListener", listenerSignature(sdk, true, method.getParameterTypes()[0]));
                    entry.removeArgs = sdk <= 28 ? new Object[]{entry.proxy} : physical.clone();
                }
                try {
                    Object result = invokeOriginal(method, physical);
                    if (remove) synchronized (listeners) { if (listeners.get(callback) == entry) listeners.remove(callback); }
                    return result;
                } catch (Throwable failure) {
                    if (created) {
                        entry.active.set(false); listenerHandler.removeCallbacksAndMessages(entry);
                        synchronized (listeners) { if (listeners.get(callback) == entry) listeners.remove(callback); }
                    }
                    throw failure;
                }
            } finally { entry.operation.unlock(); }
        }
    }

    private Object invokeOriginal(Method method, Object[] args) throws Throwable {
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private void requireListenerSignature(Method method) {
        Class<?>[] types = method.getParameterTypes();
        if (types.length == 0 || !LISTENER.equals(types[0].getName()) || !IInterface.class.isAssignableFrom(types[0])
                || method.getReturnType() != void.class || !Arrays.equals(types,
                listenerSignature(sdk, method.getName().equals("removePrimaryClipChangedListener"), types[0]))) {
            throw new UnsupportedOperationException("Unknown clipboard listener signature");
        }
    }
    private static Class<?>[] listenerSignature(int sdk, boolean remove, Class<?> listener) {
        if (sdk >= 26 && sdk <= 28) return remove ? new Class<?>[]{listener} : new Class<?>[]{listener, String.class};
        if (sdk >= 29 && sdk <= 33) return new Class<?>[]{listener, String.class, int.class};
        if (sdk >= 34 && sdk <= 35) return new Class<?>[]{listener, String.class, String.class, int.class, int.class};
        throw new UnsupportedOperationException("Clipboard listener Android version has not been verified");
    }

    private final class Listener extends Binder {
        final ReentrantLock operation = new ReentrantLock();
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicLong received = new AtomicLong(), accepted = new AtomicLong(), filtered = new AtomicLong();
        final Object target, proxy;
        final Method dispatch;
        final int transaction;
        Method remove;
        Object[] removeArgs;
        Listener(Object target, Class<?> contract) throws Exception {
            this.target = target;
            dispatch = contract.getMethod("dispatchPrimaryClipChanged");
            if (dispatch.getReturnType() != void.class || dispatch.getParameterTypes().length != 0) throw new UnsupportedOperationException("Unknown clipboard listener callback");
            transaction = RuntimeReflection.field(Class.forName(LISTENER + "$Stub"), "TRANSACTION_dispatchPrimaryClipChanged").getInt(null);
            proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (object, method, args) -> {
                if (method.getName().equals("asBinder")) return this;
                throw new UnsupportedOperationException("Clipboard callbacks require an actual OS Binder transaction");
            });
            attachInterface((IInterface) proxy, LISTENER);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(LISTENER); return true; }
            if (code != transaction) return false;
            if (Binder.getCallingUid() != Process.SYSTEM_UID || (flags & IBinder.FLAG_ONEWAY) == 0) throw new SecurityException("Clipboard event requires the actual one-way system callback");
            data.enforceInterface(LISTENER);
            if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected clipboard event payload");
            received.incrementAndGet();
            if (!active.get()) return true;
            if (!listenerHandler.postAtTime(() -> {
                if (!active.get() || !focused.getAsBoolean()) { filtered.incrementAndGet(); return; }
                // Acceptance occurs here; the original framework Stub retains its own asynchronous Handler queue.
                try { dispatch.invoke(target); accepted.incrementAndGet(); }
                catch (InvocationTargetException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw new EngineException("Actual clipboard listener dispatch failed", cause);
                } catch (Exception failure) { throw new EngineException("Actual clipboard listener dispatch failed", failure); }
            }, this, android.os.SystemClock.uptimeMillis())) throw new EngineException("Guest clipboard callback Handler is unavailable");
            return true;
        }
    }

    private void requireSignature(Method method) {
        String name = method.getName();
        Class<?> result = name.equals("getPrimaryClipDescription") ? ClipDescription.class
                : name.equals("getPrimaryClip") ? ClipData.class
                : name.equals("hasPrimaryClip") || name.equals("hasClipboardText") ? boolean.class : void.class;
        Class<?>[] expected = signature(sdk);
        if (name.equals("setPrimaryClip")) {
            Class<?>[] withClip = new Class<?>[expected.length + 1]; withClip[0] = ClipData.class;
            System.arraycopy(expected, 0, withClip, 1, expected.length); expected = withClip;
        }
        if (method.getReturnType() != result || !Arrays.equals(method.getParameterTypes(), expected)) {
            throw new UnsupportedOperationException("Unknown clipboard metadata signature");
        }
    }

    private static Class<?>[] signature(int sdk) {
        // Fixed AOSP android-8.0.0_r1 through android-15.0.0_r1; no positional String/int scan.
        if (sdk >= 26 && sdk <= 28) return new Class<?>[]{String.class};
        if (sdk >= 29 && sdk <= 33) return new Class<?>[]{String.class, int.class};
        if (sdk >= 34 && sdk <= 35) return new Class<?>[]{String.class, String.class, int.class, int.class};
        throw new UnsupportedOperationException("Clipboard metadata Android version has not been verified");
    }
}
