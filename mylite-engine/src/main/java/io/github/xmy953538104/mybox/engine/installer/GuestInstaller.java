package io.github.xmy953538104.mybox.engine.installer;

import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Runtime interface adapter. Platform transaction numbers and system installation are never used. */
public final class GuestInstaller implements InvocationHandler {
    private static final String INSTALLER = "android.content.pm.IPackageInstaller";
    private static final String SESSION = "android.content.pm.IPackageInstallerSession";
    private final IVirtualInstallerService service;
    private final String guestPackage, hostPackage;
    private final int serverPid;
    private final IBinder lifetime = new Binder();
    private final Map<IBinder, IVirtualInstallerCallback> callbacks = new HashMap<>();
    private IBinder local;

    private GuestInstaller(IBinder endpoint, String guestPackage, String hostPackage) throws RemoteException {
        if (endpoint == null || !endpoint.isBinderAlive()) throw new IllegalStateException("Virtual installer service is unavailable");
        this.service = IVirtualInstallerService.Stub.asInterface(endpoint); this.guestPackage = guestPackage; this.hostPackage = hostPackage;
        Bundle identity = call("describe", request(), null); serverPid = identity.getInt("serverPid", -1);
        if (serverPid <= 0 || serverPid == Process.myPid() || identity.getInt("serverUid", -1) != Process.myUid()) {
            throw new SecurityException("Installer service is not a separate engine process");
        }
    }

