package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Keep actual OS networks and callbacks, attributing physical requests to their real owner. */
final class GuestConnectivityCalls implements InvocationHandler {
    private final Object original;
    private final String host, guest;
    private final int hostUid, guestUid;
    private final String hostTag;
    private IBinder local;

    private GuestConnectivityCalls(Object original, Engine engine, GuestRuntime runtime) {
        this.original = original;
        host = engine.hostContext().getPackageName(); guest = runtime.packageInfo().packageName;
        hostUid = engine.hostContext().getApplicationInfo().uid;
        guestUid = runtime.packageInfo().applicationInfo.uid;
        hostTag = android.os.Build.VERSION.SDK_INT >= 30 ? engine.hostContext().getAttributionTag() : null;
    }

    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        ConnectivityManager manager = engine.hostContext().getSystemService(ConnectivityManager.class);
        Object original = RuntimeReflection.get(manager, "mService");
        Class<?> contract = Class.forName("android.net.IConnectivityManager");
        GuestConnectivityCalls calls = new GuestConnectivityCalls(original, engine, runtime);
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw guest connectivity transactions are not supported");
            }
        };
        local.attachInterface(proxy, contract.getName()); calls.local = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put(Context.CONNECTIVITY_SERVICE, local); RuntimeReflection.set(manager, "mService", proxy);
        if (cache.get(Context.CONNECTIVITY_SERVICE) != local || RuntimeReflection.get(manager, "mService") != proxy) {
            throw new EngineException("Connectivity boundary was not installed");
        }
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteConnectivityManager";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        int packageIndex = -1, count = -1;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            switch (name) {
                case "getNetworkCapabilities": case "getDefaultNetworkCapabilitiesForUser": packageIndex = 1; count = 3; break;
                case "requestRouteToHostAddress": packageIndex = 2; count = 4; break;
                case "requestNetwork": packageIndex = 8; count = 10; break;
                case "listenForNetwork": packageIndex = 4; count = 6; break;
                case "pendingRequestForNetwork": case "pendingListenForNetwork":
                    throw new UnsupportedOperationException("PendingIntent network requests need a virtual delivery adapter");
            }
        }
        if (packageIndex >= 0) {
            Class<?>[] types = method.getParameterTypes();
            if (args == null || args.length != count || types.length != count
                    || types[packageIndex] != String.class || types[packageIndex + 1] != String.class) {
                throw new UnsupportedOperationException("Unknown connectivity attribution signature: " + method);
            }
            if (!guest.equals(args[packageIndex]) && !host.equals(args[packageIndex])) throw new SecurityException("Connectivity request is not for the bound guest");
            args = args.clone(); args[packageIndex] = host; args[packageIndex + 1] = hostTag;
            if (name.equals("requestNetwork")) {
                int uid = ((Number) args[0]).intValue();
                if (uid != -1 && uid != guestUid && uid != hostUid) throw new SecurityException("Connectivity request targets another UID");
                if (uid != -1) args[0] = hostUid;
            }
            if (name.equals("getDefaultNetworkCapabilitiesForUser") && !Integer.valueOf(0).equals(args[0])) {
                throw new SecurityException("Connectivity query targets another virtual user");
            }
        } else if (args != null) {
            for (Object argument : args) if (guest.equals(argument)) throw new UnsupportedOperationException("Guest connectivity operation is not adapted: " + name);
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
