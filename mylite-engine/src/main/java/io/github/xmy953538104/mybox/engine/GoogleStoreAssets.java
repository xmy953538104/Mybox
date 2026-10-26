package io.github.xmy953538104.mybox.engine;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CheckedInputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded source staging only; PackageStore alone verifies and publishes the actual APK set. */
final class GoogleStoreAssets {
    static final int MAX_FILES = 256; // Same complete-set limit as PackageStore.
    static final long MAX_BYTES = 512L * 1024 * 1024;
    static final String DIRECTORY = "mybox/google-play";
    static final String SINGLE = "mybox/google-play.apk";
    static final String REFERENCE_STORE = "assets/preset_apps/gpstore400013.apk";

    interface Assets {
        String[] list(String path) throws IOException;
        InputStream open(String path) throws IOException;
    }

    static Copied fromAssets(Assets assets, File cache) throws IOException {
        String[] root = assets.list("mybox");
        List<String> entries = root == null ? Collections.emptyList() : Arrays.asList(root);
        boolean directory = entries.contains("google-play"), single = entries.contains("google-play.apk");
        if (directory && single || Collections.frequency(entries, "google-play") > 1
                || Collections.frequency(entries, "google-play.apk") > 1)
            throw new IOException("Conflicting Store sources: APK set and single APK");
        if (!directory && !single) return null;
        List<String> paths = new ArrayList<>();
        if (directory) {
            for (String name : members(assets.list(DIRECTORY))) paths.add(DIRECTORY + "/" + name);
        } else paths.add(SINGLE);
        return copy(paths.size(), cache, index -> assets.open(paths.get(index)));
    }

