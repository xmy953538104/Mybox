package io.github.xmy953538104.mybox.engine;

import android.app.Notification;
import android.app.NotificationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcelable;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Framework notification methods terminate at the authenticated virtual notification service. */
final class GuestNotificationCalls implements InvocationHandler {
    private final Engine engine;
    private final GuestRuntime runtime;
    private final IBinder server;
    private final Object original;
    private static GuestNotificationCalls installed;

    private GuestNotificationCalls(Engine engine, GuestRuntime runtime, IBinder server, Object original) {
        this.engine = engine; this.runtime = runtime; this.server = server; this.original = original;
    }

    static void install(Engine engine, GuestRuntime runtime) throws Exception {
        Bundle input = EngineProtocol.response(); input.putString("service", "notifications");
        IBinder server = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.GET_SERVICE, input).getBinder("service");
        if (server == null) throw new EngineException("Virtual notification service is unavailable");
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(NotificationManager.class, "getService"));
        Class<?> contract = Class.forName("android.app.INotificationManager");
        GuestNotificationCalls calls = new GuestNotificationCalls(engine, runtime, server, original);
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Field notificationCache = RuntimeReflection.field(NotificationManager.class, "sService");
        Field toastCache = RuntimeReflection.field(Toast.class, "sService");
        if (notificationCache.getType() != contract || toastCache.getType() != contract) {
            throw new EngineException("Notification or Toast cache has an unknown service contract");
        }
        notificationCache.set(null, proxy);
        // Toast owns an independent cache and otherwise bypasses NotificationManager's attribution.
        toastCache.set(null, proxy);
        if (notificationCache.get(null) != proxy || toastCache.get(null) != proxy) {
            throw new EngineException("Notification and Toast attribution caches were not installed");
        }
        installed = calls;
    }

    static Bundle foreground(Notification source, int id) throws Exception {
        if (installed == null) throw new EngineException("Notification routing is not installed");
        Bundle input = installed.input("prepare"); input.putInt("id", id);
        input.putParcelable("notification", installed.normalize(source));
        return installed.call(input);
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return ((IInterface) original).asBinder();
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteNotificationManager";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        if (name.equals("enqueueTextToast") || name.equals("enqueueToast")
                || name.equals("cancelToast") || name.equals("finishToken")) {
            return toast(original, method, args, runtime.packageInfo().packageName,
                    engine.hostContext().getPackageName(), android.os.Binder.getCallingUid(), android.os.Binder.getCallingPid());
        }
        if (args == null || args.length == 0 || !runtime.packageInfo().packageName.equals(args[0])) {
            throw new SecurityException("Notification package is not the bound guest");
        }
        if (name.equals("createNotificationChannels") || name.equals("createNotificationChannelGroups")) {
            boolean channels = name.equals("createNotificationChannels");
            List<?> values = (List<?>) RuntimeReflection.invoke(args[1], RuntimeReflection.method(args[1].getClass(), "getList"));
            Bundle input = input(channels ? "createChannels" : "createGroups");
            ArrayList<Parcelable> copy = new ArrayList<>();
            for (Object value : values) copy.add((Parcelable) value);
            input.putParcelableArrayList(channels ? "channels" : "groups", copy); call(input); return null;
        }
        if (name.equals("getNotificationChannel")) {
            if (args.length == 4 && !runtime.packageInfo().packageName.equals(args[2])) throw new SecurityException("Foreign notification channel");
            Bundle input = input("getChannel"); input.putString("id", (String) args[args.length - 1]);
            return call(input).getParcelable("channel");
        }
        if (name.equals("getNotificationChannels") || name.equals("getNotificationChannelGroups")) {
            if (name.equals("getNotificationChannels") && args.length >= 3
                    && !runtime.packageInfo().packageName.equals(args[1])) throw new SecurityException("Foreign notification channels");
            String key = name.equals("getNotificationChannels") ? "channels" : "groups";
            return slice(call(input(key)).getParcelableArrayList(key));
        }
        if (name.equals("getNotificationChannelGroup")) {
            Bundle input = input("getGroup"); input.putString("id", (String) args[1]); return call(input).getParcelable("group");
        }
        if (name.equals("deleteNotificationChannel") || name.equals("deleteNotificationChannelGroup")) {
            Bundle input = input(name.equals("deleteNotificationChannel") ? "deleteChannel" : "deleteGroup");
            input.putString("id", (String) args[1]); call(input); return null;
        }
        if (name.equals("enqueueNotificationWithTag")) {
            if (args.length != 6 || !(args[4] instanceof Notification)) throw new UnsupportedOperationException("Unknown notification enqueue signature");
            Bundle input = input("notify"); input.putString("tag", (String) args[2]); input.putInt("id", ((Number) args[3]).intValue());
            input.putParcelable("notification", normalize((Notification) args[4])); call(input); return null;
        }
        if (name.equals("cancelNotificationWithTag")) {
            int index = args.length == 5 ? 2 : args.length == 4 ? 1 : -1;
            if (index < 0) throw new UnsupportedOperationException("Unknown notification cancel signature");
            Bundle input = input("cancel"); input.putString("tag", (String) args[index]); input.putInt("id", ((Number) args[index + 1]).intValue());
            call(input); return null;
        }
        if (name.equals("cancelAllNotifications")) { call(input("cancelAll")); return null; }
        if (name.equals("areNotificationsEnabled") || name.equals("areNotificationsEnabledForPackage")) return call(input("enabled")).getBoolean("enabled");
        if (name.equals("getPackageImportance")) return call(input("importance")).getInt("importance");
        if (name.equals("getAppActiveNotifications")) return slice(call(input("active")).getParcelableArrayList("active"));
        // shortcut: notification policy, interaction capabilities and custom views need their own permission-aware bridges.
        throw new UnsupportedOperationException("Virtual notification method is not implemented: " + name);
    }

    static Object toast(Object original, Method method, Object[] args, String guest, String host,
            int callingUid, int callingPid) throws Throwable {
        if (callingUid != android.os.Process.myUid() || callingPid != android.os.Process.myPid()) {
            throw new SecurityException("Toast attribution requires a local call");
        }
        // Expired guest Activity recovery also shows a Toast using the real host Context.
        if (args == null || args.length == 0 || !(guest.equals(args[0]) || host.equals(args[0]))) {
            throw new SecurityException("Toast package is not the bound guest or host");
        }
        Object[] physical = args.clone(); physical[0] = host;
        // Keep the real token, callback, display, rate limiting and API-specific return value.
        try { return method.invoke(original, physical); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private Notification normalize(Notification source) {
        return ForegroundNotifications.copy(engine.hostContext(), runtime.application(), source);
    }
    private Bundle input(String operation) {
        Bundle input = EngineProtocol.response(); input.putString("operation", operation);
        input.putString("package", runtime.packageInfo().packageName); return input;
    }
    private Bundle call(Bundle input) throws Exception { return EngineProtocol.request(server, ServerNotifications.DESCRIPTOR, IBinder.FIRST_CALL_TRANSACTION, input); }
    private static Object slice(List<?> values) throws Exception {
        return Class.forName("android.content.pm.ParceledListSlice").getConstructor(List.class).newInstance(values);
    }
}
