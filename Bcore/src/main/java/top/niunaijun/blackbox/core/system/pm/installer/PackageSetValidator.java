package top.niunaijun.blackbox.core.system.pm.installer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Android-independent checks shared by import and Session commit. */
public final class PackageSetValidator {
    private PackageSetValidator() {}

    public static void check(String packageName, long version, String[] splits,
                             String[] actualCertificates, String installedPackage,
                             long installedVersion, String[] installedCertificates) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("Invalid package name");
        }
        if (actualCertificates == null || actualCertificates.length == 0) {
            throw new SecurityException("APK has no verified signer");
        }
        Set<String> names = new HashSet<>();
        if (splits != null) for (String split : splits) {
            if (split == null || !split.matches("[A-Za-z0-9_.-]+") || !names.add(split)) {
                throw new IllegalArgumentException("Invalid or duplicate split");
            }
        }
        if (installedPackage != null) {
            if (!packageName.equals(installedPackage)) throw new SecurityException("Package mismatch");
            if (!sameSigners(actualCertificates, installedCertificates)) {
                throw new SecurityException("Update signer does not match installed APK");
            }
            if (version < installedVersion) throw new IllegalArgumentException("Version downgrade rejected");
        }
    }

    public static boolean sameSigners(String[] first, String[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) return false;
        String[] a = first.clone(), b = second.clone();
        Arrays.sort(a);
        Arrays.sort(b);
        return Arrays.equals(a, b);
    }
}
