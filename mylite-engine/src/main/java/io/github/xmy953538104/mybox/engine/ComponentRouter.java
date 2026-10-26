package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;


/** Server-side component selection; guest-supplied metadata is never authoritative. */
final class ComponentRouter {
    private final Engine engine;
    private final ProcessRegistry processes;
    private final ServerBoundServices boundServices;

    ComponentRouter(Engine engine, ProcessRegistry processes) {
        this.engine = engine;
        this.processes = processes;
        boundServices = new ServerBoundServices(engine, processes);
    }

    ServerBoundServices.Binding bindInternal(ComponentName target, Intent intent, long flags,
            ServerBoundServices.Connection listener) throws Exception {
        return boundServices.bindInternal(target, intent, flags, listener);
    }

    Bundle bound(Bundle input, CallerIdentity caller) throws Exception { return boundServices.route(input, caller); }

    CallerIdentity boundMessageCaller(IBinder lease, ProcessRegistry.Identity querying) throws Exception {
        return boundServices.resolveMessageCaller(lease,querying);
    }

    Bundle route(int operation, Bundle input, CallerIdentity caller) throws Exception {
        if (operation == EngineProtocol.BIND_SERVICE || operation == EngineProtocol.UNBIND_SERVICE)
            throw new EngineException("Service bindings require real OS admission and a bound lease");
        ComponentInfo target = target(operation, input);
        if (target instanceof ServiceInfo) {
            ServiceAccess.require(engine, caller, (ServiceInfo) target);
        } else VirtualProviders.requireOpen(caller.packageName, (ProviderInfo) target);
        return send(operation, input, target, caller);
    }

    /** Used only by trusted server services after they have authorized their own operation. */
    Bundle routeInternal(int operation, Bundle input) throws Exception {
        if (operation == EngineProtocol.BIND_SERVICE || operation == EngineProtocol.UNBIND_SERVICE)
            throw new EngineException("Internal Service bindings require bindInternal");
        return send(operation, input, target(operation, input), null);
    }

    private Bundle send(int operation, Bundle input, ComponentInfo target, CallerIdentity caller) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            String processName = target.processName == null ? target.packageName : target.processName;
            Bundle owner;
            if (operation == EngineProtocol.UNBIND_SERVICE || operation == EngineProtocol.STOP_SERVICE) {
                owner = processes.find(target.packageName, processName);
                if (owner == null) {
                    Bundle absent = EngineProtocol.response();
                    absent.putBoolean("stopped", false);
                    absent.putBoolean("unbound", false);
                    return absent;
                }
            } else {
                Bundle request = EngineProtocol.response();
                request.putString("package", target.packageName);
                request.putString("process", processName);
                owner = processes.acquire(request);
            }
            IBinder client = owner.getBinder("client");
            Bundle command = new Bundle(input);
            command.putInt("protocol", EngineProtocol.VERSION);
            command.putParcelable("component", new ComponentName(target.packageName, target.name));
            try {
                Bundle result = EngineProtocol.request(client, EngineProtocol.GUEST, operation, command);
                result.putBinder("owner", client);
                return result;
            } catch (EngineException failure) {
                if (operation != EngineProtocol.ACQUIRE_PROVIDER || attempt != 0
                        || !(failure.getCause() instanceof android.os.DeadObjectException) || !processes.awaitDeath(client)) throw failure;
                // Only Provider acquisition is repeatable; Service mutations never take this path.
                target = target(operation, input);
                if (caller != null) VirtualProviders.requireOpen(caller.packageName, (ProviderInfo) target);
                android.util.Log.i("MyboxProvider", "Reacquiring virtual Provider after its confirmed owner death");
            }
        }
        throw new EngineException("Provider acquisition exhausted its death recovery");
    }

    private ComponentInfo target(int operation, Bundle input) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) {
            throw new EngineException("Invalid component request");
        }
        if (operation == EngineProtocol.ACQUIRE_PROVIDER) {
            String authority = input.getString("authority");
            ProviderInfo provider = VirtualProviders.find(engine, authority);
            if (provider != null) return provider;
            throw new EngineException("Virtual provider is not installed: " + authority);
        }
        ComponentName component = input.getParcelable("component");
        if (component == null) throw new EngineException("An explicit virtual Service is required");
        boolean ending = operation == EngineProtocol.STOP_SERVICE || operation == EngineProtocol.UNBIND_SERVICE;
        PackageInfo pkg = engine.packages().getPackageInfo(component.getPackageName(), PackageManager.GET_SERVICES
                | (ending ? PackageManager.MATCH_DISABLED_COMPONENTS : 0));
        if (pkg != null && pkg.services != null) {
            for (ServiceInfo service : pkg.services) {
                if (service.name.equals(component.getClassName()) && (service.enabled || ending) && service.applicationInfo.enabled) {
                    if ((service.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0) {
                        throw new EngineException("Isolated guest Services are not implemented");
                    }
                    return service;
                }
            }
        }
        throw new EngineException("Virtual Service is absent or disabled: " + component);
    }
}
