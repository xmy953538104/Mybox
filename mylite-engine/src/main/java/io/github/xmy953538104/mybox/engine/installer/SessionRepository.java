package io.github.xmy953538104.mybox.engine.installer;

import io.github.xmy953538104.mybox.engine.CallerIdentity;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.packages.PackageValidationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Own session journal, bounded staging and ownership rules; all publication uses PackageStore. */
public final class SessionRepository {
    public static final long MAX_FILE_BYTES = 1024L * 1024 * 1024;
    public static final long MAX_SESSION_BYTES = 2 * MAX_FILE_BYTES;
    public static final long MAX_STAGING_BYTES = 4 * MAX_FILE_BYTES;
    private static final int MAX_ACTIVE = 32, MAX_PER_OWNER = 8, MAX_HISTORY = 128, MAX_FILES = 256, MAX_HANDLES = 64;
    private final File root;
    private final InstallerBackend backend;
    private final InstallerAuthorization authorization;
    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, Record> sessions = new LinkedHashMap<>();
    private long stagingBytes;
    private int handles;

    public SessionRepository(File root, InstallerBackend backend, InstallerAuthorization authorization) throws IOException {
        this.root = root.getCanonicalFile(); this.backend = backend; this.authorization = authorization;
        directory(this.root);
        File[] children = this.root.listFiles();
        if (children == null) throw new IOException("Installer journal cannot be listed");
        for (File child : children) {
            if (!child.getName().matches("[1-9][0-9]{0,9}")) continue;
            if (Files.isSymbolicLink(child.toPath()) || !child.isDirectory()) throw new IOException("Invalid installer session directory");
            if (!new File(child, "session.properties").exists() && discardUnpublishedEmptyDirectory(child)) continue;
            removeJournalTemporaries(child);
            Record record = load(child);
            if (sessions.put(record.id, record) != null || sessions.size() > MAX_ACTIVE + MAX_HISTORY) throw new IOException("Installer journal exceeds session limit");
            for (String name : record.sizes.keySet()) {
                File input = entry(record, name);
                long size = input.isFile() ? Files.size(input.toPath()) : 0;
                if (size > MAX_FILE_BYTES) throw new IOException("Staged APK exceeds file limit");
                record.sizes.put(name, size); stagingBytes += size;
            }
            if (bytes(record) > MAX_SESSION_BYTES || stagingBytes > MAX_STAGING_BYTES) throw new IOException("Installer staging exceeds size limit");
            if ("COMMITTING".equals(record.state)) {
                InstalledPackage installed = backend.findPublished(files(record), record.request.expectedPackage, record.owner);
                record.state = installed == null ? "FAILED" : "SUCCEEDED";
                record.status = installed == null ? 1 : 0;
                if (installed != null) record.packageName = installed.packageName;
                persist(record);
            }
            if (terminal(record)) clearStaging(record);
        }
    }

    public synchronized int create(CallerIdentity caller, InstallRequest request) throws IOException {
        requireCurrent(caller); authorization.requireApproved(caller); pruneHistory();
        int active = 0, owned = 0;
        for (Record record : sessions.values()) if (!terminal(record)) {
            active++; if (record.owner.equals(caller.packageName)) owned++;
        }
        if (active >= MAX_ACTIVE || owned >= MAX_PER_OWNER) throw new IllegalStateException("Too many active installation sessions");
        int id;
        do { id = random.nextInt(Integer.MAX_VALUE - 1) + 1; } while (sessions.containsKey(id) || new File(root, Integer.toString(id)).exists());
        Record record = new Record(id, caller.packageName, caller.certificateSha256, request, System.currentTimeMillis());
        directory(new File(root, Integer.toString(id))); directory(stage(record));
        try { persist(record); }
        catch (IOException failure) {
            Files.deleteIfExists(stage(record).toPath()); Files.deleteIfExists(new File(root, Integer.toString(id)).toPath());
            throw failure;
        }
        sessions.put(id, record); return id;
    }

    public synchronized SessionSnapshot get(CallerIdentity caller, int id) throws IOException {
        requireCurrent(caller); Record record = sessions.get(id);
        return record == null ? null : snapshot(owned(caller, id));
    }

    public synchronized List<SessionSnapshot> list(CallerIdentity caller) throws IOException {
        requireCurrent(caller); List<SessionSnapshot> result = new ArrayList<>();
        for (Record record : sessions.values()) if (record.owner.equals(caller.packageName)
                && record.certificates.equals(caller.certificateSha256)) result.add(snapshot(record));
        return Collections.unmodifiableList(result);
    }

