package io.github.xmy953538104.mybox.engine;

import android.app.Application;
import android.app.Service;
import android.app.Notification;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import io.github.xmy953538104.mybox.engine.accounts.GuestAccounts;
import io.github.xmy953538104.mybox.engine.accounts.GuestAuthenticatorBridge;

/** Guest component objects live on their owning process's main thread. */
final class GuestComponents {
    private final GuestRuntime runtime;
    private final Map<ComponentName, ServiceRecord> services = new HashMap<>();
    private final ServiceRecord[] physicalSlots = new ServiceRecord[4];
    private long nextIncarnation;

    private static final class Binding {
        final Intent intent;
        final Intent.FilterComparison filter;
        final IBinder connection;
        final IBinder.DeathRecipient death;
        final boolean autoCreate;
        Binding(Intent intent, IBinder connection, IBinder.DeathRecipient death, boolean autoCreate) {
            this.intent = intent;
            filter = new Intent.FilterComparison(intent);
            this.connection = connection;
            this.death = death; this.autoCreate = autoCreate;
        }
    }

    private static final class ServiceRecord {
        final ComponentName component;
        final ServiceInfo info;
        Service service;
        final Binder token = new Binder();
        final Map<IBinder, Binding> connections = new HashMap<>();
        final Map<Intent.FilterComparison, IBinder> published = new HashMap<>();
        final Map<Intent.FilterComparison, Boolean> rebind = new HashMap<>();
        long incarnation;
        int executingBindings;
        int startId;
        boolean started;
        int slot = -1;
        String lease;
        int requestId;
        int physicalStartId;
        boolean stopping;
        GuestServiceStub physical;
        final Map<Integer, Intent> pendingStarts = new HashMap<>();
        ServiceRecord(ServiceInfo info) {
            component = new ComponentName(info.packageName, info.name);
            this.info = info;
        }
    }

    GuestComponents(GuestRuntime runtime) { this.runtime = runtime; }

    Bundle dispatch(int operation, Bundle input) throws Exception {
        if (operation == EngineProtocol.ACQUIRE_PROVIDER) return provider(input);
        ComponentName component = input.getParcelable("component");
        boolean ending = operation == EngineProtocol.STOP_SERVICE || operation == EngineProtocol.UNBIND_SERVICE;
        ServiceRecord record = services.get(component);
        ServiceInfo info = ending && record != null ? record.info : serviceInfo(component, ending);
        Intent intent = input.getParcelable("intent");
        if (intent == null) intent = new Intent().setComponent(component);
        intent.setExtrasClassLoader(runtime.classLoader());
        Bundle result = EngineProtocol.response();
        result.putParcelable("component", component);
        if (operation == EngineProtocol.STOP_SERVICE) {
            boolean stopped = record != null && record.started;
            if (stopped) {
                stop(record);
            }
            result.putBoolean("stopped", stopped);
            return result;
        }
        if (operation == EngineProtocol.UNBIND_SERVICE) {
            result.putBoolean("unbound", record != null && unbind(record, input.getBinder("connection")));
            return result;
        }
        if (operation == EngineProtocol.BIND_SERVICE && record == null
                && (input.getLong("flags") & Context.BIND_AUTO_CREATE) == 0) {
            result.putBoolean("bound", false);
            return result;
        }
        if (record == null) {
            record = new ServiceRecord(info);
            services.put(record.component, record);
        }
        if (operation == EngineProtocol.START_SERVICE) {
            start(record, intent, input.getBoolean("require_foreground"));
            return result;
        }
        if (operation != EngineProtocol.BIND_SERVICE) throw new EngineException("Unsupported service command");
        record.executingBindings++;
        try {
            create(record);
            IBinder connection = input.getBinder("connection");
            if (connection == null || !connection.isBinderAlive()) throw new EngineException("Service connection has died");
            Intent.FilterComparison filter = new Intent.FilterComparison(intent);
            Binding existing = record.connections.get(connection);
            if (existing != null && !existing.filter.equals(filter)) {
                throw new EngineException("One connection cannot bind different intents to the same guest Service yet");
            }
            if (!record.published.containsKey(filter)) {
                try {
                    IBinder published = record.service.onBind(intent);
                    if (published != null && android.accounts.AccountManager.ACTION_AUTHENTICATOR_INTENT.equals(intent.getAction())
                            && GuestAccounts.isAuthenticator(info)) {
                        published = GuestAuthenticatorBridge.wrap(published, runtime.serverPid());
                    }
                    record.published.put(filter, published);
                }
                catch (Throwable failure) {
                    destroyIfUnused(record);
                    throw new EngineException("Guest Service.onBind failed", failure);
                }
            }
            else if (Boolean.TRUE.equals(record.rebind.remove(filter))) record.service.onRebind(intent);
            if (!record.connections.containsKey(connection)) {
                ServiceRecord owner = record;
                IBinder.DeathRecipient death = () -> runtime.post(() -> {
                    try { unbind(owner, connection); }
                    catch (Exception failure) { throw new EngineException("Dead service client cleanup failed", failure); }
                });
                try { connection.linkToDeath(death, 0); }
                catch (android.os.RemoteException failure) {
                    destroyIfUnused(record);
                    throw failure;
                }
                record.connections.put(connection, new Binding(new Intent(intent), connection, death, (input.getLong("flags") & Context.BIND_AUTO_CREATE) != 0));
            }
            result.putBinder("service", record.published.get(filter));
            result.putBoolean("bound", true);
            result.putLong("incarnation", record.incarnation);
            return result;
        } finally { record.executingBindings--; destroyIfUnused(record); }
    }

