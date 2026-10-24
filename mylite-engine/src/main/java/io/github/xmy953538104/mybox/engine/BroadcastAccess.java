package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.util.Arrays;

/** Broadcast grants are derived from committed manifests and real APK signers. */
final class BroadcastAccess {
    private final Engine engine;

    BroadcastAccess(Engine engine) { this.engine = engine; }

    InstalledPackage sender(CallerIdentity caller) throws Exception {
        InstalledPackage installed = engine.packages().get(caller.packageName);
        if (installed == null || caller.userId != 0 || installed.appId != caller.appId
                || !installed.certificateSha256.equals(caller.certificateSha256)) {
            throw new SecurityException("Broadcast sender identity is no longer current");
        }
        return installed;
    }

    boolean current(InstalledPackage expected) throws Exception {
        if (expected == null) return false;
        InstalledPackage actual = engine.packages().get(expected.packageName);
        return actual != null && expected.baseApk.equals(actual.baseApk) && expected.appId == actual.appId
                && expected.certificateSha256.equals(actual.certificateSha256);
    }

    boolean googleRoute(InstalledPackage sender, Intent intent, String requiredPermission, boolean ordered) throws Exception {
        String target = intent.getPackage();
        if (sender.packageName.equals(target)) return false;
        if (!GuestPackageQueries.isGoogleIdentity(target)) return false;
        GoogleAccountChangePolicy.requireSender(engine, sender, intent, requiredPermission, ordered);
        Declaration declaration = declaration(requiredPermission);
        if (declaration == null || !sender.packageName.equals(declaration.owner.packageName)
                || declaration.permission.protectionLevel != PermissionInfo.PROTECTION_SIGNATURE
                || !requested(sender, requiredPermission)) {
            throw new SecurityException("Google account event permission is not owned by the pinned sender");
        }
        return true;
    }

    boolean permits(InstalledPackage sender, InstalledPackage recipient, boolean exported,
                    String senderPermission, String requiredPermission, boolean googleRoute, String receiverName) throws Exception {
        if (!current(sender) || !current(recipient)) return false;
        boolean samePackage = sender.packageName.equals(recipient.packageName);
        if (!samePackage && (!exported || !granted(sender, senderPermission))) return false;
        boolean allowed = googleRoute ? requested(recipient, requiredPermission)
                && GoogleAccountChangePolicy.allowsReceiver(recipient.packageName, recipient.certificateSha256,
                        receiverName, exported, senderPermission, requiredPermission) : granted(recipient, requiredPermission);
        return allowed && current(sender) && current(recipient);
    }

    private boolean granted(InstalledPackage recipient, String permission) throws Exception {
        if (permission == null) return true;
        Declaration declaration = declaration(permission);
        return declaration != null && ServiceAccess.permissionGranted(declaration.permission.protectionLevel,
                requested(recipient, permission), declaration.owner.certificateSha256, recipient.certificateSha256);
    }

    private boolean requested(InstalledPackage installed, String permission) throws Exception {
        PackageInfo info = engine.packages().getPackageInfo(installed.packageName, PackageManager.GET_PERMISSIONS);
        return current(installed) && info != null && info.requestedPermissions != null && Arrays.asList(info.requestedPermissions).contains(permission);
    }

    private Declaration declaration(String name) throws Exception {
        Declaration result = null;
        for (InstalledPackage installed : engine.packages().list()) {
            PackageInfo info = engine.packages().getPackageInfo(installed.packageName, PackageManager.GET_PERMISSIONS);
            if (!current(installed)) throw new SecurityException("Permission owner changed during broadcast resolution");
            if (info == null || info.permissions == null) continue;
            for (PermissionInfo permission : info.permissions) if (name.equals(permission.name)) {
                if (result != null) throw new SecurityException("Ambiguous virtual permission owner: " + name);
                result = new Declaration(installed, permission);
            }
        }
        return result;
    }

    private static final class Declaration {
        final InstalledPackage owner;
        final PermissionInfo permission;
        Declaration(InstalledPackage owner, PermissionInfo permission) { this.owner = owner; this.permission = permission; }
    }
}