    public synchronized String[] names(CallerIdentity caller, int id) throws IOException {
        Record record = owned(caller, id); readable(record);
        return record.sizes.keySet().toArray(new String[0]);
    }

    public synchronized void progress(CallerIdentity caller, int id, float value, boolean add) throws IOException {
        Record record = owned(caller, id); mutable(record);
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException("Installation progress is not finite");
        float previous = record.progress; record.progress = Math.max(0, Math.min(1, (add ? previous : 0) + value));
        try { persist(record); } catch (IOException failure) { record.progress = previous; throw failure; }
    }

    public synchronized void label(CallerIdentity caller, int id, String label) throws IOException {
        Record record = owned(caller, id); mutable(record);
        InstallRequest previous = record.request;
        record.request = new InstallRequest(previous.expectedPackage, previous.sizeBytes, label);
        try { persist(record); } catch (IOException failure) { record.request = previous; throw failure; }
    }

    public synchronized Lease openWrite(CallerIdentity caller, int id, String name, long offset, long length) throws IOException {
        Record record = owned(caller, id); mutable(record); safeName(name);
        if (offset < 0 || offset > MAX_FILE_BYTES || length < -1 || length > MAX_FILE_BYTES
                || (length >= 0 && offset > length)) throw new IllegalArgumentException("Invalid staged APK bounds");
        if (handles >= MAX_HANDLES || record.writers.containsKey(name)) throw new IllegalStateException("Staging file is already open or handle limit reached");
        if (!record.sizes.containsKey(name) && record.sizes.size() >= MAX_FILES) throw new IllegalStateException("Too many staged APK entries");
        File file = entry(record, name);
        FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean added = !record.sizes.containsKey(name);
        try {
            long existing = channel.size();
            if (added) { record.sizes.put(name, existing); stagingBytes += existing; }
            if (length >= 0 && length < existing) throw new IllegalArgumentException("Resume length cannot truncate staged APK");
            if (length > existing) {
                grow(record, name, length); channel.write(ByteBuffer.wrap(new byte[]{0}), length - 1);
            }
            persist(record);
            Lease lease = new Lease(record, name, channel, true, length < 0 ? MAX_FILE_BYTES : length);
            record.writers.put(name, lease); record.leases.add(lease); handles++; return lease;
        } catch (IOException | RuntimeException failure) {
            channel.close();
            if (added) { stagingBytes -= record.sizes.remove(name); Files.deleteIfExists(file.toPath()); }
            else {
                record.writeFailed = true;
                try { persist(record); } catch (IOException journalFailure) { failure.addSuppressed(journalFailure); }
            }
            throw failure;
        }
    }

