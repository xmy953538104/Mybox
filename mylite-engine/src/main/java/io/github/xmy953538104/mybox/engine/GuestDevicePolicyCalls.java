package io.github.xmy953538104.mybox.engine;

import android.app.admin.DevicePolicyManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** No profile owner is registered in virtual user zero. Physical policy enforcement stays in Android. */
final class GuestDevicePolicyCalls implements InvocationHandler {
    private final Object original;
    private IBinder local;
    private GuestDevicePolicyCalls(Object original) { this.original = original; }
    @SuppressWarnings("unchecked") static void install(Engine engine) throws Exception {
        DevicePolicyManager manager = engine.hostContext().getSystemService(DevicePolicyManager.class);
        if (manager == null) return;
        Object original = RuntimeReflection.get(manager, "mService"); if (original == null) return;
        Class<?> contract = Class.forName("android.app.admin.IDevicePolicyManager");
        GuestDevicePolicyCalls calls = new GuestDevicePolicyCalls(original);
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw virtual policy transactions are not supported");
            }
        };
        local.attachInterface(proxy, contract.getName()); calls.local = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put("device_policy", local); RuntimeReflection.set(manager, "mService", proxy);
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteDevicePolicyManager";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        if (name.equals("getProfileOwnerName")) {
            if (args == null || args.length != 1 || !Integer.valueOf(0).equals(args[0])) throw new SecurityException("Only virtual user zero exists");
            return null;
        }
        if (name.equals("getDeviceOwnerName")) {
            if (args != null && args.length != 0) throw new UnsupportedOperationException("Unknown device owner name signature");
            return null;
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
