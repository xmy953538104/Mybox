package io.github.xmy953538104.mybox.engine;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;

import java.util.Arrays;
import java.util.List;

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Component permissions use committed real APK certificates, never compatibility signature views. */
final class ServiceAccess {
    static void require(Engine engine, CallerIdentity caller, ServiceInfo target) throws Exception {
        require(engine, caller, target, target.permission);
    }
    static void require(Engine engine, CallerIdentity caller, ActivityInfo target) throws Exception {
        require(engine, caller, target, target.permission);
    }
    private static void require(Engine engine, CallerIdentity caller, ComponentInfo target, String required) throws Exception {
        if (caller.packageName.equals(target.packageName)) return;
        if (!target.exported) throw new SecurityException("Guest component is not exported: " + target.name);
        if (required == null || required.isEmpty()) return;
        PackageInfo callerInfo = engine.packages().getPackageInfo(caller.packageName, PackageManager.GET_PERMISSIONS);
        boolean requested = callerInfo != null && callerInfo.requestedPermissions != null
                && Arrays.asList(callerInfo.requestedPermissions).contains(required);
        if (!requested) throw new SecurityException("Caller did not request Service permission: " + required);
        PermissionInfo declaration = null;
        InstalledPackage declaringPackage = null;
        for (InstalledPackage installed : engine.packages().list()) {
            PackageInfo candidate = engine.packages().getPackageInfo(installed.packageName, PackageManager.GET_PERMISSIONS);
            if (candidate.permissions == null) continue;
            for (PermissionInfo permission : candidate.permissions) {
                if (required.equals(permission.name)) {
                    if (declaration != null) throw new SecurityException("Ambiguous virtual permission owner: " + required);
                    declaration = permission;
                    declaringPackage = installed;
                }
            }
        }
        if (declaration == null || declaringPackage == null) {
            // OS signature/dangerous permissions are not inherited from the shared host UID.
            throw new SecurityException("Service permission has no approved virtual grant: " + required);
        }
        if (!permissionGranted(declaration.protectionLevel, requested,
                declaringPackage.certificateSha256, caller.certificateSha256)) {
            throw new SecurityException("Virtual Service permission denied: " + required);
        }
    }

    static boolean permissionGranted(int protection, boolean requested,
            List<String> permissionOwnerCertificates, List<String> callerCertificates) {
        if (!requested) return false;
        int base = protection & PermissionInfo.PROTECTION_MASK_BASE;
        if (base == PermissionInfo.PROTECTION_NORMAL) return protection == PermissionInfo.PROTECTION_NORMAL;
        if (base != PermissionInfo.PROTECTION_SIGNATURE || permissionOwnerCertificates == null
                || permissionOwnerCertificates.isEmpty() || callerCertificates == null) return false;
        return permissionOwnerCertificates.size() == callerCertificates.size()
                && permissionOwnerCertificates.containsAll(callerCertificates);
    }

    private ServiceAccess() { }
}