    public static Object create(IBinder endpoint, String guestPackage, String hostPackage) throws ReflectiveOperationException, RemoteException {
        GuestInstaller handler = new GuestInstaller(endpoint, guestPackage, hostPackage);
        Object proxy = proxy(INSTALLER, handler); handler.local = binder(INSTALLER, proxy); return proxy;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        Object[] args = arguments == null ? new Object[0] : arguments;
        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args, "MyboxPackageInstaller");
        String name = method.getName(); if (name.equals("asBinder")) return local;
        Bundle input = request();
        switch (name) {
            case "createSession":
                if (args.length != 3 && args.length != 4) throw unsupported(name);
                claim((String) args[1]); user((Integer) args[args.length - 1]);
                InstallerWire.params(input, (PackageInstaller.SessionParams) args[0]);
                return call("create", input, null).getInt("id");
            case "openSession": {
                int id = integer(args, 0); Binder handle = new Binder(); input.putInt("id", id); input.putBinder("handle", handle);
                call("open", input, null);
                try { return new Session(id, handle).create(); }
                catch (ReflectiveOperationException failure) { call("close", input, null); throw failure; }
            }
            case "getSessionInfo": input.putInt("id", integer(args, 0)); return InstallerWire.info(call("info", input, null).getBundle("session"));
            case "getMySessions": claim((String) args[0]); user(integer(args, 1)); return InstallerWire.list(call("list", input, null));
            case "getAllSessions": user(integer(args, 0)); return InstallerWire.list(call("list", input, null));
            case "getStagedSessions": { Bundle empty = new Bundle(); empty.putParcelableArrayList("sessions", new java.util.ArrayList<Bundle>()); return InstallerWire.list(empty); }
            case "updateSessionAppLabel": input.putInt("id", integer(args, 0)); input.putString("label", (String) args[1]); call("label", input, null); return null;
            case "abandonSession": input.putInt("id", integer(args, 0)); call("abandon", input, null); return null;
            case "registerCallback": user(integer(args, 1)); register(args[0]); return null;
            case "unregisterCallback": unregister(args[0]); return null;
            default: throw unsupported(name);
        }
    }

    private final class Session implements InvocationHandler {
        final int id; final IBinder handle; IBinder localSession; boolean closed;
        Session(int id, IBinder handle) { this.id = id; this.handle = handle; }
        Object create() throws ReflectiveOperationException { Object value = proxy(SESSION, this); localSession = binder(SESSION, value); return value; }
        @Override public synchronized Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args, "MyboxInstallSession");
            String name = method.getName(); if (name.equals("asBinder")) return localSession;
            if (closed && !name.equals("close")) throw new IllegalStateException("Installation session handle is closed");
            Bundle input = request(); input.putInt("id", id); input.putBinder("handle", handle);
            switch (name) {
                case "setClientProgress": case "addClientProgress":
                    input.putFloat("progress", (Float) args[0]); input.putBoolean("add", name.equals("addClientProgress")); call("progress", input, null); return null;
                case "getNames": return call("names", input, null).getStringArray("names");
                case "openWrite": return service.openFile(guestPackage, id, (String) args[0], (Long) args[1], (Long) args[2], true, lifetime);
                case "openRead": return service.openFile(guestPackage, id, (String) args[0], 0, -1, false, lifetime);
                case "close": if (!closed) { call("close", input, null); closed = true; } return null;
                case "seal": call("seal", input, null); return null;
                case "abandon": call("abandon", input, null); return null;
                case "commit":
                    if (args.length < 1 || args.length > 2 || (args.length == 2 && Boolean.TRUE.equals(args[1]))) throw unsupported(name);
                    input.putParcelable("receiver", (IntentSender) args[0]); call("commit", input, null); return null;
                case "isMultiPackage": case "isStaged": return false;
                case "getChildSessionIds": return new int[0];
                case "getParentSessionId": return -1;
                default: throw unsupported(name);
            }
        }
    }

    private void register(Object callback) throws RemoteException {
        if (!(callback instanceof IInterface)) throw new IllegalArgumentException("Invalid installer callback");
        IBinder key = ((IInterface) callback).asBinder();
        synchronized (callbacks) {
            if (callbacks.containsKey(key)) return;
            if (callbacks.size() >= 8) throw new IllegalStateException("Too many installer callbacks");
            IVirtualInstallerCallback relay = new IVirtualInstallerCallback.Stub() {
                @Override public void onEvent(String event, Bundle value) throws RemoteException {
                    if (Binder.getCallingUid() != Process.myUid() || Binder.getCallingPid() != serverPid) throw new SecurityException("Installer event did not come from the engine server");
                    if (value == null) throw new IllegalArgumentException("Invalid installer event");
                    int id = value.getInt("id", -1);
                    try {
                        Class<?> contract = Class.forName("android.content.pm.IPackageInstallerCallback");
                        switch (event) {
                            case "created": contract.getMethod("onSessionCreated", int.class).invoke(callback, id); break;
                            case "badging": contract.getMethod("onSessionBadgingChanged", int.class).invoke(callback, id); break;
                            case "active": contract.getMethod("onSessionActiveChanged", int.class, boolean.class).invoke(callback, id, value.getBoolean("value")); break;
                            case "progress": contract.getMethod("onSessionProgressChanged", int.class, float.class).invoke(callback, id, value.getFloat("value")); break;
                            case "finished": contract.getMethod("onSessionFinished", int.class, boolean.class).invoke(callback, id, value.getBoolean("value")); break;
                            default: throw new IllegalArgumentException("Unknown installer event");
                        }
                    } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Platform installer callback is unavailable", failure); }
                }
            };
            call("register", request(), relay); callbacks.put(key, relay);
        }
    }
    private void unregister(Object callback) throws RemoteException {
        if (!(callback instanceof IInterface)) throw new IllegalArgumentException("Invalid installer callback");
        synchronized (callbacks) {
            IBinder key = ((IInterface) callback).asBinder(); IVirtualInstallerCallback relay = callbacks.get(key);
            if (relay == null) return;
            call("unregister", request(), relay); callbacks.remove(key);
        }
    }
    private Bundle request() { Bundle value = new Bundle(); value.putInt("version", InstallerWire.VERSION); value.putInt("userId", 0); value.putString("opPackage", guestPackage); return value; }
    private Bundle call(String operation, Bundle input, IVirtualInstallerCallback response) throws RemoteException { return service.execute(operation, input, response); }
    private void claim(String packageName) { if (!guestPackage.equals(packageName) && !hostPackage.equals(packageName)) throw new SecurityException("Installer package claim does not match guest"); }
    private static void user(int value) { if (value != 0) throw new SecurityException("Only virtual user 0 is supported"); }
    private static int integer(Object[] arguments, int at) { return (Integer) arguments[at]; }
    private static UnsupportedOperationException unsupported(String operation) { return new UnsupportedOperationException("Unsupported virtual PackageInstaller API: " + operation); }
    private static Object proxy(String contract, InvocationHandler handler) throws ReflectiveOperationException {
        Class<?> type = Class.forName(contract); return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static IBinder binder(String descriptor, Object proxy) {
        Binder value = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(descriptor); return true; }
                throw new UnsupportedOperationException("Raw platform installation transactions are not supported");
            }
        };
        value.attachInterface((IInterface) proxy, descriptor); return value;
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args, String label) {
        if (method.getName().equals("toString")) return label;
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        if (method.getName().equals("equals")) return proxy == args[0];
        throw unsupported(method.getName());
    }
}
