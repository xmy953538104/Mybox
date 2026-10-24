package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.xmy953538104.mybox.engine.accounts.GuestAuthenticatorBridge;

/** Adapts framework component requests without changing the real Binder identity. */
final class GuestComponentCalls implements InvocationHandler {
    private final Engine engine;
    private final GuestRuntime runtime;
    private final Object original;
    private final Map<IBinder, List<Connection>> bindings = new HashMap<>();
    private final Set<IBinder> providerConnections = new HashSet<>();

    private static final class Connection {
        final ComponentName component;
        final Object callback;
        final IBinder owner;
        final IBinder.DeathRecipient death;
        volatile boolean active = true;
        Connection(ComponentName component, Object callback, IBinder owner, IBinder.DeathRecipient death) {
            this.component = component;
            this.callback = callback;
            this.owner = owner;
            this.death = death;
        }
    }

    private GuestComponentCalls(Engine engine, GuestRuntime runtime, Object original) {
        this.engine = engine;
        this.runtime = runtime;
        this.original = original;
    }

    static void install(Engine engine, GuestRuntime runtime) throws Exception {
        Class<?> manager = Class.forName("android.app.ActivityManager");
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(manager, "getService"));
        Object singleton = RuntimeReflection.field(manager, "IActivityManagerSingleton").get(null);
        Class<?> contract = Class.forName("android.app.IActivityManager");
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                new GuestComponentCalls(engine, runtime, original));
        RuntimeReflection.set(singleton, "mInstance", proxy);
        GuestServiceStub.install(runtime);
        GuestNotificationCalls.install(engine, runtime);
        GuestAlarmCalls.install(engine, runtime);
        GuestClipboardCalls.install(engine, runtime);
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("checkPermission") && "android.permission.ACCOUNT_MANAGER".equals(args[0])
                && GuestAuthenticatorBridge.isAuthorizedServerCall(((Number) args[1]).intValue(),
                ((Number) args[2]).intValue())) return PackageManager.PERMISSION_GRANTED;
        if (name.equals("getContentProvider")) return acquireProvider(method, args);
        if (name.equals("publishContentProviders")) {
            List<?> providers = (List<?>) args[1];
            List<Object> physical = new ArrayList<>();
            for (Object holder : providers) {
                ProviderInfo info = (ProviderInfo) RuntimeReflection.get(holder, "info");
                if (!runtime.packageInfo().packageName.equals(info.packageName)) physical.add(holder);
            }
            if (physical.isEmpty()) return null;
            args = args.clone();
            args[1] = physical;
        }
        if ((name.equals("removeContentProvider") || name.equals("refContentProvider")
                || name.equals("unstableProviderDied")) && args != null && args.length > 0) {
            synchronized (providerConnections) {
                if (providerConnections.contains(args[0])) {
                    if (name.equals("unstableProviderDied")) providerConnections.remove(args[0]);
                    return method.getReturnType() == boolean.class ? true : null;
                }
            }
        }
        if (name.equals("stopServiceToken")) {
            final Object[] parameters = args;
            Bundle result = runtime.onMain(() -> {
                Bundle answer = EngineProtocol.response();
                answer.putBoolean("owned", runtime.components().ownsToken((IBinder) parameters[1]));
                if (answer.getBoolean("owned")) answer.putBoolean("stopped",
                        runtime.components().stopToken((IBinder) parameters[1], ((Number) parameters[2]).intValue()));
                return answer;
            });
            if (result.getBoolean("owned")) return result.getBoolean("stopped");
        }
        if (name.equals("setServiceForeground")) {
            final Object[] parameters = args;
            Bundle result = runtime.onMain(() -> {
                Bundle answer = EngineProtocol.response();
                answer.putBoolean("owned", runtime.components().ownsToken((IBinder) parameters[1]));
                if (answer.getBoolean("owned")) runtime.components().foreground((IBinder) parameters[1],
                        ((Number) parameters[2]).intValue(), (android.app.Notification) parameters[3],
                        ((Number) parameters[4]).intValue(), parameters.length > 5 ? ((Number) parameters[5]).intValue() : -1);
                return answer;
            });
            if (result.getBoolean("owned")) return null;
        }
        if (name.equals("getForegroundServiceType")) {
            final Object[] parameters = args;
            Bundle result = runtime.onMain(() -> {
                Bundle answer = EngineProtocol.response();
                answer.putBoolean("owned", runtime.components().ownsToken((IBinder) parameters[1]));
                if (answer.getBoolean("owned")) answer.putInt("type", runtime.components().foregroundType((IBinder) parameters[1]));
                return answer;
            });
            if (result.getBoolean("owned")) return result.getInt("type");
        }
        if (name.equals("unbindService")) {
            IBinder callback = ((IInterface) args[0]).asBinder();
            List<Connection> removed;
            synchronized (bindings) { removed = bindings.remove(callback); }
            if (removed != null) {
                for (Connection connection : removed) {
                    connection.active = false;
                    connection.owner.unlinkToDeath(connection.death, 0);
                    if (connection.owner.isBinderAlive()) {
                        Bundle request = EngineProtocol.response();
                        request.putParcelable("component", connection.component);
                        request.putBinder("connection", callback);
                        send(EngineProtocol.UNBIND_SERVICE, request, service(connection.component));
                    }
                }
                return true;
            }
        }
        boolean start = name.equals("startService");
        boolean stop = name.equals("stopService");
        boolean bind = name.equals("bindService") || name.equals("bindServiceInstance") || name.equals("bindIsolatedService");
        if (start || stop || bind) {
            int intentIndex = bind ? 2 : 1;
            Intent intent = (Intent) args[intentIndex];
            ComponentName target = intent.getComponent();
            if (target == null) {
                android.content.pm.ResolveInfo resolved = engine.hostContext().getPackageManager().resolveService(intent, 0);
                if (resolved == null || resolved.serviceInfo == null) return start ? null : 0;
                target = new ComponentName(resolved.serviceInfo.packageName, resolved.serviceInfo.name);
                intent = new Intent(intent).setComponent(target);
                args = args.clone();
                args[intentIndex] = intent;
            }
            String targetPackage = target == null ? intent.getPackage() : target.getPackageName();
            boolean installed = targetPackage != null && engine.packages().get(targetPackage) != null;
            if (installed) {
                if (target == null) throw new EngineException("Virtual implicit Service resolution is not implemented");
                if (bind && !name.equals("bindService") && args[6] != null) {
                    throw new EngineException("Named isolated Service instances are not implemented");
                }
                ServiceInfo info = service(target);
                Bundle request = EngineProtocol.response();
                request.putParcelable("component", target);
                request.putParcelable("intent", new Intent(intent));
                if (start) request.putBoolean("require_foreground", Boolean.TRUE.equals(args[3]));
                if (bind) {
                    final ComponentName boundTarget = target;
                    Object callback = args[4];
                    IBinder callbackBinder = ((IInterface) callback).asBinder();
                    request.putBinder("connection", callbackBinder);
                    request.putLong("flags", ((Number) args[5]).longValue());
                    Bundle result = send(EngineProtocol.BIND_SERVICE, request, info);
                    if (!result.getBoolean("bound")) return 0;
                    IBinder owner = result.getBinder("owner");
                    final Connection[] holder = new Connection[1];
                    IBinder.DeathRecipient death = () -> {
                        if (holder[0].active) {
                            try { connected(callback, boundTarget, null, true); }
                            catch (Exception failure) { android.util.Log.w("MyboxEngine", "Service death callback failed", failure); }
                        }
                    };
                    Connection connection = new Connection(target, callback, owner, death);
                    holder[0] = connection;
                    owner.linkToDeath(death, 0);
                    synchronized (bindings) {
                        List<Connection> records = bindings.get(callbackBinder);
                        if (records == null) bindings.put(callbackBinder, records = new ArrayList<>());
                        records.add(connection);
                    }
                    connected(callback, target, result.getBinder("service"), false);
                    return 1;
                }
                Bundle result = send(start ? EngineProtocol.START_SERVICE : EngineProtocol.STOP_SERVICE, request, info);
                return start ? target : result.getBoolean("stopped") ? 1 : 0;
            }
            if (GuestPackageQueries.isGoogleIdentity(targetPackage)) {
                throw new EngineException("Google service is not installed in the container");
            }
            args = args.clone();
            if (start) args[4] = engine.hostContext().getPackageName();
            else if (bind) args[args.length - 2] = engine.hostContext().getPackageName();
        }
        return original(method, args);
    }

    private Bundle send(int operation, Bundle request, ServiceInfo info) throws Exception {
        if (runtime.packageInfo().packageName.equals(info.packageName) && runtime.ownsProcess(info.processName)) {
            Bundle result = runtime.onMain(() -> runtime.components().dispatch(operation, request));
            result.putBinder("owner", runtime.clientBinder());
            return result;
        }
        return EngineProtocol.request(engine.server(), EngineProtocol.SERVER, operation, request);
    }

    private ServiceInfo service(ComponentName component) throws Exception {
        PackageInfo pkg = engine.packages().getPackageInfo(component.getPackageName(), PackageManager.GET_SERVICES);
        if (pkg != null && pkg.services != null) {
            for (ServiceInfo info : pkg.services) if (component.getClassName().equals(info.name)
                    && info.enabled && info.applicationInfo.enabled) {
                if ((info.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0) {
                    throw new EngineException("Isolated guest Services are not implemented");
                }
                return info;
            }
        }
        throw new EngineException("Guest Service is absent or disabled: " + component);
    }

    private Object acquireProvider(Method method, Object[] args) throws Throwable {
        int authorityIndex = args.length - 3;
        String authority = (String) args[authorityIndex];
        ProviderInfo selected = engine.hostContext().getPackageManager().resolveContentProvider(authority, 0);
        if (selected != null && engine.packages().get(selected.packageName) != null) {
            Bundle request = EngineProtocol.response();
            request.putString("authority", authority);
            Bundle result;
            if (runtime.packageInfo().packageName.equals(selected.packageName) && runtime.ownsProcess(selected.processName)) {
                return runtime.localProvider(selected, authority, ((Number) args[authorityIndex + 1]).intValue());
            } else result = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.ACQUIRE_PROVIDER, request);
            IBinder binder = result.getBinder("provider");
            if (binder == null || !binder.isBinderAlive()) throw new EngineException("Guest provider Binder is unavailable");
            ProviderInfo info = result.getParcelable("info");
            Object holder = Class.forName("android.app.ContentProviderHolder").getConstructor(ProviderInfo.class).newInstance(info);
            Object provider = RuntimeReflection.invoke(null,
                    RuntimeReflection.method(Class.forName("android.content.ContentProviderNative"), "asInterface", IBinder.class), binder);
            RuntimeReflection.set(holder, "provider", provider);
            RuntimeReflection.set(holder, "noReleaseNeeded", true);
            IBinder connection = new android.os.Binder();
            RuntimeReflection.set(holder, "connection", connection);
            synchronized (providerConnections) { providerConnections.add(connection); }
            return holder;
        }
        if (selected == null || GuestPackageQueries.isGoogleIdentity(selected.packageName)) return null;
        Object[] physical = args.clone();
        if (authorityIndex > 1) physical[1] = engine.hostContext().getPackageName();
        Object holder = original(method, physical);
        if (holder != null) {
            Object provider = RuntimeReflection.get(holder, "provider");
            RuntimeReflection.set(holder, "provider", PhysicalProviderCalls.wrap(engine.hostContext(),
                    runtime.packageInfo().packageName, provider));
        }
        return holder;
    }

    private static void connected(Object callback, ComponentName component, IBinder service, boolean dead) throws Exception {
        RuntimeReflection.invoke(callback, RuntimeReflection.method(Class.forName("android.app.IServiceConnection"),
                "connected", ComponentName.class, IBinder.class, boolean.class), component, service, dead);
    }

    private Object original(Method method, Object[] args) throws Throwable {
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
