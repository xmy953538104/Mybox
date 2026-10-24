package io.github.xmy953538104.mybox.engine.accounts;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/** Keeps authenticator callbacks attributable to their real guest PID. */
public final class GuestAuthenticatorBridge extends Binder {
    private static final ThreadLocal<Integer> AUTHORIZED_SERVER = new ThreadLocal<>();
    private final IBinder service;
    private final int serverPid;

    private GuestAuthenticatorBridge(IBinder service, int serverPid) {
        if (service == null || serverPid <= 0 || serverPid == Process.myPid()) {
            throw new IllegalArgumentException("A remote engine authenticator caller is required");
        }
        this.service = service; this.serverPid = serverPid;
        attachInterface(null, AccountWire.AUTHENTICATOR);
    }

    /** Only call after GuestAccounts.isAuthenticator(info) verifies the committed registration. */
    public static IBinder wrap(IBinder service, int trustedServerPid) {
        return new GuestAuthenticatorBridge(service, trustedServerPid);
    }

    public static boolean isAuthorizedServerCall(int pid, int uid) {
        Integer expected = AUTHORIZED_SERVER.get();
        return expected != null && expected == pid && uid == Process.myUid()
                && Binder.getCallingPid() == pid && Binder.getCallingUid() == uid;
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(AccountWire.AUTHENTICATOR); return true; }
        if (Binder.getCallingPid() != serverPid || Binder.getCallingUid() != Process.myUid()
                || (flags & IBinder.FLAG_ONEWAY) != 0) {
            throw new SecurityException("Authenticator requests require the synchronous engine server");
        }
        if (code < 1 || code > 14) return false;
        data.enforceInterface(AccountWire.AUTHENTICATOR);
        IBinder response = data.readStrongBinder();
        if (response == null) throw new IllegalArgumentException("Authenticator response is required");
        Binder relay = new Binder() {
            @Override protected boolean onTransact(int responseCode, Parcel input, Parcel output, int responseFlags) throws RemoteException {
                if (responseCode == INTERFACE_TRANSACTION) {
                    if (output != null) output.writeString(AccountWire.AUTHENTICATOR_RESPONSE); return true;
                }
                if (responseCode < 1 || responseCode > 3) return false;
                // A relay is a capability retained only inside this authenticated guest package.
                int callerPid = Binder.getCallingPid(), callerUid = Binder.getCallingUid();
                boolean local = callerUid == Process.myUid() && (callerPid == Process.myPid()
                        || isAuthorizedServerCall(callerPid, callerUid));
                if (!local && !GuestAccounts.isAuthenticatorPeer(callerUid, callerPid)) {
                    throw new SecurityException("Foreign authenticator relay caller");
                }
                input.enforceInterface(AccountWire.AUTHENTICATOR_RESPONSE);
                Parcel forwarded = Parcel.obtain(), answer = Parcel.obtain();
                long identity = Binder.clearCallingIdentity();
                try {
                    forwarded.writeInterfaceToken(AccountWire.AUTHENTICATOR_RESPONSE);
                    forwarded.appendFrom(input, input.dataPosition(), input.dataAvail());
                    if (!response.transact(responseCode, forwarded, answer, 0)) throw new RemoteException("Authenticator response was rejected");
                    if (answer.dataSize() > 0) answer.readException();
                    return true;
                } finally { Binder.restoreCallingIdentity(identity); answer.recycle(); forwarded.recycle(); }
            }
        };
        relay.attachInterface(null, AccountWire.AUTHENTICATOR_RESPONSE);
        Parcel forwarded = Parcel.obtain();
        Integer previous = AUTHORIZED_SERVER.get();
        try {
            forwarded.writeInterfaceToken(AccountWire.AUTHENTICATOR);
            forwarded.writeStrongBinder(relay);
            forwarded.appendFrom(data, data.dataPosition(), data.dataAvail());
            AUTHORIZED_SERVER.set(serverPid);
            return service.transact(code, forwarded, reply, 0);
        } finally {
            if (previous == null) AUTHORIZED_SERVER.remove(); else AUTHORIZED_SERVER.set(previous);
            forwarded.recycle();
        }
    }
}