    private void create(ServiceRecord record) throws Exception {
        if (record.service != null) return;
        ServiceInfo info = record.info;
        Context context = runtime.application().createPackageContext(info.packageName,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        if (info.splitName != null) context = context.createContextForSplit(info.splitName);
        ClassLoader loader = context.getClassLoader();
        Service service;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            Object factory = RuntimeReflection.invoke(runtime.loadedApk(),
                    RuntimeReflection.method(runtime.loadedApk().getClass(), "getAppFactory"));
            service = (Service) RuntimeReflection.invoke(factory,
                    RuntimeReflection.method(factory.getClass(), "instantiateService", ClassLoader.class, String.class, Intent.class),
                    loader, info.name, new Intent().setComponent(new ComponentName(info.packageName, info.name)));
        } else service = (Service) loader.loadClass(info.name).getDeclaredConstructor().newInstance();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object activityManager = RuntimeReflection.invoke(null,
                RuntimeReflection.method(Class.forName("android.app.ActivityManager"), "getService"));
        RuntimeReflection.invoke(service, RuntimeReflection.method(Service.class, "attach", Context.class,
                activityThread, String.class, IBinder.class, Application.class, Object.class),
                context, RuntimeReflection.activityThread(), info.name, record.token, runtime.application(), activityManager);
        RuntimeReflection.invoke(context, RuntimeReflection.method(context.getClass(), "setOuterContext", Context.class), service);
        record.service = service;
        try { service.onCreate(); record.incarnation = ++nextIncarnation; state(record, true); }
        catch (Throwable failure) {
            services.remove(record.component);
            throw new EngineException("Guest Service.onCreate failed", failure);
        }
    }

    private boolean unbind(ServiceRecord record, IBinder connection) {
        Binding binding = record.connections.remove(connection);
        if (binding == null) return false;
        connection.unlinkToDeath(binding.death, 0);
        boolean active = false;
        for (Binding remaining : record.connections.values()) if (remaining.filter.equals(binding.filter)) active = true;
        if (!active) record.rebind.put(binding.filter, record.service.onUnbind(binding.intent));
        destroyIfUnused(record);
        return true;
    }

    boolean hasService(ComponentName component) {
        ServiceRecord record = services.get(component); return record != null && record.service != null;
    }
    private void state(ServiceRecord record, boolean alive) {
        if (record.incarnation > 0) ServerBoundServices.state(runtime, record.component, record.incarnation, alive, record.started);
    }

    private void destroyIfUnused(ServiceRecord record) {
        if (record.started || record.executingBindings != 0) return;
        for (Binding binding : record.connections.values()) if (binding.autoCreate) return;
        if (record.slot >= 0) {
            stopPhysical(record);
            return;
        }
        if (services.remove(record.component) != record) return;
        if (record.service != null) {
            // BIND_AUTO_CREATE alone retains the object. Passive clients cannot keep a stopped Service alive.
            ArrayList<Binding> passive = new ArrayList<>(record.connections.values());
            record.connections.clear();
            java.util.HashSet<Intent.FilterComparison> notified = new java.util.HashSet<>();
            for (Binding binding : passive) {
                binding.connection.unlinkToDeath(binding.death, 0);
                if (notified.add(binding.filter)) record.service.onUnbind(binding.intent);
            }
            try { record.service.onDestroy(); } finally { state(record, false); }
        }
    }

    boolean ownsToken(IBinder token) {
        for (ServiceRecord record : services.values()) if (record.token == token) return true;
        return false;
    }

