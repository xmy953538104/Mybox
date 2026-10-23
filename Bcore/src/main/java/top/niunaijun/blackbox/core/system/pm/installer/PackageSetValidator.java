package top.niunaijun.blackbox.core.system.pm.installer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Android-independent checks shared by import and Session commit. */
public final class PackageSetValidator {
    private PackageSetValidator() {}

    public static boolean isOfficialGoogleSigner(String[] certificates) {
        if (certificates == null || certificates.length != 1) return false;
        // Fixed upstream microG KnownGooglePackages privileged Google keys.
        return "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83".equals(certificates[0])
                || "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053".equals(certificates[0]);
    }

    public static void checkSplitRequirements(String[] splits, boolean splitRequired, String[] dependencies) {
        Set<String> supplied = new HashSet<>();
        if (splits != null) supplied.addAll(Arrays.asList(splits));
        if (splitRequired && supplied.isEmpty()) throw new IllegalArgumentException("Base APK requires splits");
        if (dependencies != null) for (String dependency : dependencies) {
            if (dependency != null && !dependency.isEmpty() && !supplied.contains(dependency)) {
                throw new IllegalArgumentException("Required split is missing: " + dependency);
            }
        }
    }

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
