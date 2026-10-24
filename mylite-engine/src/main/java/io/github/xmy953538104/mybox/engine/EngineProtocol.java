package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.app.ActivityManager;
import android.content.Context;

/** Versioned, app-private IPC; independent of the original binary's Binder implementation. */
final class EngineProtocol {
    static final int VERSION = 1;
    static final int SLOT_COUNT = 4;
    static final String SERVER = "io.github.xmy953538104.mybox.engine.Server.v1";
    static final String GUEST = "io.github.xmy953538104.mybox.engine.Guest.v1";
    static final int ACQUIRE = IBinder.FIRST_CALL_TRANSACTION;
    static final int DESCRIBE = IBinder.FIRST_CALL_TRANSACTION;
    static final int BIND_APPLICATION = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int START_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int BIND_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int UNBIND_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int STOP_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int ACQUIRE_PROVIDER = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int GET_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 7;
    static final int REGISTER_RECEIVER = IBinder.FIRST_CALL_TRANSACTION + 8;
    static final int UNREGISTER_RECEIVER = IBinder.FIRST_CALL_TRANSACTION + 9;
    static final int SEND_BROADCAST = IBinder.FIRST_CALL_TRANSACTION + 10;
    static final int DELIVER_BROADCAST = IBinder.FIRST_CALL_TRANSACTION + 11;
    static final int PREPARE_ACTIVITY = IBinder.FIRST_CALL_TRANSACTION + 12;
    static final int PENDING_INTENT = IBinder.FIRST_CALL_TRANSACTION + 13;
    static final int DELIVER_RECEIVER = IBinder.FIRST_CALL_TRANSACTION + 14;
    static final int HOST_ACTIVITY = IBinder.FIRST_CALL_TRANSACTION + 15;
    static final String LAUNCH = "io.github.xmy953538104.mybox.engine.LAUNCH";

    static void requireCaller() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Engine IPC is restricted to its owning app UID");
        }
    }

    static void requireProcess(Context context, String processName) {
        requireCaller();
        int pid = Binder.getCallingPid();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pid == pid && process.uid == Process.myUid() && processName.equals(process.processName)) return;
            }
        }
        throw new SecurityException("Caller is not the required engine process");
    }

    static Bundle request(IBinder binder, String descriptor, int code, Bundle input) {
        if (binder == null || !binder.isBinderAlive()) {
            throw new EngineException("Engine service is unavailable: " + descriptor);
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeBundle(input);
            if (!binder.transact(code, data, reply, 0)) {
                throw new EngineException("Engine protocol operation is not supported: " + code);
            }
            reply.readException();
            Bundle result = reply.readBundle(EngineProtocol.class.getClassLoader());
            if (result == null || result.getInt("protocol") != VERSION) {
                throw new EngineException("Engine protocol response is missing or incompatible");
            }
            String error = result.getString("error");
            if (error != null) throw new EngineException(error);
            return result;
        } catch (RemoteException e) {
            throw new EngineException("Engine process disconnected", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static Bundle response() {
        Bundle result = new Bundle();
        result.putInt("protocol", VERSION);
        return result;
    }

    static Bundle failure(Throwable error) {
        Bundle result = response();
        result.putString("error", error.getClass().getSimpleName() + ": "
                + (error.getMessage() == null ? "operation failed" : error.getMessage()));
        return result;
    }

    private EngineProtocol() { }
}
