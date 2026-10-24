package io.github.xmy953538104.mybox.engine;

import android.content.RestrictionsManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Read the real owning app's administrator restrictions; never manufacture an unrestricted bundle. */
final class GuestRestrictionsCalls implements InvocationHandler {
    private final Object original;
    private final String host, guest;
    private IBinder local;
    private GuestRestrictionsCalls(Object original, String host, String guest) { this.original = original; this.host = host; this.guest = guest; }
    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        RestrictionsManager manager = engine.hostContext().getSystemService(RestrictionsManager.class);
        if (manager == null) return;
        Object original = RuntimeReflection.get(manager, "mService"); if (original == null) return;
        Class<?> contract = Class.forName("android.content.IRestrictionsManager");
        GuestRestrictionsCalls calls = new GuestRestrictionsCalls(original, engine.hostContext().getPackageName(), runtime.packageInfo().packageName);
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw guest restriction transactions are not supported");
            }
        };
        local.attachInterface(proxy, contract.getName()); calls.local = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put("restrictions", local); RuntimeReflection.set(manager, "mService", proxy);
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteRestrictionsManager";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        if (name.equals("getApplicationRestrictions")) {
            if (args == null || args.length != 1 || !guest.equals(args[0])) throw new SecurityException("Restrictions query is not for this guest");
            args = new Object[]{host};
        } else if (args != null) {
            for (Object argument : args) if (guest.equals(argument)) throw new UnsupportedOperationException("Virtual restriction operation is not implemented: " + name);
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
