package io.github.xmy953538104.mybox.engine;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** No platform fallback: alarm requests end at a registered virtual creator's OS capability. */
final class GuestAlarmCalls implements InvocationHandler {
    private final String packageName;
    private final IBinder endpoint;
    private IBinder local;
    private GuestAlarmCalls(String packageName, IBinder endpoint) { this.packageName = packageName; this.endpoint = endpoint; }

    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        Bundle request = EngineProtocol.response(); request.putString("service", "alarms");
        IBinder endpoint = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.GET_SERVICE, request).getBinder("service");
        if (endpoint == null) throw new EngineException("Virtual alarm service is unavailable");
        Class<?> contract = Class.forName("android.app.IAlarmManager");
        GuestAlarmCalls calls = new GuestAlarmCalls(runtime.packageInfo().packageName, endpoint);
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw platform alarm transactions are not supported");
            }
        };
        local.attachInterface(proxy, "android.app.IAlarmManager"); calls.local = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put("alarm", local);
        AlarmManager current = (AlarmManager) engine.hostContext().getSystemService(android.content.Context.ALARM_SERVICE);
        RuntimeReflection.field(AlarmManager.class, "mService").set(current, proxy);
        if (cache.get("alarm") != local || RuntimeReflection.get(current, "mService") != proxy) {
            throw new EngineException("AlarmManager boundary was not installed");
        }
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteAlarmManager";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        Bundle input = EngineProtocol.response(); input.putString("package", packageName); input.putString("operation", name);
        if (name.equals("set")) {
            if (args == null || args.length != 11 || !packageName.equals(args[0]) || !(args[6] instanceof PendingIntent)
                    || args[7] != null || args[8] != null || args[9] != null || args[10] != null) {
                throw new UnsupportedOperationException("Alarm listeners, WorkSource, alarm-clock and unknown set signatures are not supported");
            }
            input.putInt("type", (Integer) args[1]); input.putLong("trigger", (Long) args[2]);
            input.putLong("window", (Long) args[3]); input.putLong("interval", (Long) args[4]); input.putInt("flags", (Integer) args[5]);
            input.putBinder("sender", sender((PendingIntent) args[6])); call(input); return null;
        }
        if (name.equals("remove")) {
            if (args == null || args.length != 2 || !(args[0] instanceof PendingIntent) || args[1] != null) {
                throw new UnsupportedOperationException("Alarm listener cancellation is not supported");
            }
            input.putBinder("sender", sender((PendingIntent) args[0])); call(input); return null;
        }
        if (name.equals("removeAll") || name.equals("canScheduleExactAlarms") || name.equals("hasScheduleExactAlarm")) {
            int count = name.equals("hasScheduleExactAlarm") ? 2 : 1;
            if (args == null || args.length != count || !packageName.equals(args[0])
                    || count == 2 && !Integer.valueOf(0).equals(args[1])) throw new SecurityException("Alarm identity differs from the guest");
            Bundle result = call(input); return name.equals("removeAll") ? null : result.getBoolean("allowed");
        }
        if (name.equals("getConfigVersion") && (args == null || args.length == 0)) return call(input).getInt("version");
        throw new UnsupportedOperationException("Virtual AlarmManager method is not implemented: " + name);
    }

    private Bundle call(Bundle input) throws Exception {
        return EngineProtocol.request(endpoint, ServerAlarms.DESCRIPTOR, IBinder.FIRST_CALL_TRANSACTION, input);
    }
    private static IBinder sender(PendingIntent pending) throws Exception {
        return ServerPendingIntents.binderOf(RuntimeReflection.get(pending, "mTarget"));
    }
}
