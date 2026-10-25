package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Subscription listeners use their real OS callback and the physical caller's attribution. */
final class GuestTelephonyRegistryCalls implements InvocationHandler {
    private static final String SERVICE = "telephony.registry";
    private static final String CONTRACT = "com.android.internal.telephony.ITelephonyRegistry";
    private static final String CALLBACK = "com.android.internal.telephony.IOnSubscriptionsChangedListener";
    private final Object original;
    private final String host, guest, hostTag;
    private final IBinder backend;
    private IBinder local;

    private GuestTelephonyRegistryCalls(Object original, String host, String guest, String hostTag) {
        this.original = original; this.host = host; this.guest = guest; this.hostTag = hostTag;
        backend = ((IInterface) original).asBinder();
    }

    static IInterface wrap(Object original, String host, String guest, String hostTag) throws Exception {
        if (Proxy.isProxyClass(original.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(original);
            if (handler instanceof GuestTelephonyRegistryCalls) {
                GuestTelephonyRegistryCalls existing = (GuestTelephonyRegistryCalls) handler;
                if (!host.equals(existing.host) || !guest.equals(existing.guest) || !Objects.equals(hostTag, existing.hostTag)) {
                    throw new SecurityException("Telephony registry is already bound to another guest");
                }
                return (IInterface) original;
            }
        }
        Class<?> contract = Class.forName(CONTRACT);
        GuestTelephonyRegistryCalls calls = new GuestTelephonyRegistryCalls(original, host, guest, hostTag);
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw guest telephony registry transactions are not supported");
            }
        };
        local.attachInterface(proxy, CONTRACT); calls.local = local;
        return proxy;
    }

    @SuppressWarnings("unchecked") static synchronized void install(Engine engine, GuestRuntime runtime) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) RuntimeReflection.invoke(null, RuntimeReflection.method(serviceManager, "getService", String.class), SERVICE);
        if (binder == null) return; // Preserve a device which genuinely has no registry service.
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName(CONTRACT + "$Stub"), "asInterface", IBinder.class), binder);
        String host = engine.hostContext().getPackageName(), guest = runtime.packageInfo().packageName;
        String tag = android.os.Build.VERSION.SDK_INT >= 30 ? engine.hostContext().getAttributionTag() : null;
        IInterface proxy = wrap(original, host, guest, tag);
        GuestTelephonyRegistryCalls calls = (GuestTelephonyRegistryCalls) Proxy.getInvocationHandler(proxy);
        List<Field> fields = new ArrayList<>();
        // Discover caches by their actual presence, rather than guessing a framework transition SDK.
        for (String owner : new String[]{"android.telephony.TelephonyManager", "android.telephony.TelephonyRegistryManager"}) {
            Field field;
            try { field = RuntimeReflection.field(Class.forName(owner), "sRegistry"); }
            catch (ClassNotFoundException | NoSuchFieldException absent) { continue; }
            Object cached = field.get(null);
            if (cached != null && cached != proxy && ((IInterface) cached).asBinder() != calls.backend) {
                throw new EngineException("Telephony registry cache has a different service owner");
            }
            fields.add(field);
        }
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(serviceManager, "sCache").get(null);
        cache.put(SERVICE, proxy.asBinder());
        for (Field field : fields) field.set(null, proxy);
        if (cache.get(SERVICE) != proxy.asBinder()) throw new EngineException("Telephony registry service boundary was not installed");
        for (Field field : fields) if (field.get(null) != proxy) throw new EngineException("Telephony registry static boundary was not installed");
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder") && method.getParameterCount() == 0) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteTelephonyRegistry";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        if (name.equals("addOnSubscriptionsChangedListener") || name.equals("addOnOpportunisticSubscriptionsChangedListener")
                || name.equals("removeOnSubscriptionsChangedListener")) {
            if (Binder.getCallingUid() != android.os.Process.myUid() || Binder.getCallingPid() != android.os.Process.myPid()) {
                throw new SecurityException("Telephony attribution requires a local guest call");
            }
            Class<?>[] types = method.getParameterTypes();
            boolean remove = name.equals("removeOnSubscriptionsChangedListener");
            // AOSP 26/28/29 use (pkg, callback); 30/34/35 add featureId on registration.
            if (args == null || args.length != types.length || method.getReturnType() != void.class
                    || (types.length != 2 && (remove || types.length != 3)) || types[0] != String.class
                    || !types[types.length - 1].getName().equals(CALLBACK)
                    || types.length == 3 && types[1] != String.class) {
                throw new UnsupportedOperationException("Unknown subscription listener signature: " + method);
            }
            if (!guest.equals(args[0]) && !host.equals(args[0])) throw new SecurityException("Subscription listener is not for the bound guest");
            args = args.clone(); args[0] = host;
            if (types.length == 3) args[1] = hostTag;
        } else if (name.equals("listen") || name.equals("listenForSubscriber") || name.equals("listenWithFeature")
                || name.equals("listenForSubscriberWithFeature") || name.equals("listenWithEventList")) {
            throw new UnsupportedOperationException("Radio event permissions and delivery are not adapted: " + name);
        } else if (args != null) {
            for (Object argument : args) if (guest.equals(argument)) {
                throw new UnsupportedOperationException("Guest telephony registry operation is not adapted: " + name);
            }
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
