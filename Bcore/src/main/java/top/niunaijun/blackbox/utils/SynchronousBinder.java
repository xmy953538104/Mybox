package top.niunaijun.blackbox.utils;

import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** Local client wrapper: never put this Java proxy into a Parcel. */
public final class SynchronousBinder {
    private SynchronousBinder() {}

    public static IBinder wrap(IBinder remote) {
        return (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(), new Class<?>[]{IBinder.class},
                (proxy, method, args) -> {
                    if ("queryLocalInterface".equals(method.getName())) return null;
                    if ("transact".equals(method.getName())) {
                        Parcel reply = (Parcel) args[2];
                        boolean temporary = reply == null;
                        if (temporary) reply = Parcel.obtain();
                        try {
                            boolean delivered = remote.transact((Integer) args[0], (Parcel) args[1], reply,
                                    (Integer) args[3] & ~IBinder.FLAG_ONEWAY);
                            if (temporary && reply.dataSize() > 0) {
                                reply.setDataPosition(0);
                                reply.readException();
                            }
                            return delivered;
                        } finally {
                            if (temporary) reply.recycle();
                        }
                    }
                    try { return method.invoke(remote, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
}