    boolean stopToken(IBinder token, int startId) {
        for (ServiceRecord record : new ArrayList<>(services.values())) {
            if (record.token == token && (startId < 0 || startId == record.startId)) {
                if (startId >= 0 && !record.pendingStarts.isEmpty()) return false;
                stop(record);
                return true;
            }
        }
        return false;
    }

    private void start(ServiceRecord record, Intent intent, boolean foreground) {
        if (record.stopping) throw new IllegalStateException("Guest Service is still completing its previous stop");
        if (record.pendingStarts.size() >= 128) throw new IllegalStateException("Too many pending guest Service starts");
        boolean allocated = record.slot < 0;
        if (allocated) {
            for (int i = 0; i < physicalSlots.length; i++) if (physicalSlots[i] == null) { record.slot = i; break; }
            if (record.slot < 0) {
                destroyIfUnused(record);
                throw new IllegalStateException("All four physical Service slots in this guest process are occupied");
            }
            record.lease = java.util.UUID.randomUUID().toString(); physicalSlots[record.slot] = record;
        }
        int request = ++record.requestId;
        record.pendingStarts.put(request, new Intent(intent));
        boolean wasStarted = record.started;
        record.started = true;
        Intent physical = physicalIntent(record).putExtra("lease", record.lease).putExtra("request", request);
        try {
            Context context = Engine.get().hostContext();
            ComponentName accepted = foreground ? context.startForegroundService(physical) : context.startService(physical);
            if (accepted == null) throw new IllegalStateException("Android did not start the physical Service");
            if (record.service != null) state(record, true);
        } catch (RuntimeException failure) {
            record.pendingStarts.remove(request); record.started = wasStarted;
            if (allocated) release(record);
            destroyIfUnused(record);
            throw failure;
        }
    }

    private Intent physicalIntent(ServiceRecord record) {
        return new Intent().setClassName(Engine.get().hostContext(), GuestServiceStub.class.getName() + "$P" + runtime.slot() + "S" + record.slot);
    }

    int physicalStart(GuestServiceStub physical, Intent command, int flags, int startId) throws Exception {
        ServiceRecord record = physicalRecord(physical, false);
        if (record == null || command == null || !record.lease.equals(command.getStringExtra("lease"))) {
            throw new SecurityException("Physical Service start has no live guest lease");
        }
        record.physical = physical; record.physicalStartId = startId;
        Intent intent = record.pendingStarts.remove(command.getIntExtra("request", -1));
        if (record.stopping || !record.started) { physical.stopSelf(); return Service.START_NOT_STICKY; }
        if (intent == null) throw new IllegalStateException("Guest Service start payload is unavailable");
        create(record);
        intent.setExtrasClassLoader(runtime.classLoader());
        return record.service.onStartCommand(intent, flags, ++record.startId);
    }

    void physicalCreated(GuestServiceStub physical) {
        ServiceRecord record = physicalRecord(physical, false);
        if (record == null || record.physical != null) throw new SecurityException("Physical Service has no reserved guest slot");
        record.physical = physical;
    }

    private void stop(ServiceRecord record) {
        record.started = false;
        if (record.service != null) state(record, true);
        if (record.slot >= 0) stopPhysical(record);
        else destroyIfUnused(record);
    }

    private void stopPhysical(ServiceRecord record) {
        if (record.stopping) return;
        record.stopping = true; record.pendingStarts.clear();
        if (record.physical != null) record.physical.stopSelf();
        else if (!Engine.get().hostContext().stopService(physicalIntent(record))) {
            release(record); destroyIfUnused(record);
        }
    }

    private void release(ServiceRecord record) {
        if (record.slot >= 0 && physicalSlots[record.slot] == record) physicalSlots[record.slot] = null;
        record.slot = -1; record.lease = null; record.physical = null; record.stopping = false;
        record.pendingStarts.clear();
    }

    void physicalDestroyed(GuestServiceStub physical) {
        ServiceRecord record = physicalRecord(physical, true);
        if (record == null) return;
        record.started = false;
        if (record.service != null) state(record, true);
        release(record); destroyIfUnused(record);
    }

    private ServiceRecord physicalRecord(GuestServiceStub physical, boolean requireInstance) {
        for (ServiceRecord record : physicalSlots) if (record != null
                && physical.getClass().getName().equals(physicalIntent(record).getComponent().getClassName())
                && (!requireInstance || record.physical == physical)) return record;
        return null;
    }

