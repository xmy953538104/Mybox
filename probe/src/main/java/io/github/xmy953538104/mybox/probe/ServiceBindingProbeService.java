package io.github.xmy953538104.mybox.probe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

public final class ServiceBindingProbeService extends Service {
    static final String DESCRIPTOR = "mybox.probe.service-binding";
    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            String nonce = data.readString();
            reply.writeNoException();
            reply.writeString(nonce);
            reply.writeInt(Process.myPid());
            reply.writeString(getPackageName());
            return true;
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }
}
