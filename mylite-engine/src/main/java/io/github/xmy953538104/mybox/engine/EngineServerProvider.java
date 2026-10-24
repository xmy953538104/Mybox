package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import io.github.xmy953538104.mybox.engine.accounts.AccountService;
import io.github.xmy953538104.mybox.engine.accounts.CallerResolver;
import io.github.xmy953538104.mybox.engine.installer.VirtualInstallerService;

/** Bootstrap authority for the engine's dedicated server process. */
public final class EngineServerProvider extends EngineProvider {
    private static volatile ProcessRegistry registry;
    private ProcessRegistry processes;
    private ComponentRouter components;
    private ServerBroadcasts broadcasts;
    private AccountService accounts;
    private ServerPendingIntents pendingIntents;
    private VirtualInstallerService installer;
    private ServerActivities activities;
    private ServerNotifications notifications;
    private ServerContentObservers contentObservers;
    private ServerComponentStates componentStates;
    private ServerJobs jobs;
    private static volatile ServerAlarms osAlarms;
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
                    result = EngineProtocol.response();
                    String service = input == null ? null : input.getString("service");
                    if ("accounts".equals(service)) result.putBinder("service", accounts.asBinder());
                    else if ("installer".equals(service)) result.putBinder("service", installer.asBinder());
                    else if ("notifications".equals(service)) result.putBinder("service", notifications);
                    else if ("alarms".equals(service)) result.putBinder("service", osAlarms());
                    else if ("content_observers".equals(service)) result.putBinder("service", contentObservers);
                    else if ("component_states".equals(service)) result.putBinder("service", componentStates);
                    else if ("jobs".equals(service)) result.putBinder("service", jobs);
                    else throw new EngineException("Unknown engine service");
                } else if (code >= EngineProtocol.REGISTER_RECEIVER && code <= EngineProtocol.SEND_BROADCAST) {
                    result = broadcasts.route(code, input, resolveCaller());
                } else if (code == EngineProtocol.PREPARE_ACTIVITY) {
                    result = activities.guest(input, processes.identity(Binder.getCallingUid(), Binder.getCallingPid()));
                } else if (code == EngineProtocol.HOST_ACTIVITY) {
                    EngineProtocol.requireProcess(getContext(), getContext().getPackageName());
                    result = activities.host(input, Binder.getCallingPid());
                } else if (code == EngineProtocol.PENDING_INTENT) {
                    result = pendingIntents.route(input, resolveCaller());
                } else if (code == EngineProtocol.JOB_SERVICE) {
                    EngineProtocol.requireProcess(getContext(), getContext().getPackageName() + ":engine_jobs");
                    result = jobs.dispatch(input);
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
        processes = new ProcessRegistry(Engine.get(), binder);
        registry = processes;
        ServerAccountUiTickets accountUi = new ServerAccountUiTickets(Engine.get(), processes);
        activities = new ServerActivities(Engine.get(), processes, accountUi);
        processes.setDeathListener(activities);
        components = new ComponentRouter(Engine.get(), processes);
        broadcasts = new ServerBroadcasts(Engine.get(), processes);
        try {
            pendingIntents = new ServerPendingIntents(Engine.get(), broadcasts, components);
            osAlarms = new ServerAlarms(Engine.get(), pendingIntents, broadcasts);
            notifications = new ServerNotifications(Engine.get());
            contentObservers = new ServerContentObservers(Engine.get());
            componentStates = new ServerComponentStates(Engine.get());
            jobs = new ServerJobs(Engine.get(), pendingIntents.osStore(), components, processes);
            installer = new VirtualInstallerService(getContext(), Engine.get().packages(), claimed -> {
                CallerIdentity caller = resolveCaller();
                if (!caller.packageName.equals(claimed)) throw new SecurityException("Claimed installer package differs from the registered process");
                return caller;
            }, new InstallerPolicy(Engine.get()), (caller, sender) -> {
                try {
                    ServerPendingIntents.StatusDelivery prepared = pendingIntents.prepareStatus(caller, sender);
                    return prepared::send;
                } catch (SecurityException invalid) { throw invalid; }
                catch (Exception invalid) { throw new EngineException("Virtual installer status capability is unavailable", invalid); }
            });
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
            }, accountUi);
        } catch (Exception failure) {
            throw new EngineException("Virtual engine services could not initialize", failure);
        }
        binder.attachInterface(null, EngineProtocol.SERVER);
        return true;
    }

    public static CallerIdentity resolveCaller() {
        return resolveRegisteredCaller(Binder.getCallingUid(), Binder.getCallingPid());
    }

    static ServerAlarms osAlarms() {
        ServerAlarms current = osAlarms;
        if (current == null) throw new EngineException("OS alarm server is unavailable");
        return current;
    }

    static ProcessRegistry.Identity registeredProcess(int uid, int pid) {
        try {
            if (registry == null) throw new SecurityException("Process registry is unavailable");
            return registry.identity(uid, pid);
        } catch (Exception failure) { throw new SecurityException("Registered process identity is unavailable", failure); }
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
