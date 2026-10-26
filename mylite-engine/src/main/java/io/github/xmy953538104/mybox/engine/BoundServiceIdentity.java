package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** A source-built service resolves this exact binding, never an unauthenticated WorkSource UID. */
public final class BoundServiceIdentity {
    private static final String GMS = "com.google.android.gms";
    private static final String REGISTER = "com.google.android.c2dm.intent.REGISTER";
    private static final String SERVICE = "org.microg.gms.gcm.PushRegisterService";
    private static final String MESSENGER = "android.os.IMessenger";
    private static final ThreadLocal<IBinder> CURRENT = new ThreadLocal<>();
    private BoundServiceIdentity() {}

    // Public reflection entry for source-built microG, through the existing host LoadedApk.
    public static IBinder currentLeaseV1() { return CURRENT.get(); }

    static boolean isRegistration(ComponentName component, Intent intent) {
        return component != null && GMS.equals(component.getPackageName()) && SERVICE.equals(component.getClassName())
                && intent != null && REGISTER.equals(intent.getAction());
    }
    static IBinder wrap(Engine engine, ComponentName component, Intent intent, IBinder original, IBinder lease) throws Exception {
        if (original == null || !isRegistration(component,intent)) return original;
        TrustedMicrog.require(engine.hostContext(),engine.packages().get(GMS));
        return wrapLocal(original,lease);
    }
    static IBinder wrapLocal(IBinder original, IBinder lease) throws RemoteException {
        if (!(original instanceof Binder) || lease == null || !MESSENGER.equals(original.getInterfaceDescriptor())) {
            throw new SecurityException("Bound identity requires a local Messenger and a server lease");
        }
        return new Binder() {
            { attachInterface(null,MESSENGER); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code != FIRST_CALL_TRANSACTION) return original.transact(code,data,reply,flags);
                EngineProtocol.requireCaller();
                if ((flags & FLAG_ONEWAY) == 0) throw new SecurityException("Messenger identity requires the actual one-way contract");
                IBinder previous = CURRENT.get();
                try {
                    CURRENT.set(lease);
                    // A local Binder executes on this same thread; the original Stub owns all parsing.
                    return original.transact(code,data,reply,flags);
                } finally {
                    if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
                }
            }
        };
    }
}
