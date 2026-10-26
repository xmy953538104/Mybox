package io.github.xmy953538104.mybox.engine;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

/** Debug-only regression for Android physical ServiceRecord token semantics. */
final class BoundCarrierLifecycle extends Binder {
    static final String DESCRIPTOR = "mybox.synthetic.BoundLifecycle.v1";
    static final String EXTRA_OWNER = "mybox.synthetic.carrier.owner";
    private final Object activityManager;
    private final android.content.ComponentName component;
    private final IBinder actualToken;
    private final Bundle owner;
    private final int serverPid;

    BoundCarrierLifecycle(Service instance, Bundle owner, int serverPid) {
        if (!BoundServiceWire.sameOwner(owner, owner) || serverPid <= 0) throw new IllegalArgumentException("Missing carrier owner seal");
        this.owner = new Bundle(owner); this.serverPid = serverPid;
        try {
            Object attached = RuntimeReflection.get(instance, "mActivityManager");
            // All existing Activity/Broadcast/PendingIntent/Component proxies pass asBinder through unchanged.
            IBinder physical = ((android.os.IInterface) attached).asBinder();
            if (!"android.app.IActivityManager".equals(physical.getInterfaceDescriptor())) throw new SecurityException("Unexpected physical ActivityManager Binder");
            activityManager = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName("android.app.IActivityManager$Stub"),
                    "asInterface", IBinder.class), physical);
            actualToken = (IBinder) RuntimeReflection.get(instance, "mToken");
            component = new android.content.ComponentName(instance, (String) RuntimeReflection.get(instance, "mClassName"));
            if (actualToken == null || activityManager == null) throw new IllegalStateException("Physical Service is not attached");
        } catch (Exception failure) { throw new EngineException("Cannot capture the physical Service token", failure); }
        attachInterface(null, BoundCarrierLifecycle.DESCRIPTOR);
    }
    static boolean acceptStart(Service instance, Bundle currentOwner, boolean prepared, Intent intent, int startId) {
        if (prepared && BoundServiceWire.sameOwner(currentOwner, intent == null ? null : intent.getBundleExtra(BoundCarrierLifecycle.EXTRA_OWNER))) return true;
        // A stale request must never clear a prepared new owner's latest started state or pending binds.
        if (!prepared) instance.stopSelfResult(startId);
        return false;
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == IBinder.INTERFACE_TRANSACTION) { reply.writeString(BoundCarrierLifecycle.DESCRIPTOR); return true; }
        if (code != BoundServiceWire.CALL) return false;
        Bundle result;
        try {
            EngineProtocol.requireCaller(); data.enforceInterface(BoundCarrierLifecycle.DESCRIPTOR);
            Bundle input = data.readBundle(getClass().getClassLoader());
            if (Binder.getCallingPid() != serverPid || !BoundServiceWire.sameOwner(owner, input.getBundle("owner")))
                throw new SecurityException("Carrier stop belongs to another owner");
            if (!"stop".equals(input.getString("operation"))) throw new IllegalArgumentException("Unknown carrier lifecycle operation");
            long identity = Binder.clearCallingIdentity();
            try {
                RuntimeReflection.invoke(activityManager, RuntimeReflection.method(Class.forName("android.app.IActivityManager"),
                        "stopServiceToken", android.content.ComponentName.class, IBinder.class, int.class), component, actualToken, -1);
            } // Actual old mToken on the raw OS Binder; no guest-main dispatch.
            finally { Binder.restoreCallingIdentity(identity); }
            result = EngineProtocol.response();
        } catch (Exception failure) { result = BoundServiceWire.failure(failure); }
        reply.writeNoException(); reply.writeBundle(result); return true;
    }
}