    static List<String> members(String[] names) throws IOException {
        if (names == null || names.length == 0 || names.length > MAX_FILES) throw new IOException("Store set requires 1 to 256 APKs");
        List<String> result = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (String name : names) {
            if (name == null || name.length() > 160 || !(name.equals("base.apk")
                    || name.matches("split_[A-Za-z0-9_][A-Za-z0-9_.-]*\\.apk"))
                    || !unique.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Invalid or duplicate Store APK filename");
            result.add(name);
        }
        if (!result.contains("base.apk")) throw new IOException("Store APK set is missing base.apk");
        Collections.sort(result); // base.apk precedes every permitted split filename.
        return result;
    }

    static Copied fromFiles(List<File> files, File cache) throws IOException {
        if (files == null || files.isEmpty() || files.size() > MAX_FILES) throw new IOException("Store set requires 1 to 256 APKs");
        for (File file : files) if (file == null || !file.isFile()) throw new IOException("Store source is not a regular file");
        return copy(files.size(), cache, index -> new FileInputStream(files.get(index)));
    }

    /** The selected reference is data only: never import its engine, services or native libraries. */
    static Copied fromReference(File archive, File cache) throws IOException {
        boundReferenceDirectory(archive);
        Copied result = null;
        try (ZipFile zip = new ZipFile(archive)) {
            if (zip.size() > 16384) throw new IOException("Too many reference ZIP entries");
            ZipEntry selected = null;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!REFERENCE_STORE.equals(entry.getName())) continue;
                if (selected != null) throw new IOException("Duplicate Play entry in the selected source");
                selected = entry;
            }
            if (selected == null) throw new IOException("该文件没有受支持的内置 Play 安装包；请改选 Google Play 原签名 APK 与全部分包");
            if (selected.isDirectory() || selected.getSize() <= 0 || selected.getSize() > MAX_BYTES || selected.getCrc() < 0)
                throw new IOException("Invalid embedded Play APK size or CRC");
            ZipEntry entry = selected;
            CRC32 crc = new CRC32();
            result = copy(1, cache, index -> new CheckedInputStream(zip.getInputStream(entry), crc));
            if (result.files.get(0).length() != entry.getSize() || crc.getValue() != entry.getCrc()) {
                throw new IOException("Embedded Play APK is truncated or corrupt");
            }
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            if (result != null) result.finish(false,failure);
            throw failure;
        }
    }

    // Bound central-directory allocation before ZipFile parses an untrusted archive.
    private static void boundReferenceDirectory(File archive) throws IOException {
        if (archive == null || !archive.isFile() || archive.length() > MAX_BYTES) throw new IOException("Invalid reference APK; maximum 512 MiB");
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(archive,"r")) {
            long length = input.length(); int tailLength = (int)Math.min(length,65557);
            byte[] tail = new byte[tailLength]; input.seek(length-tailLength); input.readFully(tail);
            for (int offset = tailLength-22; offset >= 0; offset--) {
                if (u32(tail,offset) != 0x06054b50L || offset+22+u16(tail,offset+20) != tailLength) continue;
                long count = u16(tail,offset+10), bytes = u32(tail,offset+12), start = u32(tail,offset+16);
                if (u16(tail,offset+4) != 0 || u16(tail,offset+6) != 0 || u16(tail,offset+8) != count
                        || count == 0 || count > 16384 || bytes > 8L*1024*1024 || bytes < count*46
                        || start+bytes != length-tailLength+offset)
                    throw new IOException("Unsupported or unbounded reference ZIP directory");
                return;
            }
            throw new IOException("Reference APK ZIP directory is missing or truncated");
        }
    }
    private static int u16(byte[] data,int offset) { return (data[offset]&255) | (data[offset+1]&255)<<8; }
    private static long u32(byte[] data,int offset) { return (u16(data,offset)&0xffffL) | ((long)u16(data,offset+2)<<16); }

    private interface Open { InputStream open(int index) throws IOException; }

    private static Copied copy(int count, File cache, Open source) throws IOException {
        Copied result = new Copied(Files.createTempDirectory(cache.toPath(), "google-play-").toFile());
        try {
            long total = 0;
            for (int index = 0; index < count; index++) {
                File file = new File(result.directory, "input-" + index + ".apk"); result.files.add(file);
                try (InputStream input = source.open(index); FileOutputStream output = new FileOutputStream(file)) {
                    if (input == null) throw new IOException("Cannot open Store APK");
                    total += copyApk(input, output, MAX_BYTES - total);
                    output.getFD().sync();
                }
            }
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            result.finish(false, failure);
            throw failure;
        }
    }

    static long copyApk(InputStream input, OutputStream output, long remaining) throws IOException {
        long size = 0; byte[] buffer = new byte[65536];
        for (;;) {
            int read = input.read(buffer);
            if (read == -1) break;
            if (read == 0) { int one = input.read(); if (one == -1) break; buffer[0] = (byte) one; read = 1; }
            if (read > remaining - size) throw new IOException("Store APK set exceeds 512 MiB");
            size += read; output.write(buffer, 0, read);
        }
        if (size == 0) throw new IOException("Store APK is empty");
        return size;
    }

    static final class Copied implements Closeable {
        private final File directory;
        private final List<File> files = new ArrayList<>();
        private Copied(File directory) { this.directory = directory; }
        List<File> files() { return Collections.unmodifiableList(files); }
        /** A committed installation is authoritative; cleanup is a diagnostic after publication. */
        Exception finish(boolean published, Throwable failure) throws IOException {
            try { close(); }
            catch (IOException | RuntimeException cleanup) {
                if (failure != null) { failure.addSuppressed(cleanup); return null; }
                if (published) return cleanup;
                throw cleanup;
            }
            return null;
        }
        @Override public void close() throws IOException {
            IOException failure = null;
            for (File file : files) if (file.exists() && !file.delete()) {
                IOException next = new IOException("Cannot remove temporary Store APK");
                if (failure == null) failure = next; else failure.addSuppressed(next);
            }
            if (directory.exists() && !directory.delete()) {
                IOException next = new IOException("Cannot remove temporary Store directory");
                if (failure == null) failure = next; else failure.addSuppressed(next);
            }
            if (failure != null) throw failure;
        }
    }
    private GoogleStoreAssets() { }
}
