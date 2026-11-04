package io.github.xmy953538104.mybox.engine;

import android.accounts.Account;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;

/** Private synchronous dispatch preserves a verifiable server PID around the real local adapter. */
final class SyncAdapterControl {
    static final String DESCRIPTOR = "io.github.xmy953538104.mybox.engine.SyncControl.v1";
    static final String ACTION = "android.content.SyncAdapter";
    private SyncAdapterControl() { }

    static void prepare(Engine engine, PackageInfo info, String process, long generation) {
        if (info.services == null) return;
        for (ServiceInfo service : info.services) if (process.equals(service.processName == null ? service.packageName : service.processName)
                && service.metaData != null && service.metaData.containsKey(ACTION)) {
            NativeBinderIdentity.Result result = NativeBinderIdentity.initializeGuest(engine.hostContext());
            EngineDiagnostics.event("sync", result.installed ? "identity_ready" : "identity_unavailable", generation, result.errno);
            return;
        }
    }

    private static Bundle authorization(GuestRuntime runtime, IBinder lease, long incarnation) {
        if (!runtime.hasPreparedServiceReservation()) throw new SecurityException("Sync process reservation expired");
        Bundle request = BoundServiceWire.operation("sync_control"); request.putLong("incarnation", incarnation);
        Bundle result = BoundServiceWire.call(lease, BoundServiceWire.LEASE, request);
        if (!result.getBoolean("active") || !BoundServiceWire.sameOwner(runtime.carrierOwner(), result.getBundle("owner"))
                || result.getInt("server_pid") <= 0 || result.getInt("server_pid") != runtime.serverPid())
            throw new SecurityException("Sync control lease owner changed");
        return result;
    }

    static IBinder wrap(GuestRuntime runtime, ComponentName component, Intent intent, IBinder original,
                        IBinder lease, long incarnation) throws Exception {
        if (original == null || intent == null || !ACTION.equals(intent.getAction())) return original;
        Bundle authorized = authorization(runtime, lease, incarnation);
        if (!authorized.getBoolean("internal")) return original;
        if (!(original instanceof Binder) || !"android.content.ISyncAdapter".equals(original.getInterfaceDescriptor())
                || !component.equals(intent.getComponent()))
            throw new SecurityException("Sync control requires this binding's local Android adapter");
        Class<?> contract = Class.forName("android.content.ISyncAdapter"), syncContext = Class.forName("android.content.ISyncContext");
        Object adapter = RuntimeReflection.invoke(null, Class.forName("android.content.ISyncAdapter$Stub")
                .getMethod("asInterface", IBinder.class), original);
        return new Binder() {
            { attachInterface(null, DESCRIPTOR); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(DESCRIPTOR); return true; }
                if (code != BoundServiceWire.CALL) return false;
                // Oneway Binder has PID zero; neither it nor WorkSource can authenticate a server.
                if ((flags & FLAG_ONEWAY) != 0 || reply == null) return false;
                Bundle result; long work = 0;
                try {
                    data.enforceInterface(DESCRIPTOR); EngineProtocol.requireCaller();
                    int rawUid = NativeBinderIdentity.rawCallingUid(), rawPid = NativeBinderIdentity.rawCallingPid();
                    if (rawUid != Process.myUid() || rawPid <= 0 || rawPid != runtime.serverPid())
                        throw new SecurityException("Sync control requires its physical engine server");
                    Bundle current = authorization(runtime, lease, incarnation);
                    if (!current.getBoolean("internal")) throw new SecurityException("Guest bindings cannot acquire sync control");
                    Bundle input = data.readBundle(getClass().getClassLoader());
                    if (input == null || data.dataAvail() != 0) throw new IllegalArgumentException("Invalid sync control envelope");
                    String operation = input.getString("operation");
                    if (!"start".equals(operation) && !"cancel".equals(operation)) throw new IllegalArgumentException("Unknown sync control operation");
                    work = input.getLong("work");
                    IBinder contextBinder = input.getBinder("context");
                    if (work <= 0 || contextBinder == null || !contextBinder.isBinderAlive()
                            || !"android.content.ISyncContext".equals(contextBinder.getInterfaceDescriptor()))
                        throw new SecurityException("Sync control has no live completion context");
                    Object context = RuntimeReflection.invoke(null, Class.forName("android.content.ISyncContext$Stub")
                            .getMethod("asInterface", IBinder.class), contextBinder);
                    Account account = input.getParcelable("account"); String authority = input.getString("authority");
                    Bundle extras = input.getBundle("extras");
                    if ("start".equals(operation)) {
                        if (account == null || authority == null || authority.isEmpty() || extras == null)
                            throw new IllegalArgumentException("Sync start fields are missing");
                        extras = ContentSyncState.extras(ContentSyncState.extras(extras));
                    }
                    EngineDiagnostics.event("sync", "control_raw_uid", work, rawUid);
                    EngineDiagnostics.event("sync", "control_raw_pid", work, rawPid);
                    try (NativeBinderIdentity.Scope scope = NativeBinderIdentity.enterSyncServer(rawPid,
                            current.getBundle("owner").getLong("generation"))) {
                        EngineDiagnostics.event("sync", "control_visible_uid", work, Binder.getCallingUid());
                        if ("start".equals(operation)) RuntimeReflection.invoke(adapter,
                                contract.getMethod("startSync", syncContext, String.class, Account.class, Bundle.class), context, authority, account, extras);
                        else RuntimeReflection.invoke(adapter, contract.getMethod("cancelSync", syncContext), context);
                    } finally {
                        EngineDiagnostics.event("sync", "control_restored_uid", work, Binder.getCallingUid());
                    }
                    EngineDiagnostics.event("sync", "start".equals(operation) ? "start_dispatched" : "cancel_dispatched", work, 0);
                    result = EngineProtocol.response();
                } catch (Exception failure) {
                    EngineDiagnostics.failure("sync", "control", work, failure); result = BoundServiceWire.failure(failure);
                }
                reply.writeNoException(); reply.writeBundle(result); return true;
            }
        };
    }

    static void dispatch(IBinder target, boolean cancel, long work, IBinder context,
                         String authority, Account account, Bundle extras) throws Exception {
        if (target == null || !target.isBinderAlive() || !DESCRIPTOR.equals(target.getInterfaceDescriptor()))
            throw new SecurityException("Sync adapter did not publish private server control");
        Bundle input = BoundServiceWire.operation(cancel ? "cancel" : "start");
        input.putLong("work", work); input.putBinder("context", context);
        if (!cancel) { input.putString("authority", authority); input.putParcelable("account", account); input.putBundle("extras", new Bundle(extras)); }
        BoundServiceWire.call(target, DESCRIPTOR, input);
    }
}
