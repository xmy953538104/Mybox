package io.github.xmy953538104.mybox.engine;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Fixed-schema, bounded app-private journal. Never accepts payloads or exception messages. */
final class DiagnosticJournal {
    static final int SEGMENTS = 4, SEGMENT_BYTES = 256 * 1024;
    static final int ERROR_SEGMENTS = 2, ERROR_BYTES = 64 * 1024;
    private final File root, enabled, lock;
    private final int pid;
    private final String role, epoch = UUID.randomUUID().toString();
    private final AtomicLong lost = new AtomicLong(), sequence = new AtomicLong();
    private volatile String guest = "host";
    private volatile long generation;

    DiagnosticJournal(File root, int pid, String role) throws IOException {
        this.root = root.getCanonicalFile(); this.pid = pid; this.role = token(role);
        if (Files.isSymbolicLink(root.toPath()) || (!root.isDirectory() && !root.mkdirs()))
            throw new IOException("Diagnostic directory is unavailable");
        enabled = new File(this.root, "enabled"); lock = new File(this.root, "lock");
    }

    boolean enabled() {
        try { return Files.isRegularFile(enabled.toPath(), LinkOption.NOFOLLOW_LINKS); }
        catch (SecurityException unavailable) { return false; }
    }
    void bound(String name, long value) { guest = token(name); generation = value; }
    long next() { return sequence.incrementAndGet(); }
    long lost() { return lost.get(); }

    synchronized void setEnabled(boolean value) throws IOException {
        try (FileChannel channel = channel(lock); FileLock ignored = channel.lock()) {
            if (value) { try (FileChannel marker = channel(enabled)) { marker.force(true); } }
            else Files.deleteIfExists(enabled.toPath());
        }
    }

    synchronized void clear() throws IOException {
        try (FileChannel channel = channel(lock); FileLock ignored = channel.lock()) {
            for (File file : logFiles()) Files.deleteIfExists(file.toPath());
            lost.set(0);
        }
    }

    synchronized void record(String area, String operation, long id, long value, Throwable error) {
        if (!enabled()) return;
        long dropped = 0;
        try (FileChannel channel = channel(lock); FileLock guard = recordLock(channel,
                "account_callback".equals(area) && operation != null
                        && (operation.endsWith(".result_forwarded") || operation.endsWith(".error_forwarded") || error != null))) {
            if (guard == null) { lost.incrementAndGet(); return; }
            if (!enabled()) return;
            dropped = lost.getAndSet(0);
            StringBuilder row = new StringBuilder(512).append("{\"v\":1,\"wall_ms\":").append(System.currentTimeMillis())
                    .append(",\"mono_ns\":").append(System.nanoTime()).append(",\"pid\":").append(pid)
                    .append(",\"thread\":").append(Thread.currentThread().getId())
                    .append(",\"epoch\":\"").append(epoch).append("\",\"role\":\"").append(role)
                    .append("\",\"guest\":\"").append(guest).append("\",\"generation\":").append(generation)
                    .append(",\"area\":\"").append(token(area)).append("\",\"operation\":\"").append(token(operation))
                    .append("\",\"id\":").append(id).append(",\"value\":").append(value)
                    .append(",\"dropped\":").append(dropped).append(",\"error\":\"");
            if (error != null) {
                Throwable cause = error;
                for (int i = 0; i < 4; i++) {
                    if (i > 0) row.append(';');
                    row.append(token(cause.getClass().getName()));
                    if (cause.getCause() == null || cause.getCause() == cause) break;
                    cause = cause.getCause();
                }
            }
            row.append("\",\"engine_stack\":\"");
            appendSafeStack(row, error);
            byte[] bytes = row.append("\"}\n").toString().getBytes(StandardCharsets.UTF_8);
            if (error != null) append("errors", ERROR_SEGMENTS, ERROR_BYTES, bytes, true);
            append("events", SEGMENTS, SEGMENT_BYTES, bytes, false);
        } catch (IOException | RuntimeException failure) { lost.addAndGet(dropped + 1); }
    }

    private static FileLock recordLock(FileChannel channel, boolean completedCallback) throws IOException {
        long deadline = System.nanoTime() + (completedCallback ? 100_000_000L : 0);
        do {
            try {
                FileLock acquired = channel.tryLock();
                if (acquired != null) return acquired;
            } catch (java.nio.channels.OverlappingFileLockException busy) {
                // Another journal in this process uses the same cross-process lock.
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) return null;
            java.util.concurrent.locks.LockSupport.parkNanos(Math.min(remaining, 1_000_000L));
        } while (true);
    }

