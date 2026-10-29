package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.content.pm.ShortcutManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Display and per-Activity limits belong to the physical process presenting the guest. */
final class GuestShortcutCalls implements InvocationHandler {
    private static final String CONTRACT = "android.content.pm.IShortcutService";
    private static GuestShortcutCalls installed;
    private final Object original;
    private final String guest, host;
    private final int user;
    private IBinder binder;

    GuestShortcutCalls(Object original, String guest, String host, int user) {
        this.original = original; this.guest = guest; this.host = host; this.user = user;
    }

    @SuppressWarnings("unchecked") static synchronized void install(Engine engine, GuestRuntime runtime) throws Exception {
        Context context = engine.hostContext();
        String guest = runtime.packageInfo().packageName, host = context.getPackageName();
        if (context.getApplicationInfo().uid != Process.myUid() || !host.equals(context.getOpPackageName()))
            throw new SecurityException("Shortcut host identity is not the physical app");
        if (installed != null) {
            if (!guest.equals(installed.guest) || !host.equals(installed.host)) throw new SecurityException("Shortcut identity is already sealed");
            return;
        }
        Class<?> manager = Class.forName("android.os.ServiceManager"), contract = Class.forName(CONTRACT);
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(manager, "sCache").get(null);
        IBinder physical = (IBinder) RuntimeReflection.invoke(null, RuntimeReflection.method(manager, "getService", String.class), "shortcut");
        if (physical == null || !CONTRACT.equals(physical.getInterfaceDescriptor())) throw new EngineException("Physical shortcut service is unavailable");
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName(CONTRACT + "$Stub"), "asInterface", IBinder.class), physical);
        for (String name : new String[]{"getIconMaxDimensions", "getMaxShortcutCountPerActivity"})
            if (contract.getMethod(name, String.class, int.class).getReturnType() != int.class) throw new UnsupportedOperationException("Unknown shortcut query signature");
        GuestShortcutCalls calls = new GuestShortcutCalls(original, guest, host, Process.myUid() / 100000);
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(CONTRACT); return true; }
                throw new UnsupportedOperationException("Raw shortcut transactions cannot preserve guest identity");
            }
        };
        local.attachInterface((IInterface) proxy, CONTRACT); calls.binder = local;
        ShortcutManager shortcuts = context.getSystemService(ShortcutManager.class);
        if (shortcuts == null) throw new EngineException("Cached shortcut manager is unavailable");
        RuntimeReflection.set(shortcuts, "mService", proxy);
        if (RuntimeReflection.get(shortcuts, "mService") != proxy) throw new EngineException("Shortcut manager mapping did not stick");
        synchronized (cache) { cache.put("shortcut", local); }
        installed = calls;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return binder;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteShortcutQueries";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        long trace = EngineDiagnostics.begin("shortcut_api", name, 0);
        try {
            // shortcut: shortcut data, mutation and per-app rate accounting need their own virtual store and launch envelopes.
            if (!(name.equals("getIconMaxDimensions") || name.equals("getMaxShortcutCountPerActivity"))
                    || method.getReturnType() != int.class || args == null || args.length != 2 || method.getParameterTypes().length != 2
                    || method.getParameterTypes()[0] != String.class || method.getParameterTypes()[1] != int.class)
                throw new UnsupportedOperationException("Unsupported virtual shortcut API: " + name);
            if (!(args[0] instanceof String) || !(args[1] instanceof Integer)
                    || !guest.equals(args[0]) && !host.equals(args[0]) || ((Integer) args[1]) != user)
                throw new SecurityException("Shortcut query is not for the current guest user");
            Object[] physical = args.clone(); physical[0] = host;
            Object value;
            try { value = method.invoke(original, physical); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            EngineDiagnostics.event("shortcut_api", "returned", trace, 0); return value;
        } catch (Throwable failure) { EngineDiagnostics.failure("shortcut_api", name, trace, failure); throw failure; }
    }
}
