package top.niunaijun.blackbox.core.system.pm.installer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ApkNativeLibraries {
    private ApkNativeLibraries() {}

    public static void extract(File apk, File destination, String abi) throws Exception {
        boolean hasLibraries = false, hasCompatibleLibraries = false;
        String prefix = "lib/" + abi + "/";
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("lib/") || !name.endsWith(".so")) continue;
                hasLibraries = true;
                if (!name.startsWith(prefix)) continue;
                hasCompatibleLibraries = true;
                String libraryName = name.substring(prefix.length());
                if (!libraryName.matches("[A-Za-z0-9+_.-]+\\.so")) throw new IOException("Invalid native library path");
                File outputFile = new File(destination, libraryName);
                if (outputFile.exists()) {
                    try (InputStream existing = new FileInputStream(outputFile); InputStream incoming = zip.getInputStream(entry)) {
                        if (!MessageDigest.isEqual(hash(existing), hash(incoming))) throw new IOException("Conflicting native libraries across splits");
                    }
                } else {
                    try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        output.getFD().sync();
                    }
                }
            }
        }
        if (hasLibraries && !hasCompatibleLibraries) throw new IllegalArgumentException("APK requires an unsupported ABI");
    }

    private static byte[] hash(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        return digest.digest();
    }
}