    void foreground(IBinder token, int id, Notification notification, int flags, int type) throws Exception {
        ServiceRecord record = tokenRecord(token);
        if (id == 0) {
            if (notification != null || (flags & ~(Service.STOP_FOREGROUND_REMOVE | Service.STOP_FOREGROUND_DETACH)) != 0) {
                throw new IllegalArgumentException("Invalid stopForeground arguments");
            }
            // A valid bound-only or already-stopping Service may clear foreground state repeatedly.
            if (record.physical != null) record.physical.stopForeground(flags);
            return;
        }
        if (record.physical == null || record.stopping) throw new IllegalStateException("Guest Service has no active physical started lifecycle");
        int selected = ForegroundServiceTypes.resolve(Engine.get(), record.info, type);
        Bundle mapped = GuestNotificationCalls.foreground(notification, id);
        Notification normalized = mapped.getParcelable("notification");
        if (android.os.Build.VERSION.SDK_INT >= 29) record.physical.startForeground(mapped.getInt("id"), normalized, selected);
        else record.physical.startForeground(mapped.getInt("id"), normalized);
    }

    int foregroundType(IBinder token) {
        ServiceRecord record = tokenRecord(token);
        return record.physical == null ? 0 : record.physical.getForegroundServiceType();
    }

    private ServiceRecord tokenRecord(IBinder token) {
        for (ServiceRecord record : services.values()) if (record.token == token) return record;
        throw new SecurityException("Unknown guest Service token");
    }

    void physicalTaskRemoved(GuestServiceStub physical, Intent intent) {
        ServiceRecord record = physicalRecord(physical, true);
        if (record != null && record.service != null) {
            // shortcut: task removal needs a server-validated original guest Intent, never the host capability envelope.
            android.util.Log.w("MyliteService", "Guest task-removal delivery is not implemented");
        }
    }
    void physicalConfiguration(GuestServiceStub physical, android.content.res.Configuration config) {
        ServiceRecord record = physicalRecord(physical, true);
        if (record != null && record.service != null) record.service.onConfigurationChanged(config);
    }
    void physicalMemory(GuestServiceStub physical, int level) {
        ServiceRecord record = physicalRecord(physical, true);
        if (record != null && record.service != null) {
            if (level < 0) record.service.onLowMemory(); else record.service.onTrimMemory(level);
        }
    }
    void boundConfiguration(android.content.res.Configuration config) {
        for (ServiceRecord record : new ArrayList<>(services.values())) if (record.service != null && record.physical == null) record.service.onConfigurationChanged(config);
    }
    void boundMemory(int level) {
        for (ServiceRecord record : new ArrayList<>(services.values())) if (record.service != null && record.physical == null) {
            if (level < 0) record.service.onLowMemory(); else record.service.onTrimMemory(level);
        }
    }

    void physicalTimeout(GuestServiceStub physical, int startId, int type) {
        ServiceRecord record = physicalRecord(physical, true);
        if (record != null && record.service != null) {
            if (android.os.Build.VERSION.SDK_INT >= 35 && type >= 0) record.service.onTimeout(record.startId, type);
            else if (android.os.Build.VERSION.SDK_INT >= 34) record.service.onTimeout(record.startId);
        }
    }

    private ServiceInfo serviceInfo(ComponentName component, boolean ending) throws Exception {
        PackageInfo pkg = Engine.get().packages().getPackageInfo(runtime.packageInfo().packageName,
                android.content.pm.PackageManager.GET_SERVICES | (ending ? android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS : 0));
        if (component != null && pkg.services != null && component.getPackageName().equals(pkg.packageName)) {
            for (ServiceInfo info : pkg.services) {
                if (component.getClassName().equals(info.name) && (info.enabled || ending) && info.applicationInfo.enabled
                        && runtime.ownsProcess(info.processName)) return info;
            }
        }
        throw new EngineException("Service does not belong to the bound process: " + component);
    }

    private Bundle provider(Bundle input) throws Exception {
        String authority = input.getString("authority");
        ProviderInfo selected = null;
        PackageInfo pkg = runtime.packageInfo();
        if (pkg.providers != null) {
            for (ProviderInfo info : pkg.providers) {
                if (info.authority == null || !info.enabled || !info.applicationInfo.enabled
                        || !runtime.ownsProcess(info.processName)) continue;
                for (String name : info.authority.split(";")) if (name.equals(authority)) selected = info;
            }
        }
        if (selected == null) throw new EngineException("Provider does not belong to the bound process");
        try (ContentProviderClient provider = runtime.application().getContentResolver()
                .acquireUnstableContentProviderClient(authority)) {
            if (provider == null) throw new EngineException("Guest provider was not installed");
            IInterface contract = (IInterface) RuntimeReflection.get(provider, "mContentProvider");
            Bundle result = EngineProtocol.response();
            result.putBinder("provider", contract.asBinder());
            result.putParcelable("info", selected);
            return result;
        }
    }
}
