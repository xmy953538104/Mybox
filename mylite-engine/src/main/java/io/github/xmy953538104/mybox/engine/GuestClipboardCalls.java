package io.github.xmy953538104.mybox.engine;

import android.content.ClipDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Clipboard access retains the actual OS checks and requires this guest's own foreground focus. */
final class GuestClipboardCalls implements InvocationHandler {
    private final Object original;
    private final String guestPackage;
    private final String hostPackage;
    private final String hostAttribution;
    private final int hostUser;
    private final int hostDevice;
    private final int sdk;
    private final BooleanSupplier focused;
    private final Runnable requireLocalCaller;

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
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        IBinder physical = ((IInterface) original).asBinder();
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                // Raw calls retain the prior OS checks and arguments; only the local Java query is adapted.
                return physical.transact(code, data, reply, flags);
            }
        };
        local.attachInterface(proxy, "android.content.IClipboard");
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(
                Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put(Context.CLIPBOARD_SERVICE, local);
        RuntimeReflection.field(ClipboardManager.class, "mService").set(current, proxy);
        if (cache.get(Context.CLIPBOARD_SERVICE) != local || RuntimeReflection.get(current, "mService") != proxy) {
            throw new EngineException("Clipboard metadata boundary was not installed");
        }
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object[] forwarded = args;
        String name = method.getName();
        boolean read = name.equals("getPrimaryClipDescription") || name.equals("getPrimaryClip")
                || name.equals("hasPrimaryClip") || name.equals("hasClipboardText");
        boolean write = name.equals("setPrimaryClip") || name.equals("clearPrimaryClip");
        if (read || write) {
            requireSignature(method);
            int packageIndex = name.equals("setPrimaryClip") ? 1 : 0;
            if (args == null || args.length != method.getParameterTypes().length || !guestPackage.equals(args[packageIndex])) {
                throw new SecurityException("Clipboard metadata package is not the bound guest");
            }
            int userIndex = sdk >= 34 ? 2 : sdk >= 29 ? 1 : -1;
            if (userIndex >= 0) userIndex += packageIndex;
            if (userIndex >= 0 && !Integer.valueOf(0).equals(args[userIndex])) {
                throw new SecurityException("Clipboard metadata supports only virtual user zero");
            }
            if (sdk >= 34) {
                if (args[packageIndex + 1] != null) throw new UnsupportedOperationException("Guest clipboard attribution tags are not mapped");
                if (!Integer.valueOf(hostDevice).equals(args[packageIndex + 3])) {
                    throw new SecurityException("Clipboard metadata device differs from the host context");
                }
            }
            requireLocalCaller.run();
            // Same-UID processes share the OS focus check. An unfocused guest may not borrow that focus.
            if (!focused.getAsBoolean()) {
                if (write) throw new SecurityException("An unfocused guest cannot replace the clipboard");
                return method.getReturnType() == boolean.class ? false : null;
            }
            forwarded = args.clone();
            forwarded[packageIndex] = hostPackage;
            if (userIndex >= 0) forwarded[userIndex] = hostUser;
            if (sdk >= 34) forwarded[packageIndex + 1] = hostAttribution;
        }
        try {
            return method.invoke(original, forwarded);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
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
