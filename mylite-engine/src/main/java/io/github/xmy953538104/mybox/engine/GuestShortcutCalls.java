package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Physical configuration plus authenticated, per-guest shortcut data and launch routing. */
final class GuestShortcutCalls implements InvocationHandler {
    private static final String CONTRACT = "android.content.pm.IShortcutService";
    private static GuestShortcutCalls installed;
    private final Object original;
    private final String guest, host;
    private final int user;
    private IBinder binder;
    private IBinder server;
    private GuestRuntime runtime;

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
        Bundle request = EngineProtocol.response(); request.putString("service", "shortcuts");
        calls.server = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.GET_SERVICE, request).getBinder("service");
        if (calls.server == null) throw new EngineException("Virtual shortcut service is unavailable");
        calls.runtime = runtime;
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
            if (name.equals("toString")) return "MyliteShortcuts";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        long trace = EngineDiagnostics.begin("shortcut_api", name, 0);
        try {
            if (args == null || args.length < 2 || method.getParameterTypes()[0] != String.class
                    || method.getParameterTypes()[args.length - 1] != int.class)
                throw new UnsupportedOperationException("Unknown virtual shortcut signature: " + name);
            if (!(args[0] instanceof String) || !(args[args.length - 1] instanceof Integer)
                    || !guest.equals(args[0]) && !host.equals(args[0]) || ((Integer) args[args.length - 1]) != user)
                throw new SecurityException("Shortcut query is not for the current guest user");
            Object value;
            if (args.length == 2 && (name.equals("getIconMaxDimensions") || name.equals("getMaxShortcutCountPerActivity")
                    || name.equals("getRemainingCallCount") || name.equals("getRateLimitResetTime"))) {
                Object[] physical = args.clone(); physical[0] = host;
                try { value = method.invoke(original, physical); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            } else value = data(name, args);
            EngineDiagnostics.event("shortcut_api", "returned", trace, 0); return value;
        } catch (Throwable failure) { EngineDiagnostics.failure("shortcut_api", name, trace, failure); throw failure; }
    }

    private Object data(String name, Object[] args) throws Exception {
        // An unknown or system-only API cannot fall through to the physical publisher identity.
        if (server == null) throw new UnsupportedOperationException("Shortcut data service is not connected");
        Bundle request = EngineProtocol.response(); request.putString("operation", name); request.putString("package", guest); request.putInt("user", 0);
        boolean query = name.equals("getDynamicShortcuts") || name.equals("getPinnedShortcuts") || name.equals("getManifestShortcuts");
        if (query || name.equals("removeAllDynamicShortcuts")) {
            if (args.length != 2) throw new UnsupportedOperationException("Unknown shortcut query shape");
        } else if (name.equals("getShortcuts")) {
            if (args.length != 3 || !(args[1] instanceof Integer)) throw new UnsupportedOperationException("Unknown shortcut match shape");
            request.putInt("match", (Integer) args[1]); query = true;
        } else if (name.equals("setDynamicShortcuts") || name.equals("addDynamicShortcuts") || name.equals("updateShortcuts")) {
            if (args.length != 3 || args[1] == null) throw new UnsupportedOperationException("Unknown shortcut publication shape");
            List<?> supplied = (List<?>) RuntimeReflection.invoke(args[1], RuntimeReflection.method(args[1].getClass(), "getList"));
            if (supplied.size() > 32) throw new IllegalArgumentException("Too many shortcuts");
            ArrayList<ShortcutInfo> values = new ArrayList<>();
            for (Object item : supplied) {
                if (!(item instanceof ShortcutInfo) || !guest.equals(((ShortcutInfo) item).getPackage())) throw new SecurityException("Foreign shortcut publisher");
                ShortcutInfo copy = ServerShortcuts.copy((ShortcutInfo) item);
                RuntimeReflection.invoke(copy, RuntimeReflection.method(ShortcutInfo.class, "resolveResourceStrings", android.content.res.Resources.class), runtime.application().getResources());
                android.graphics.drawable.Icon icon = (android.graphics.drawable.Icon) RuntimeReflection.get(copy, "mIcon");
                if (icon != null) RuntimeReflection.set(copy, "mIcon", ForegroundNotifications.icon(runtime.application(), icon));
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    android.app.Person[] persons = (android.app.Person[]) RuntimeReflection.invoke(copy, RuntimeReflection.method(ShortcutInfo.class, "getPersons"));
                    if (persons != null) {
                        for (int index = 0; index < persons.length; index++) if (persons[index] != null && persons[index].getIcon() != null)
                            persons[index] = persons[index].toBuilder().setIcon(ForegroundNotifications.icon(runtime.application(), persons[index].getIcon())).build();
                        RuntimeReflection.set(copy, "mPersons", persons);
                    }
                }
                values.add(copy);
            }
            request.putParcelable("shortcuts", (android.os.Parcelable) Class.forName("android.content.pm.ParceledListSlice").getConstructor(List.class).newInstance(values));
        } else if (name.equals("enableShortcuts") || name.equals("disableShortcuts") || name.equals("removeDynamicShortcuts")
                || name.equals("removeLongLivedShortcuts") || name.equals("reportShortcutUsed")) {
            int expected = name.equals("disableShortcuts") ? 5 : 3;
            if (args.length != expected) throw new UnsupportedOperationException("Unknown shortcut ID operation shape");
            ArrayList<String> ids = new ArrayList<>();
            if (name.equals("reportShortcutUsed")) ids.add((String) args[1]);
            else {
                if (!(args[1] instanceof List) || ((List<?>) args[1]).size() > 256) throw new IllegalArgumentException("Invalid shortcut IDs");
                for (Object id : (List<?>) args[1]) {
                    if (!(id instanceof String)) throw new IllegalArgumentException("Invalid shortcut ID"); ids.add((String) id);
                }
            }
            request.putStringArrayList("ids", ids);
            if (name.equals("disableShortcuts")) {
                if (!(args[3] instanceof Integer)) throw new UnsupportedOperationException("Unknown disabled-message resource");
                CharSequence message = (CharSequence) args[2];
                if ((Integer) args[3] != 0) message = runtime.application().getText((Integer) args[3]);
                request.putCharSequence("message", message);
            }
        } else throw new UnsupportedOperationException("Unsupported virtual shortcut API: " + name);
        Bundle result = EngineProtocol.request(server, ServerShortcuts.DESCRIPTOR, IBinder.FIRST_CALL_TRANSACTION, request);
        if (query) {
            Object values = result.getParcelable("shortcuts");
            if (!Class.forName("android.content.pm.ParceledListSlice").isInstance(values)) throw new EngineException("Missing shortcut query result");
            return values;
        }
        return name.equals("setDynamicShortcuts") || name.equals("addDynamicShortcuts") || name.equals("updateShortcuts") ? result.getBoolean("value") : null;
    }
}
