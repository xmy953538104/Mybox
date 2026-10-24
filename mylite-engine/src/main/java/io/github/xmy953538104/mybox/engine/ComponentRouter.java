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

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Server-side component selection; guest-supplied metadata is never authoritative. */
final class ComponentRouter {
    private final Engine engine;
    private final ProcessRegistry processes;

    ComponentRouter(Engine engine, ProcessRegistry processes) {
        this.engine = engine;
        this.processes = processes;
    }

    Bundle route(int operation, Bundle input, CallerIdentity caller) throws Exception {
        ComponentInfo target = target(operation, input);
        if (target instanceof ServiceInfo) {
            ServiceAccess.require(engine, caller, (ServiceInfo) target);
        } else if (!caller.packageName.equals(target.packageName)) {
            throw new SecurityException("Cross-package Provider permissions are not implemented yet");
        }
        return send(operation, input, target);
    }

    /** Used only by trusted server services after they have authorized their own operation. */
    Bundle routeInternal(int operation, Bundle input) throws Exception {
        return send(operation, input, target(operation, input));
    }

    private Bundle send(int operation, Bundle input, ComponentInfo target) throws Exception {
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
        Bundle result = EngineProtocol.request(client, EngineProtocol.GUEST, operation, command);
        result.putBinder("owner", client);
        return result;
    }

    private ComponentInfo target(int operation, Bundle input) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) {
            throw new EngineException("Invalid component request");
        }
        if (operation == EngineProtocol.ACQUIRE_PROVIDER) {
            String authority = input.getString("authority");
            for (InstalledPackage installed : engine.packages().list()) {
                PackageInfo pkg = engine.packages().getPackageInfo(installed.packageName, PackageManager.GET_PROVIDERS);
                if (pkg.providers == null) continue;
                for (ProviderInfo provider : pkg.providers) {
                    if (!provider.enabled || !provider.applicationInfo.enabled || provider.authority == null) continue;
                    for (String name : provider.authority.split(";")) if (name.equals(authority)) return provider;
                }
            }
            throw new EngineException("Virtual provider is not installed: " + authority);
        }
        ComponentName component = input.getParcelable("component");
        if (component == null) throw new EngineException("An explicit virtual Service is required");
        PackageInfo pkg = engine.packages().getPackageInfo(component.getPackageName(), PackageManager.GET_SERVICES);
        if (pkg != null && pkg.services != null) {
            for (ServiceInfo service : pkg.services) {
                if (service.name.equals(component.getClassName()) && service.enabled && service.applicationInfo.enabled) {
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
