package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.Process;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Predicate;

/** Self power-policy queries refer to the UID actually running this bound guest. */
final class GuestDeviceIdleCalls implements InvocationHandler {
    private static final String DESCRIPTOR = "android.os.IDeviceIdleController";
    private static GuestDeviceIdleCalls installed;
    private final Object original;
    private final String guest, host;
    private final Predicate<String> virtualPackage;

    GuestDeviceIdleCalls(Object original, String guest, String host, Predicate<String> virtualPackage) {
        this.original = original; this.guest = guest; this.host = host; this.virtualPackage = virtualPackage;
    }

    @SuppressWarnings("unchecked") static synchronized void install(Engine engine, GuestRuntime runtime) throws Exception {
        String guest = runtime.packageInfo().packageName;
        Context context = engine.hostContext();
        String host = context.getOpPackageName();
        if (context.getApplicationInfo().uid != Process.myUid() || !host.equals(context.getPackageName())) {
            throw new SecurityException("DeviceIdle host identity is not the physical app");
        }
        if (installed != null) {
            if (!guest.equals(installed.guest) || !host.equals(installed.host)) throw new SecurityException("DeviceIdle identity is already sealed");
            return;
        }
        Class<?> manager = Class.forName("android.os.ServiceManager");
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(manager, "sCache").get(null);
        IBinder physical = (IBinder) RuntimeReflection.invoke(null, RuntimeReflection.method(manager, "getService", String.class), "deviceidle");
        if (physical == null || !DESCRIPTOR.equals(physical.getInterfaceDescriptor())) throw new EngineException("Physical DeviceIdle service is unavailable");
        Class<?> contract = Class.forName(DESCRIPTOR);
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName(DESCRIPTOR + "$Stub"), "asInterface", IBinder.class), physical);
        for (String name : new String[]{"isPowerSaveWhitelistApp", "isPowerSaveWhitelistExceptIdleApp"}) {
            if (contract.getMethod(name, String.class).getReturnType() != boolean.class) throw new UnsupportedOperationException("Unknown DeviceIdle query signature");
        }
        GuestDeviceIdleCalls calls = new GuestDeviceIdleCalls(original, guest, host, target -> {
            try { return engine.packages().get(target) != null; }
            catch (java.io.IOException failure) { throw new EngineException("DeviceIdle target metadata is unavailable", failure); }
        });
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(DESCRIPTOR); return true; }
                throw new UnsupportedOperationException("Raw DeviceIdle transactions cannot preserve virtual package identity");
            }
        };
        local.attachInterface((IInterface) proxy, DESCRIPTOR);
        PowerManager power = context.getSystemService(PowerManager.class);
        boolean cachedPath = false;
        try {
            Field direct = RuntimeReflection.field(PowerManager.class, "mIDeviceIdleController");
            direct.set(power, proxy); cachedPath = direct.get(power) == proxy;
        } catch (NoSuchFieldException modernLayout) { /* Modern managers below hold the controller. */ }
        for (String service : new String[]{"deviceidle", "power_whitelist", "power_exemption"}) {
            Object current = context.getSystemService(service);
            if (current == null) continue;
            Object previous = RuntimeReflection.get(current, "mService");
            if (!contract.isInstance(previous)) throw new EngineException("Unknown cached DeviceIdle manager layout");
            RuntimeReflection.set(current, "mService", proxy);
            if (RuntimeReflection.get(current, "mService") != proxy) throw new EngineException("Cached DeviceIdle mapping did not stick");
            cachedPath = true;
        }
        if (!cachedPath) throw new UnsupportedOperationException("No supported cached DeviceIdle query path");
        synchronized (cache) { cache.put("deviceidle", local); }
        Object readBack = RuntimeReflection.invoke(null, RuntimeReflection.method(manager, "getService", String.class), "deviceidle");
        if (readBack != local) throw new EngineException("DeviceIdle service mapping did not stick");
        installed = calls;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] supplied) throws Throwable {
        Object[] args = supplied;
        if (method.getName().equals("isPowerSaveWhitelistApp") || method.getName().equals("isPowerSaveWhitelistExceptIdleApp")) {
            if (method.getReturnType() != boolean.class || method.getParameterTypes().length != 1
                    || method.getParameterTypes()[0] != String.class || args == null || args.length != 1 || !(args[0] instanceof String)) {
                throw new UnsupportedOperationException("Unknown DeviceIdle query shape");
            }
            String target = (String) args[0];
            if (guest.equals(target)) { args = args.clone(); args[0] = host; }
            else if (!host.equals(target) && virtualPackage.test(target)) {
                throw new UnsupportedOperationException("Foreign virtual DeviceIdle target has no physical package policy");
            }
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