    public synchronized Lease openRead(CallerIdentity caller, int id, String name) throws IOException {
        Record record = owned(caller, id); readable(record); safeName(name);
        if (!record.sizes.containsKey(name)) throw new IllegalArgumentException("Staged APK does not exist");
        if (handles >= MAX_HANDLES) throw new IllegalStateException("Staging handle limit reached");
        FileChannel channel = FileChannel.open(entry(record, name).toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        Lease lease = new Lease(record, name, channel, false, record.sizes.get(name));
        record.leases.add(lease); handles++; return lease;
    }

    public synchronized void seal(CallerIdentity caller, int id) throws IOException {
        Record record = owned(caller, id);
        if ("SEALED".equals(record.state)) return;
        mutable(record); canSeal(record);
        record.state = "SEALED";
        try { persist(record); } catch (IOException failure) { record.state = "OPEN"; throw failure; }
    }

    /** Returns a runnable plan only after a durable seal; caller may run it on a bounded worker. */
    public synchronized CommitPlan prepareCommit(CallerIdentity caller, int id) throws IOException {
        Record record = owned(caller, id); authorization.requireApproved(caller);
        if (!"OPEN".equals(record.state) && !"SEALED".equals(record.state)) throw new IllegalStateException("Installation session was already committed or abandoned");
        canSeal(record); String previous = record.state; record.state = "COMMITTING";
        try { persist(record); } catch (IOException failure) { record.state = previous; throw failure; }
        return new CommitPlan(record, caller, files(record));
    }

    public synchronized void abandon(CallerIdentity caller, int id) throws IOException {
        Record record = owned(caller, id);
        if ("COMMITTING".equals(record.state)) throw new IllegalStateException("A publishing installation cannot be abandoned");
        if (terminal(record)) return;
        String previous = record.state; record.state = "ABANDONED"; record.status = 3;
        try { persist(record); } catch (IOException failure) { record.state = previous; record.status = -1; throw failure; }
        for (Lease lease : new ArrayList<>(record.leases)) lease.close();
        clearStaging(record);
    }

    public final class Lease implements AutoCloseable {
        private final Record record;
        private final String name;
        private final FileChannel channel;
        private final boolean writer;
        private final long limit;
        private boolean closed;
        private Lease(Record record, String name, FileChannel channel, boolean writer, long limit) {
            this.record = record; this.name = name; this.channel = channel; this.writer = writer; this.limit = limit;
        }
        public long size() throws IOException { synchronized (SessionRepository.this) { available(); return channel.size(); } }
        public int read(long offset, byte[] data, int count) throws IOException {
            synchronized (SessionRepository.this) {
                available(); readable(record); bounds(offset, count, MAX_FILE_BYTES, data.length);
                int read = channel.read(ByteBuffer.wrap(data, 0, count), offset); return Math.max(0, read);
            }
        }
        public int write(long offset, byte[] data, int count) throws IOException {
            synchronized (SessionRepository.this) {
                available(); mutable(record);
                if (!writer) throw new IOException("Staging descriptor is read-only");
                bounds(offset, count, limit, data.length); grow(record, name, Math.max(record.sizes.get(name), offset + count));
                try {
                    ByteBuffer buffer = ByteBuffer.wrap(data, 0, count); int written = 0;
                    while (buffer.hasRemaining()) { int next = channel.write(buffer, offset + written); if (next <= 0) throw new IOException("Staged APK write made no progress"); written += next; }
                    return written;
                } catch (IOException failure) { rememberWriteFailure(record, failure); throw failure; }
            }
        }
        public void sync() throws IOException { synchronized (SessionRepository.this) { available(); try { channel.force(true); } catch (IOException failure) { rememberWriteFailure(record, failure); throw failure; } } }
        @Override public void close() throws IOException {
            synchronized (SessionRepository.this) {
                if (closed) return; closed = true;
                try { if (writer) channel.force(true); }
                catch (IOException failure) { rememberWriteFailure(record, failure); throw failure; }
                finally { try { channel.close(); } finally { handles--; record.leases.remove(this); if (writer) record.writers.remove(name); } }
            }
        }
        private void available() throws IOException { if (closed || terminal(record)) throw new IOException("Staging descriptor was revoked"); }
    }

    public final class CommitPlan {
        private final Record record;
        private final CallerIdentity caller;
        private final List<File> inputs;
        private boolean executed;
        private CommitPlan(Record record, CallerIdentity caller, List<File> inputs) { this.record = record; this.caller = caller; this.inputs = inputs; }
        public CommitOutcome run() {
            synchronized (this) { if (executed) throw new IllegalStateException("Commit plan was already executed"); executed = true; }
            int status; String message; InstalledPackage installed = null;
            try {
                synchronized (SessionRepository.this) { requireCurrent(caller); authorization.requireApproved(caller); }
                installed = backend.install(inputs, record.request.expectedPackage, caller); status = 0; message = "Installed";
            } catch (PackageValidationException invalid) { status = 4; message = "APK validation failed"; }
            catch (SecurityException denied) { status = 2; message = "Installer is no longer authorized"; }
            catch (IOException failure) { status = 1; message = "Installation could not be published"; }
            catch (RuntimeException failure) { status = 1; message = "Installation failed"; }
            boolean journaled = false;
            synchronized (SessionRepository.this) {
                record.state = status == 0 ? "SUCCEEDED" : "FAILED"; record.status = status; record.progress = 1;
                if (installed != null) record.packageName = installed.packageName;
                try {
                    persist(record); journaled = true;
                    for (Lease lease : new ArrayList<>(record.leases)) lease.close();
                    clearStaging(record);
                }
                catch (IOException failure) { /* Keep staged evidence so an interrupted journal can be reconciled on restart. */ }
            }
            return new CommitOutcome(snapshot(record), status, message, journaled);
        }
    }

    public static final class CommitOutcome {
        public final SessionSnapshot session;
        public final int status;
        public final String message;
        public final boolean journaled;
        CommitOutcome(SessionSnapshot session, int status, String message, boolean journaled) {
            this.session = session; this.status = status; this.message = message; this.journaled = journaled;
        }
    }

    private Record owned(CallerIdentity caller, int id) throws IOException {
        requireCurrent(caller); Record record = sessions.get(id);
        if (record == null) throw new IllegalArgumentException("Installation session does not exist");
        if (!record.owner.equals(caller.packageName) || !record.certificates.equals(caller.certificateSha256)) throw new SecurityException("Installation session belongs to another identity");
        return record;
    }
    private void requireCurrent(CallerIdentity caller) throws IOException {
        if (caller == null || caller.userId != 0 || !caller.certificateSha256.equals(backend.certificates(caller.packageName))) {
            throw new SecurityException("Installer caller is not a current virtual user-0 identity");
        }
    }
    private void canSeal(Record record) throws IOException {
        if (!record.writers.isEmpty()) throw new IllegalStateException("Staged APK streams are still open");
        if (record.writeFailed) throw new IOException("A staged APK write failed");
        if (record.sizes.isEmpty()) throw new IllegalStateException("Installation session contains no APKs");
        for (long size : record.sizes.values()) if (size <= 0) throw new IllegalStateException("Staged APK is empty");
    }
    private void rememberWriteFailure(Record record, IOException failure) {
        record.writeFailed = true;
        try { persist(record); } catch (IOException journalFailure) { failure.addSuppressed(journalFailure); }
    }
    private static void mutable(Record record) { if (!"OPEN".equals(record.state)) throw new SecurityException("Installation session is sealed or abandoned"); }
    private static void readable(Record record) { if (!"OPEN".equals(record.state) && !"SEALED".equals(record.state)) throw new SecurityException("Installation session is committed or abandoned"); }
    private static boolean terminal(Record record) { return "SUCCEEDED".equals(record.state) || "FAILED".equals(record.state) || "ABANDONED".equals(record.state); }
    private static void safeName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid staged APK entry name");
        }
    }
    private static void bounds(long offset, int count, long limit, int bufferSize) throws IOException {
        if (offset < 0 || count < 0 || count > bufferSize || offset > limit || count > limit - offset) throw new IOException("Staged APK write exceeds declared bounds");
    }
    private void grow(Record record, String name, long size) throws IOException {
        long old = record.sizes.getOrDefault(name, 0L), growth = size - old;
        if (growth <= 0) return;
        if (size > MAX_FILE_BYTES || growth > MAX_SESSION_BYTES - bytes(record) || growth > MAX_STAGING_BYTES - stagingBytes) throw new IOException("Installer staging quota exceeded");
        record.sizes.put(name, size); stagingBytes += growth;
    }
    private static long bytes(Record record) { long bytes = 0; for (long value : record.sizes.values()) bytes += value; return bytes; }
    private File stage(Record record) { return new File(root, record.id + "/stage"); }
    private File entry(Record record, String name) throws IOException {
        safeName(name); File parent = stage(record);
        if (Files.isSymbolicLink(parent.toPath()) || !parent.getCanonicalFile().equals(parent.getAbsoluteFile())) throw new IOException("Staging directory is not local");
        File file = new File(parent, name);
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Staged APK cannot be a symbolic link");
        return file;
    }
    private List<File> files(Record record) throws IOException { List<File> files = new ArrayList<>(); for (String name : record.sizes.keySet()) files.add(entry(record, name)); return files; }
    private static void directory(File directory) throws IOException {
        if (Files.isSymbolicLink(directory.toPath()) || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException("Installer directory cannot be created");
    }
    private static SessionSnapshot snapshot(Record record) {
        return new SessionSnapshot(record.id, record.owner, record.packageName, record.request.label, record.state,
                record.request.sizeBytes, record.createdAt, record.progress, record.status);
    }
    private void clearStaging(Record record) throws IOException {
        for (String name : new ArrayList<>(record.sizes.keySet())) {
            Files.deleteIfExists(entry(record, name).toPath()); stagingBytes -= record.sizes.get(name); record.sizes.put(name, 0L);
        }
    }
    private void pruneHistory() throws IOException {
        List<Record> history = new ArrayList<>(); for (Record record : sessions.values()) if (terminal(record)) history.add(record);
        history.sort(Comparator.comparingLong(record -> record.createdAt));
        while (history.size() >= MAX_HISTORY) {
            Record oldest = history.remove(0); clearStaging(oldest);
            Files.deleteIfExists(stage(oldest).toPath());
            Files.deleteIfExists(new File(root, oldest.id + "/session.properties").toPath());
            Files.deleteIfExists(new File(root, Integer.toString(oldest.id)).toPath()); sessions.remove(oldest.id);
        }
    }
    private void persist(Record record) throws IOException {
        Properties values = new Properties(); values.setProperty("format", "1"); values.setProperty("id", Integer.toString(record.id));
        values.setProperty("owner", record.owner); values.setProperty("certs", String.join(",", record.certificates));
        values.setProperty("target", record.request.expectedPackage == null ? "" : record.request.expectedPackage);
        values.setProperty("package", record.packageName == null ? "" : record.packageName);
        values.setProperty("size", Long.toString(record.request.sizeBytes)); values.setProperty("label", record.request.label == null ? "" : record.request.label);
        values.setProperty("state", record.state); values.setProperty("created", Long.toString(record.createdAt));
        values.setProperty("progress", Float.toString(record.progress)); values.setProperty("status", Integer.toString(record.status));
        values.setProperty("writeFailed", Boolean.toString(record.writeFailed));
        values.setProperty("entries", String.join(",", record.sizes.keySet()));
        File directory = new File(root, Integer.toString(record.id)); File temporary = new File(directory, "journal-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { values.store(output, "Mybox virtual installation session"); output.getFD().sync(); }
            Files.move(temporary.toPath(), new File(directory, "session.properties").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
    private Record load(File directory) throws IOException {
        File file = new File(directory, "session.properties");
        if (!file.isFile() || file.length() > 128 * 1024 || Files.isSymbolicLink(file.toPath())) throw new IOException("Invalid installer session journal");
        Properties values = new Properties(); try (FileInputStream input = new FileInputStream(file)) { values.load(input); }
        try {
            int id = Integer.parseInt(values.getProperty("id"));
            if (!"1".equals(values.getProperty("format")) || id <= 0 || !directory.getName().equals(Integer.toString(id))) throw new IllegalArgumentException();
            String owner = values.getProperty("owner");
            if (owner == null || !owner.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) throw new IllegalArgumentException();
            List<String> certificates = new ArrayList<>();
            for (String value : values.getProperty("certs").split(",")) { if (!value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException(); certificates.add(value); }
            InstallRequest request = new InstallRequest(empty(values.getProperty("target")), Long.parseLong(values.getProperty("size")), empty(values.getProperty("label")));
            Record record = new Record(id, owner, certificates, request, Long.parseLong(values.getProperty("created")));
            record.packageName = empty(values.getProperty("package")); record.state = values.getProperty("state");
            if (!("OPEN".equals(record.state) || "SEALED".equals(record.state) || "COMMITTING".equals(record.state) || terminal(record))) throw new IllegalArgumentException();
            record.progress = Float.parseFloat(values.getProperty("progress")); record.status = Integer.parseInt(values.getProperty("status"));
            String writeFailed = values.getProperty("writeFailed", "false");
            if (!writeFailed.equals("true") && !writeFailed.equals("false")) throw new IllegalArgumentException();
            record.writeFailed = Boolean.parseBoolean(writeFailed);
            if (!Float.isFinite(record.progress) || record.progress < 0 || record.progress > 1) throw new IllegalArgumentException();
            String entries = values.getProperty("entries");
            if (!entries.isEmpty()) for (String name : entries.split(",")) {
                safeName(name); if (record.sizes.put(name, 0L) != null || record.sizes.size() > MAX_FILES) throw new IllegalArgumentException();
            }
            return record;
        } catch (RuntimeException malformed) { throw new IOException("Malformed installer session journal", malformed); }
    }
    private static boolean discardUnpublishedEmptyDirectory(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Unpublished installer directory cannot be listed");
        for (File child : children) {
            if (child.getName().equals("stage") && child.isDirectory() && !Files.isSymbolicLink(child.toPath())) {
                File[] entries = child.listFiles(); if (entries == null || entries.length != 0) return false;
            } else if (!journalTemporary(child)) return false;
        }
        removeJournalTemporaries(directory);
        Files.deleteIfExists(new File(directory, "stage").toPath()); Files.delete(directory.toPath()); return true;
    }
    private static boolean journalTemporary(File file) {
        return file.getName().matches("journal-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.tmp")
                && file.isFile() && !Files.isSymbolicLink(file.toPath()) && file.length() <= 128 * 1024;
    }
    private static void removeJournalTemporaries(File directory) throws IOException {
        File[] children = directory.listFiles(); if (children == null) throw new IOException("Installer directory cannot be listed");
        for (File child : children) if (journalTemporary(child)) Files.delete(child.toPath());
    }
    private static String empty(String value) { return value == null || value.isEmpty() ? null : value; }
    private static final class Record {
        final int id;
        final String owner;
        final List<String> certificates;
        InstallRequest request;
        final long createdAt;
        final Map<String, Long> sizes = new LinkedHashMap<>();
        final Map<String, Lease> writers = new HashMap<>();
        final Set<Lease> leases = new HashSet<>();
        String state = "OPEN", packageName;
        float progress;
        int status = -1;
        boolean writeFailed;
        Record(int id, String owner, List<String> certificates, InstallRequest request, long createdAt) {
            this.id = id; this.owner = owner; this.certificates = new ArrayList<>(certificates); this.request = request;
            this.createdAt = createdAt; packageName = request.expectedPackage;
        }
    }
}