    void export(OutputStream destination, int sdk, long version, String build) throws IOException {
        File[] files = logFiles(); byte[][] snapshot = new byte[files.length][];
        boolean collecting; long dropped;
        synchronized (this) {
            try (FileChannel channel = channel(lock); FileLock ignored = channel.lock()) {
                collecting = enabled(); dropped = lost();
                for (int i = 0; i < files.length; i++) {
                    File file = files[i];
                    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
                    int limit = file.getName().startsWith("errors.") ? ERROR_BYTES : SEGMENT_BYTES;
                    try (java.io.InputStream input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS);
                            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (count > limit - bytes.size()) throw new IOException("Diagnostic segment exceeds its bound");
                            bytes.write(buffer, 0, count);
                        }
                        snapshot[i] = bytes.toByteArray();
                    }
                }
            }
        }
        // Document providers may block: neither the process monitor nor the shared lock crosses this boundary.
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"schema\":1,\"sdk\":" + sdk + ",\"app_version\":" + version
                    + ",\"build\":\"" + token(build) + "\",\"debug_enabled\":" + collecting + ",\"local_dropped\":" + dropped
                    + ",\"max_event_bytes\":" + (SEGMENTS * SEGMENT_BYTES + ERROR_SEGMENTS * ERROR_BYTES) + "}\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 0; i < files.length; i++) {
                if (snapshot[i] == null) continue;
                zip.putNextEntry(new ZipEntry(files[i].getName())); zip.write(snapshot[i]);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("coverage.txt"));
            zip.write(("Engine IPC, installer, jobs, native bootstrap and connectivity boundary events.\n"
                    + "No accounts, tokens, passwords, APKs, databases, HTTP bodies, URLs or raw logcat.\n"
                    + "HTTP: optional last 1000 own-UID log lines, 3 second read budget; known Play route/status only.\n"
                    + "Missing HTTP logs are not proof of connectivity. No packet/TLS/body interception.\n"
                    + "Guest-internal errors absent from these logs and native tombstones require separate evidence.\n"
                    + "A missing event is not success. dropped records indicate contention or I/O failure.\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void appendSafeStack(StringBuilder row, Throwable error) {
        if (error == null) return;
        java.util.List<Throwable> causes = new java.util.ArrayList<>(4);
        for (Throwable cause = error; cause != null && causes.size() < 4; cause = cause.getCause()) {
            causes.add(cause);
            if (cause.getCause() == cause) break;
        }
        java.util.Set<StackTraceElement> framework = new java.util.LinkedHashSet<>();
        // Reserve space for the deepest known framework boundary, even when the outer stack is all engine code.
        for (int depth = causes.size() - 1; depth >= 0 && framework.size() < 4; depth--) {
            StackTraceElement[] frames = causes.get(depth).getStackTrace();
            for (int i = 0; i < frames.length && i < 64 && framework.size() < 4; i++) {
                String type = frames[i].getClassName();
                if (type.startsWith("android.app.") || type.startsWith("android.media.") || type.startsWith("android.accounts.")
                        || type.startsWith("android.appwidget.") || type.startsWith("com.android.internal.appwidget.")
                        || type.startsWith("android.security.net.config.") || type.startsWith("javax.net.ssl.")
                        || type.startsWith("com.android.org.conscrypt.")) framework.add(frames[i]);
            }
        }
        int count = 0;
        StackTraceElement[] own = error.getStackTrace();
        for (int i = 0; i < own.length && i < 64 && count < 8 - framework.size(); i++) {
            if (!own[i].getClassName().startsWith("io.github.xmy953538104.mybox.")) continue;
            if (count++ > 0) row.append(';');
            appendFrame(row, own[i]);
        }
        for (StackTraceElement frame : framework) {
            if (count++ > 0) row.append(';');
            appendFrame(row, frame);
        }
    }
    private static void appendFrame(StringBuilder row, StackTraceElement frame) {
        row.append(token(frame.getClassName())).append('.').append(token(frame.getMethodName()))
                .append(':').append(frame.getLineNumber());
    }

    private void append(String prefix, int segments, int limit, byte[] bytes, boolean sync) throws IOException {
        File current = segment(prefix, 0);
        if (current.length() + bytes.length > limit) {
            Files.deleteIfExists(segment(prefix, segments - 1).toPath());
            for (int i = segments - 2; i >= 0; i--) if (segment(prefix, i).exists())
                Files.move(segment(prefix, i).toPath(), segment(prefix, i + 1).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        try (FileChannel output = FileChannel.open(current.toPath(), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            if (sync) output.force(false);
        }
    }
    private File[] logFiles() {
        File[] files = new File[ERROR_SEGMENTS + SEGMENTS]; int next = 0;
        for (int i = ERROR_SEGMENTS - 1; i >= 0; i--) files[next++] = segment("errors", i);
        for (int i = SEGMENTS - 1; i >= 0; i--) files[next++] = segment("events", i);
        return files;
    }
    private File segment(String prefix, int index) { return new File(root, prefix + "." + index + ".jsonl"); }
    private static FileChannel channel(File file) throws IOException {
        return FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    private static String token(String value) {
        return value != null && value.length() <= 180 && value.matches("[A-Za-z0-9_.$<>-]+") ? value : "redacted";
    }
}
