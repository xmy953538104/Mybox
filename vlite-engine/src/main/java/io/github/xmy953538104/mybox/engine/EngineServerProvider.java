package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import io.github.xmy953538104.mybox.engine.accounts.AccountService;
import io.github.xmy953538104.mybox.engine.accounts.CallerResolver;

/** Bootstrap authority for the engine's dedicated server process. */
public final class EngineServerProvider extends EngineProvider {
    private static volatile ProcessRegistry registry;
    private ProcessRegistry processes;
    private ComponentRouter components;
    private ServerBroadcasts broadcasts;
    private AccountService accounts;
    private ServerPendingIntents pendingIntents;
    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(EngineProtocol.SERVER);
                return true;
            }
            EngineProtocol.requireCaller();
            data.enforceInterface(EngineProtocol.SERVER);
            Bundle result;
            try {
                Bundle input = data.readBundle(getClass().getClassLoader());
                if (code == EngineProtocol.ACQUIRE) {
                    EngineProtocol.requireProcess(getContext(), getContext().getPackageName());
                    result = processes.acquire(input);
                } else if (code >= EngineProtocol.START_SERVICE && code <= EngineProtocol.ACQUIRE_PROVIDER) {
                    result = components.route(code, input, resolveCaller());
                } else if (code == EngineProtocol.GET_SERVICE) {
                    resolveCaller();
                    if (input == null || !"accounts".equals(input.getString("service"))) {
                        throw new EngineException("Unknown engine service");
                    }
                    result = EngineProtocol.response();
                    result.putBinder("service", accounts.asBinder());
                } else if (code >= EngineProtocol.REGISTER_RECEIVER && code <= EngineProtocol.SEND_BROADCAST) {
                    result = broadcasts.route(code, input, resolveCaller());
                } else if (code == EngineProtocol.PREPARE_ACTIVITY) {
                    result = prepareActivity(input, resolveCaller());
                } else if (code == EngineProtocol.PENDING_INTENT) {
                    result = pendingIntents.route(input, resolveCaller());
                } else return false;
            } catch (Exception e) {
                result = EngineProtocol.failure(e);
            }
            reply.writeNoException();
            reply.writeBundle(result);
            return true;
        }
    };

    @Override public boolean onCreate() {
        super.onCreate();
        processes = new ProcessRegistry(Engine.get());
        registry = processes;
        components = new ComponentRouter(Engine.get(), processes);
        broadcasts = new ServerBroadcasts(Engine.get(), processes);
        pendingIntents = new ServerPendingIntents(Engine.get(), broadcasts);
        try {
            accounts = new AccountService(getContext(), Engine.get().packages(), new CallerResolver() {
                @Override public CallerIdentity resolve(String claimedOpPackage) {
                    CallerIdentity caller = resolveCaller();
                    if (claimedOpPackage != null && !caller.packageName.equals(claimedOpPackage)) {
                        throw new SecurityException("Claimed account package differs from the registered process");
                    }
                    return caller;
                }
                @Override public CallerIdentity resolvePeer(int realUid, int realPid) {
                    return resolveRegisteredCaller(realUid, realPid);
                }
            }, new ServerAuthenticatorRouter(components), broadcasts, installed -> {
                try { TrustedMicrog.require(getContext(), installed); return true; }
                catch (SecurityException untrusted) { return false; }
            });
        } catch (java.io.IOException failure) {
            throw new EngineException("Virtual account service could not initialize", failure);
        }
        binder.attachInterface(null, EngineProtocol.SERVER);
        return true;
    }

    private Bundle prepareActivity(Bundle input, CallerIdentity caller) throws Exception {
        android.content.ComponentName component = input == null ? null : input.getParcelable("component");
        if (component == null || !caller.packageName.equals(component.getPackageName())) {
            throw new SecurityException("Only same-package Activity preparation is authorized");
        }
        android.content.pm.PackageInfo pkg = Engine.get().packages().getPackageInfo(caller.packageName,
                android.content.pm.PackageManager.GET_ACTIVITIES);
        if (pkg != null && pkg.activities != null) {
            for (android.content.pm.ActivityInfo activity : pkg.activities) {
                if (component.getClassName().equals(activity.name) && activity.enabled && activity.applicationInfo.enabled) {
                    if (activity.launchMode != android.content.pm.ActivityInfo.LAUNCH_MULTIPLE) {
                        throw new EngineException("Non-standard guest Activity launch modes are not implemented");
                    }
                    Bundle request = EngineProtocol.response();
                    request.putString("package", activity.packageName);
                    request.putString("process", activity.processName == null ? activity.packageName : activity.processName);
                    Bundle process = processes.acquire(request);
                    process.remove("client");
                    process.putString("package", activity.packageName);
                    process.putString("activity", activity.name);
                    return process;
                }
            }
        }
        throw new EngineException("Guest Activity is absent or disabled: " + component);
    }

    public static CallerIdentity resolveCaller() {
        return resolveRegisteredCaller(Binder.getCallingUid(), Binder.getCallingPid());
    }

    /** Internal server bridge; callers must authenticate any forwarded PID before invoking this. */
    public static CallerIdentity resolveRegisteredCaller(int realUid, int realPid) {
        ProcessRegistry current = registry;
        if (current == null) throw new SecurityException("Guest identity resolution requires the engine server");
        try {
            return current.resolveCaller(realUid, realPid);
        } catch (SecurityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Guest identity could not be verified", failure);
        }
    }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        EngineProtocol.requireCaller();
        if (!"connect".equals(method)) throw new IllegalArgumentException("Unknown server operation");
        Bundle result = EngineProtocol.response();
        result.putBinder("server", binder);
        return result;
    }
}
