package io.github.xmy953538104.mybox.engine.packages;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class NativeLibraries {
    static String extract(List<VerifiedApk> apks, File output, String[] supportedAbis) throws IOException {
        Set<String> available = new HashSet<>();
        for (VerifiedApk apk : apks) {
            try (ZipFile zip = new ZipFile(apk.file)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String[] parts = entries.nextElement().getName().split("/", -1);
                    if (parts.length == 3 && "lib".equals(parts[0]) && parts[2].endsWith(".so")) available.add(parts[1]);
                }
            }
        }
        String selected = null;
        for (String abi : supportedAbis) if (available.contains(abi)) { selected = abi; break; }
        if (!available.isEmpty() && selected == null) throw new PackageValidationException("APK has no native ABI supported by this process");
        File directory = new File(output, selected == null ? "none" : selected);
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Cannot create native library directory");
        if (selected == null) return null;
        String prefix = "lib/" + selected + "/";
        Set<String> extracted = new HashSet<>();
        long total = 0;
        for (VerifiedApk apk : apks) {
            try (ZipFile zip = new ZipFile(apk.file)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().startsWith(prefix) || entry.isDirectory()) continue;
                    String name = entry.getName().substring(prefix.length());
                    if (!name.endsWith(".so")) continue;
                    if (!name.matches("[A-Za-z0-9_.+-]+") || !extracted.add(name)) {
                        throw new PackageValidationException("Invalid or duplicate native library name");
                    }
                    File library = new File(directory, name);
                    try (InputStream input = zip.getInputStream(entry); FileOutputStream stream = new FileOutputStream(library)) {
                        byte[] buffer = new byte[32768];
                        int count;
                        long size = 0;
                        while ((count = input.read(buffer)) != -1) {
                            size += count;
                            total += count;
                            if (size > 512L * 1024 * 1024 || total > 2L * 1024 * 1024 * 1024) {
                                throw new PackageValidationException("Native library expansion exceeds limit");
                            }
                            stream.write(buffer, 0, count);
                        }
                        stream.getFD().sync();
                    }
                    if (!library.setReadOnly()) throw new IOException("Cannot seal native library");
                }
            }
        }
        return selected;
    }
}
