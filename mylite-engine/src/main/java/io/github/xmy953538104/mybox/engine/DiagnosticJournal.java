package io.github.xmy953538104.mybox.engine;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
            for (int i = 0; i < SEGMENTS; i++) Files.deleteIfExists(segment(i).toPath());
            lost.set(0);
        }
    }

    synchronized void record(String area, String operation, long id, long value, Throwable error) {
        if (!enabled()) return;
        long dropped = 0;
        try (FileChannel channel = channel(lock); FileLock guard = channel.tryLock()) {
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
            if (error != null) {
                int count = 0;
                for (StackTraceElement frame : error.getStackTrace()) {
                    if (!frame.getClassName().startsWith("io.github.xmy953538104.mybox.")) continue;
                    if (count++ > 0) row.append(';');
                    row.append(token(frame.getClassName())).append('.').append(token(frame.getMethodName()))
                            .append(':').append(frame.getLineNumber());
                    if (count == 8) break;
                }
            }
            byte[] bytes = row.append("\"}\n").toString().getBytes(StandardCharsets.UTF_8);
            File current = segment(0);
            if (current.length() + bytes.length > SEGMENT_BYTES) {
                Files.deleteIfExists(segment(SEGMENTS - 1).toPath());
                for (int i = SEGMENTS - 2; i >= 0; i--) if (segment(i).exists())
                    Files.move(segment(i).toPath(), segment(i + 1).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try (FileChannel output = FileChannel.open(current.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                if (error != null) output.force(false);
            }
        } catch (IOException | RuntimeException failure) { lost.addAndGet(dropped + 1); }
    }

    synchronized void export(OutputStream destination, int sdk, long version) throws IOException {
        try (FileChannel channel = channel(lock); FileLock ignored = channel.lock();
                ZipOutputStream zip = new ZipOutputStream(destination)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"schema\":1,\"sdk\":" + sdk + ",\"app_version\":" + version
                    + ",\"debug_enabled\":" + enabled() + ",\"local_dropped\":" + lost()
                    + ",\"max_event_bytes\":" + (SEGMENTS * SEGMENT_BYTES) + "}\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = SEGMENTS - 1; i >= 0; i--) {
                File file = segment(i);
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
                if (file.length() > SEGMENT_BYTES) throw new IOException("Diagnostic segment exceeds its bound");
                zip.putNextEntry(new ZipEntry(file.getName()));
                try (java.io.InputStream input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                }
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

    private File segment(int index) { return new File(root, "events." + index + ".jsonl"); }
    private static FileChannel channel(File file) throws IOException {
        return FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    private static String token(String value) {
        return value != null && value.length() <= 180 && value.matches("[A-Za-z0-9_.$<>-]+") ? value : "redacted";
    }
}
