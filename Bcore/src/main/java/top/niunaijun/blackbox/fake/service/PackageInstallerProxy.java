package top.niunaijun.blackbox.fake.service;

import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.IInterface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;

/** Uses the device's actual hidden interface, so added AIDL parameters do not shift old transactions. */
public final class PackageInstallerProxy implements InvocationHandler {
    private final int sessionId;
    private final Binder binder = new Binder();

    private PackageInstallerProxy(int sessionId) { this.sessionId = sessionId; }

    public static Object create(Class<?> api) { return create(api, -1); }

    private static Object create(Class<?> api, int id) {
        PackageInstallerProxy handler = new PackageInstallerProxy(id);
        Object proxy = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, handler);
        handler.binder.attachInterface((IInterface) proxy, api.getName());
        return proxy;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String operation = method.getName();
        if ("asBinder".equals(operation)) return binder;
        if (method.getDeclaringClass() == Object.class) {
            if ("toString".equals(operation)) return "MyboxPackageInstaller(" + sessionId + ")";
            if ("hashCode".equals(operation)) return System.identityHashCode(proxy);
            if ("equals".equals(operation)) return proxy == args[0];
        }
        Bundle request = new Bundle();
        if (sessionId >= 0) request.putInt("id", sessionId);
        switch (operation) {
            case "createSession":
                request.putParcelable("params", (PackageInstaller.SessionParams) args[0]);
                return call(operation, request).getInt("id");
            case "openSession": {
                int id = (Integer) args[0];
                request.putInt("id", id);
                call(operation, request);
                return create(method.getReturnType(), id);
            }
            case "getSessionInfo":
                request.putInt("id", (Integer) args[0]);
                return call(operation, request).getParcelable("info");
            case "getAllSessions":
            case "getMySessions":
            case "getStagedSessions": {
                ArrayList<PackageInstaller.SessionInfo> sessions = call(operation, request).getParcelableArrayList("sessions");
                return ParceledListSliceCompat.create(sessions);
            }
            case "registerCallback":
            case "unregisterCallback":
                request.putBinder("callback", ((IInterface) args[0]).asBinder());
                call(operation, request);
                return null;
            case "updateSessionAppIcon":
                request.putInt("id", (Integer) args[0]);
                request.putParcelable("icon", (Bitmap) args[1]);
                call(operation, request);
                return null;
            case "updateSessionAppLabel":
                request.putInt("id", (Integer) args[0]);
                request.putString("label", (String) args[1]);
                call(operation, request);
                return null;
            case "abandonSession":
                request.putInt("id", (Integer) args[0]);
                call(operation, request);
                return null;
            case "setClientProgress":
            case "addClientProgress":
                request.putFloat("progress", (Float) args[0]);
                call(operation, request);
                return null;
            case "getNames":
                return call(operation, request).getStringArray("names");
            case "openWrite":
                return BlackBoxCore.getBPackageManager().openSessionWrite(sessionId, (String) args[0],
                        (Long) args[1], (Long) args[2], usesFileBridge());
            case "openRead":
                return BlackBoxCore.getBPackageManager().openSessionRead(sessionId, (String) args[0]);
            case "removeSplit":
                request.putString("name", (String) args[0]);
                call(operation, request);
                return null;
            case "commit":
                if (args.length > 1 && Boolean.TRUE.equals(args[1])) {
                    throw new UnsupportedOperationException("Transferred installer sessions are not supported");
                }
                request.putParcelable("receiver", (IntentSender) args[0]);
                call(operation, request);
                return null;
            case "close":
            case "abandon":
                call(operation, request);
                return null;
            case "isMultiPackage":
            case "isStaged":
                return false;
            case "getChildSessionIds":
                return new int[0];
            case "getParentSessionId":
                return -1;
            case "getDataLoaderParams":
                return null;
            default:
                throw new UnsupportedOperationException("Unsupported virtual PackageInstaller API: " + operation);
        }
    }

    private static Bundle call(String operation, Bundle request) throws android.os.RemoteException {
        Bundle result = BlackBoxCore.getBPackageManager().installerCall(operation, request);
        if (result == null) throw new IllegalStateException("Installer returned no result");
        result.setClassLoader(PackageInstaller.SessionInfo.class.getClassLoader());
        return result;
    }

    private static boolean usesFileBridge() {
        try {
            Field enabled = PackageInstaller.class.getDeclaredField("ENABLE_REVOCABLE_FD");
            enabled.setAccessible(true);
            return !enabled.getBoolean(null);
        } catch (NoSuchFieldException oldAndroid) { return true; }
        catch (Exception e) { throw new IllegalStateException("Cannot determine PackageInstaller write protocol", e); }
    }
}
