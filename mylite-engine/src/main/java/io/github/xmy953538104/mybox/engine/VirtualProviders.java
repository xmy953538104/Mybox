package io.github.xmy953538104.mybox.engine;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Provider selection comes only from committed virtual manifests. */
final class VirtualProviders {
    static ProviderInfo find(Engine engine, String authority) throws Exception {
        if (authority == null || authority.isEmpty() || authority.indexOf('@') >= 0) {
            throw new SecurityException("Missing or user-qualified Provider authority");
        }
        ProviderInfo found = null;
        for (InstalledPackage installed : engine.packages().list()) {
            PackageInfo pkg = engine.packages().getPackageInfo(installed.packageName, PackageManager.GET_PROVIDERS);
            if (pkg.providers == null) continue;
            for (ProviderInfo provider : pkg.providers) {
                if (!provider.enabled || !provider.applicationInfo.enabled || provider.authority == null) continue;
                for (String name : provider.authority.split(";")) if (name.equals(authority)) {
                    if (found != null) throw new SecurityException("Ambiguous virtual Provider authority");
                    found = provider;
                }
            }
        }
        return found;
    }

    static void requireOpen(String caller, ProviderInfo target) {
        if (caller.equals(target.packageName)) return;
        // shortcut: only unrestricted exported peers; add per-operation grants before protected Providers.
        if (!target.exported || target.readPermission != null || target.writePermission != null
                || target.pathPermissions != null && target.pathPermissions.length != 0) {
            throw new SecurityException("Protected cross-package Provider access is not implemented");
        }
    }

    private VirtualProviders() { }
}
