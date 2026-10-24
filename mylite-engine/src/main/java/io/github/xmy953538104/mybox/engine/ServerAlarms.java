package io.github.xmy953538104.mybox.engine;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;

/** Narrow host AlarmManager boundary. Only registered broadcast capabilities reach the OS. */
final class ServerAlarms extends Binder {
    static final String DESCRIPTOR = EngineProtocol.SERVER + ".Alarms.v1";
    private final Engine engine;
    private final ServerPendingIntents pending;
    private final ServerBroadcasts broadcasts;
    private final Object system;
    private final Class<?> contract;

    ServerAlarms(Engine engine, ServerPendingIntents pending, ServerBroadcasts broadcasts) throws Exception {
        this.engine = engine; this.pending = pending; this.broadcasts = broadcasts;
        AlarmManager manager = (AlarmManager) engine.hostContext().getSystemService(android.content.Context.ALARM_SERVICE);
        system = RuntimeReflection.get(manager, "mService");
        contract = Class.forName("android.app.IAlarmManager");
        attachInterface(null, DESCRIPTOR);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        if (code != FIRST_CALL_TRANSACTION) return false;
        data.enforceInterface(DESCRIPTOR);
        try {
            CallerIdentity caller = EngineServerProvider.resolveCaller();
            Bundle input = data.readBundle(getClass().getClassLoader());
            if (input == null || input.getInt("protocol") != EngineProtocol.VERSION
                    || !caller.packageName.equals(input.getString("package")) || caller.userId != 0) {
                throw new SecurityException("Alarm caller is not the registered user-0 guest");
            }
            Bundle result = route(input, caller);
            reply.writeNoException(); reply.writeBundle(result);
        } catch (SecurityException | IllegalArgumentException | UnsupportedOperationException failure) {
            reply.writeException(failure);
        } catch (Exception failure) {
            reply.writeNoException(); reply.writeBundle(EngineProtocol.failure(failure));
        }
        return true;
    }

    private Bundle route(Bundle input, CallerIdentity caller) throws Exception {
        Bundle result = EngineProtocol.response();
        String operation = input.getString("operation");
        if ("set".equals(operation)) {
            int type = input.getInt("type", -1), flags = input.getInt("flags");
            long trigger = input.getLong("trigger", -1), window = input.getLong("window"), interval = input.getLong("interval");
            if (type < AlarmManager.RTC_WAKEUP || type > AlarmManager.ELAPSED_REALTIME
                    || trigger < 0 || window < -1 || interval != 0 || (flags & ~(1 | 4)) != 0) {
                throw new UnsupportedOperationException("Only one-time RTC/elapsed alarms with standard exact/window/allow-idle flags are supported");
            }
            pending.alarm(caller, input.getBinder("sender"), "set", physical -> systemCall("set",
                    new Class<?>[]{String.class, int.class, long.class, long.class, long.class, int.class,
                            PendingIntent.class, Class.forName("android.app.IAlarmListener"), String.class,
                            android.os.WorkSource.class, AlarmManager.AlarmClockInfo.class},
                    engine.hostContext().getPackageName(), type, trigger, window, 0L, flags, physical, null, null, null, null));
        } else if ("remove".equals(operation)) {
            pending.alarm(caller, input.getBinder("sender"), "remove", this::remove);
        } else if ("removeAll".equals(operation)) {
            pending.removeAlarms(caller, this::remove);
        } else if ("canScheduleExactAlarms".equals(operation)) {
            result.putBoolean("allowed", (Boolean) systemCall(operation, new Class<?>[]{String.class}, engine.hostContext().getPackageName()));
        } else if ("hasScheduleExactAlarm".equals(operation)) {
            result.putBoolean("allowed", (Boolean) systemCall(operation, new Class<?>[]{String.class, int.class}, engine.hostContext().getPackageName(), android.os.Process.myUid() / 100000));
        } else if ("getConfigVersion".equals(operation)) {
            result.putInt("version", ((Number) systemCall(operation, new Class<?>[0])).intValue());
        } else throw new UnsupportedOperationException("Alarm method is not implemented: " + operation);
        return result;
    }

    private void remove(PendingIntent physical) throws Exception {
        systemCall("remove", new Class<?>[]{PendingIntent.class, Class.forName("android.app.IAlarmListener")}, physical, null);
    }
    private Object systemCall(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = RuntimeReflection.method(contract, name, types);
        long identity = Binder.clearCallingIdentity();
        try { return RuntimeReflection.invoke(system, method, args); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    OsPendingStore.Snapshot verify(Intent intent) throws Exception { return pending.verifyOsDelivery(intent); }
    Bundle deliver(OsPendingStore.Snapshot snapshot, AlarmDeliveryTicket ticket, boolean ordered, int initialCode) throws Exception {
        return broadcasts.sendOsSnapshot(snapshot, ticket, ordered, initialCode);
    }
}
