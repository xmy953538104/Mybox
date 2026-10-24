package io.github.xmy953538104.mybox.engine.installer;

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PackageInstallerBackend implements InstallerBackend {
    private final PackageStore packages;
    PackageInstallerBackend(PackageStore packages) { this.packages = packages; }
    @Override public List<String> certificates(String name) throws IOException {
        InstalledPackage installed = packages.get(name); return installed == null ? null : installed.certificateSha256;
    }
    @Override public InstalledPackage install(List<File> apks, String expected) throws IOException { return packages.install(apks, expected); }
    @Override public InstalledPackage findPublished(List<File> apks, String expected) throws IOException {
        if (apks.isEmpty()) return null;
        List<String> requested = hashes(apks);
        for (InstalledPackage installed : packages.list()) {
            if (expected != null && !expected.equals(installed.packageName)) continue;
            List<File> files = new ArrayList<>(installed.splitApks); files.add(installed.baseApk);
            if (files.size() == apks.size() && requested.equals(hashes(files))) return installed;
        }
        return null;
    }
    private static List<String> hashes(List<File> files) throws IOException {
        List<String> result = new ArrayList<>();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (File file : files) {
                digest.reset(); byte[] buffer = new byte[64 * 1024];
                try (FileInputStream input = new FileInputStream(file)) {
                    int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
                StringBuilder value = new StringBuilder();
                for (byte item : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
                result.add(value.toString());
            }
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        Collections.sort(result); return result;
    }
}
