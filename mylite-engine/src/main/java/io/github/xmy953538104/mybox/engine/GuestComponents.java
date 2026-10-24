package io.github.xmy953538104.mybox.engine;

import android.app.Application;
import android.app.Service;
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

    private static final class Binding {
        final Intent intent;
        final Intent.FilterComparison filter;
        final IBinder connection;
        final IBinder.DeathRecipient death;
        Binding(Intent intent, IBinder connection, IBinder.DeathRecipient death) {
            this.intent = intent;
            filter = new Intent.FilterComparison(intent);
            this.connection = connection;
            this.death = death;
        }
    }

    private static final class ServiceRecord {
        final ComponentName component;
        final Service service;
        final Binder token = new Binder();
        final Map<IBinder, Binding> connections = new HashMap<>();
        final Map<Intent.FilterComparison, IBinder> published = new HashMap<>();
        final Map<Intent.FilterComparison, Boolean> rebind = new HashMap<>();
        int startId;
        boolean started;
        ServiceRecord(ComponentName component, Service service) {
            this.component = component;
            this.service = service;
        }
    }

    GuestComponents(GuestRuntime runtime) { this.runtime = runtime; }

    Bundle dispatch(int operation, Bundle input) throws Exception {
        if (operation == EngineProtocol.ACQUIRE_PROVIDER) return provider(input);
        ComponentName component = input.getParcelable("component");
        ServiceInfo info = serviceInfo(component);
        Intent intent = input.getParcelable("intent");
        if (intent == null) intent = new Intent().setComponent(component);
        intent.setExtrasClassLoader(runtime.classLoader());
        ServiceRecord record = services.get(component);
        Bundle result = EngineProtocol.response();
        result.putParcelable("component", component);
        if (operation == EngineProtocol.STOP_SERVICE) {
            boolean stopped = record != null && record.started;
            if (stopped) {
                record.started = false;
                destroyIfUnused(record);
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
        if (record == null) record = create(info);
        if (operation == EngineProtocol.START_SERVICE) {
            record.started = true;
            result.putInt("start_id", ++record.startId);
            result.putInt("restart_policy", record.service.onStartCommand(intent, 0, record.startId));
            return result;
        }
        if (operation != EngineProtocol.BIND_SERVICE) throw new EngineException("Unsupported service command");
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
            record.connections.put(connection, new Binding(new Intent(intent), connection, death));
        }
        result.putBinder("service", record.published.get(filter));
        result.putBoolean("bound", true);
        return result;
    }

    private ServiceRecord create(ServiceInfo info) throws Exception {
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
        ServiceRecord record = new ServiceRecord(new ComponentName(info.packageName, info.name), service);
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object activityManager = RuntimeReflection.invoke(null,
                RuntimeReflection.method(Class.forName("android.app.ActivityManager"), "getService"));
        RuntimeReflection.invoke(service, RuntimeReflection.method(Service.class, "attach", Context.class,
                activityThread, String.class, IBinder.class, Application.class, Object.class),
                context, RuntimeReflection.activityThread(), info.name, record.token, runtime.application(), activityManager);
        RuntimeReflection.invoke(context, RuntimeReflection.method(context.getClass(), "setOuterContext", Context.class), service);
        services.put(record.component, record);
        try { service.onCreate(); }
        catch (Throwable failure) {
            services.remove(record.component);
            throw new EngineException("Guest Service.onCreate failed", failure);
        }
        return record;
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

    private void destroyIfUnused(ServiceRecord record) {
        if (record.started || !record.connections.isEmpty()) return;
        if (services.remove(record.component) != record) return;
        record.service.onDestroy();
    }

    boolean ownsToken(IBinder token) {
        for (ServiceRecord record : services.values()) if (record.token == token) return true;
        return false;
    }

    boolean stopToken(IBinder token, int startId) {
        for (ServiceRecord record : new ArrayList<>(services.values())) {
            if (record.token == token && (startId < 0 || startId == record.startId)) {
                record.started = false;
                destroyIfUnused(record);
                return true;
            }
        }
        return false;
    }

    private ServiceInfo serviceInfo(ComponentName component) {
        PackageInfo pkg = runtime.packageInfo();
        if (component != null && pkg.services != null && component.getPackageName().equals(pkg.packageName)) {
            for (ServiceInfo info : pkg.services) {
                if (component.getClassName().equals(info.name) && info.enabled && info.applicationInfo.enabled
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
