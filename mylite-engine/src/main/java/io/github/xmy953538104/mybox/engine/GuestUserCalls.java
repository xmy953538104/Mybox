package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.UserManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

/** The container has one virtual user; discovering it must not enumerate privileged physical users. */
final class GuestUserCalls implements InvocationHandler {
    private final Object original;
    private final String host, guest;
    private IBinder local;
    private GuestUserCalls(Object original, String host, String guest) { this.original = original; this.host = host; this.guest = guest; }
    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        UserManager manager = engine.hostContext().getSystemService(UserManager.class);
        Object original = RuntimeReflection.get(manager, "mService");
        Class<?> contract = Class.forName("android.os.IUserManager");
        GuestUserCalls calls = new GuestUserCalls(original, engine.hostContext().getPackageName(), runtime.packageInfo().packageName);
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw virtual user transactions are not supported");
            }
        };
        local.attachInterface(proxy, contract.getName()); calls.local = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put("user", local); RuntimeReflection.set(manager, "mService", proxy);
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteUserManager";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        if (name.equals("getUsers")) {
            if (args != null) for (Object value : args) if (!(value instanceof Boolean)) throw new UnsupportedOperationException("Unknown user enumeration signature");
            return new ArrayList<>(Collections.singletonList(user()));
        }
        if (name.equals("getUserInfo") || name.equals("getProfiles") || name.equals("getProfileIds") || name.equals("getProfileParent")) {
            if (args == null || args.length == 0 || !Integer.valueOf(0).equals(args[0])) throw new SecurityException("Only virtual user zero exists");
            for (int i = 1; i < args.length; i++) if (!(args[i] instanceof Boolean)) throw new UnsupportedOperationException("Unknown user profile signature");
            if (name.equals("getProfileParent")) return null;
            if (name.equals("getProfileIds")) return new int[]{0};
            return name.equals("getUserInfo") ? user() : new ArrayList<>(Collections.singletonList(user()));
        }
        if (name.equals("getApplicationRestrictions") || name.equals("getApplicationRestrictionsForUser")) {
            int count = name.equals("getApplicationRestrictions") ? 1 : 2;
            if (args == null || args.length != count || !guest.equals(args[0]) || count == 2 && !Integer.valueOf(0).equals(args[1])) {
                throw new SecurityException("Restrictions query is not this guest's virtual user zero");
            }
            method = RuntimeReflection.method(Class.forName("android.os.IUserManager"), "getApplicationRestrictions", String.class);
            args = new Object[]{host};
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Object user() throws Exception {
        Class<?> type = Class.forName("android.content.pm.UserInfo");
        int flags = RuntimeReflection.field(type, "FLAG_PRIMARY").getInt(null) | RuntimeReflection.field(type, "FLAG_INITIALIZED").getInt(null);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            flags |= RuntimeReflection.field(type, "FLAG_SYSTEM").getInt(null);
            return type.getConstructor(int.class, String.class, String.class, int.class, String.class)
                    .newInstance(0, "Mybox", null, flags, "android.os.usertype.full.SYSTEM");
        }
        return type.getConstructor(int.class, String.class, int.class).newInstance(0, "Mybox", flags);
    }
}
