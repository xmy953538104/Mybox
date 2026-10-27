package io.github.xmy953538104.mybox.engine;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;

/** Public capability queries keep Android's permissions, authenticator mask and actual result. */
final class GuestBiometricCalls implements InvocationHandler {
    private final Object original;
    private final String guest, host;
    private final int physicalUser;
    private final boolean declared;
    private IBinder local;

    GuestBiometricCalls(Object original, String guest, String host, int physicalUser, boolean declared) {
        this.original = original; this.guest = guest; this.host = host;
        this.physicalUser = physicalUser; this.declared = declared;
    }

    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        Object manager = engine.hostContext().getSystemService("biometric");
        if (manager == null) return;
        Object original = RuntimeReflection.get(manager, "mService");
        if (original == null) return;
        PackageInfo manifest = engine.packages().getPackageInfo(runtime.packageInfo().packageName, PackageManager.GET_PERMISSIONS);
        boolean declared = manifest != null && manifest.requestedPermissions != null
                && (Arrays.asList(manifest.requestedPermissions).contains("android.permission.USE_BIOMETRIC")
                || Arrays.asList(manifest.requestedPermissions).contains("android.permission.USE_FINGERPRINT"));
        GuestBiometricCalls calls = new GuestBiometricCalls(original, runtime.packageInfo().packageName,
                engine.hostContext().getPackageName(), android.os.Process.myUid() / 100000, declared);
        Class<?> contract = Class.forName("android.hardware.biometrics.IAuthService");
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw virtual biometric transactions are not supported");
            }
        };
        local.attachInterface(proxy, contract.getName()); calls.local = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put("auth", local); RuntimeReflection.set(manager, "mService", proxy);
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteBiometricManager";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        if (name.equals("canAuthenticate") || name.equals("getButtonLabel")
                || name.equals("getPromptMessage") || name.equals("getSettingName")) {
            int packageIndex = name.equals("canAuthenticate") ? 0 : 1;
            int userIndex = 1 - packageIndex;
            if (args == null || args.length != 3 || !(args[packageIndex] instanceof String)
                    || !(args[userIndex] instanceof Integer) || !(args[2] instanceof Integer))
                throw new UnsupportedOperationException("Unknown biometric query signature: " + name);
            if (!guest.equals(args[packageIndex]) && !host.equals(args[packageIndex]))
                throw new SecurityException("Biometric query does not belong to the bound guest");
            if (!Integer.valueOf(0).equals(args[userIndex])) throw new SecurityException("Only virtual user zero exists");
            if (!declared) throw new SecurityException("Guest did not declare a biometric permission");
            args = args.clone(); args[packageIndex] = host; args[userIndex] = physicalUser;
        } else if (name.equals("getAuthenticatorIds")) {
            // Android Keystore uses this query even without a declared biometric permission.
            if (args == null || args.length != 1 || !(args[0] instanceof Integer))
                throw new UnsupportedOperationException("Unknown authenticator-ID query signature");
            if (!Integer.valueOf(0).equals(args[0])) throw new SecurityException("Only virtual user zero exists");
            args = new Object[]{physicalUser};
        } else if (name.equals("authenticate") || name.equals("cancelAuthentication")) {
            // shortcut: real prompt/token/cancellation ownership is separate from capability queries.
            throw new UnsupportedOperationException("Virtual biometric authentication is not implemented");
        } else if (args != null) {
            for (Object argument : args) if (guest.equals(argument))
                throw new UnsupportedOperationException("Guest biometric operation is not adapted: " + name);
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
