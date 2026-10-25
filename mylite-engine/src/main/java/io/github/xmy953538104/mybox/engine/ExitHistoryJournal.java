package io.github.xmy953538104.mybox.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import java.util.Arrays;

/** Server-derived process ownership, independent of Android so ambiguity and corruption are testable. */
final class ExitHistoryJournal {
    interface Writer { void write(byte[] bytes) throws IOException; }
    static final class Clock {
        final String boot; final long wall, elapsed;
        Clock(String boot, long wall, long elapsed) { this.boot = boot; this.wall = wall; this.elapsed = elapsed; }
    }
    static final class Row {
        final String key, physicalProcess; final int pid; final long timestamp;
        Row(String key, String physicalProcess, int pid, long timestamp) {
            this.key = key; this.physicalProcess = physicalProcess; this.pid = pid; this.timestamp = timestamp;
        }
    }
    static final class Owner {
        final String epoch, process;
        Owner(String epoch, String process) { this.epoch = epoch; this.process = process; }
    }
    private static final class Install {
        String name, epoch; int appId; long first;
    }
    private static final class Reservation {
        String epoch, process, physical, boot; int pid; long generation, start, elapsed;
        boolean retired, eligible = true; List<String> baseline = new ArrayList<>();
    }
    private static final class Saved {
        String key, epoch, process;
    }
    private static final class State {
        Clock clock; long discontinuities;
        List<Install> installs = new ArrayList<>(); List<Reservation> reservations = new ArrayList<>();
        List<Saved> saved = new ArrayList<>();
    }
    private static final int MAGIC = 0x4d584531, LIMIT = 256, INSTALL_LIMIT = LIMIT * 2 + 1, MAX_BYTES = 1024 * 1024;
    private final Writer writer;
    private State state;

    ExitHistoryJournal(byte[] bytes, Writer writer) throws IOException {
        this.writer = writer; state = bytes == null ? new State() : decode(bytes);
        // A replacement server cannot retain live Binder ownership from the old server.
        if (bytes != null) for (Reservation record : state.reservations) record.retired = true;
    }

    synchronized String epoch(String name, int appId, long first) {
        Install found = find(state, name, appId, first);
        if (found == null) throw new SecurityException("Exit history installation is not registered");
        return found.epoch;
    }

    synchronized boolean seal(String name, int appId, long first, String process, String physical, int pid,
                           long generation, Clock clock, List<String> baseline) throws IOException {
        if (pid <= 0 || generation <= 0 || name == null || process == null || physical == null
                || baseline.size() > LIMIT || first < 0) throw new IOException("Invalid exit history reservation");
        State next = copy(); sample(next, clock);
        if (next.reservations.size() == LIMIT) {
            Reservation retired = null;
            for (Reservation old : next.reservations) if (old.retired) { retired = old; break; }
            if (retired == null) throw new IOException("Exit history process quota exceeded");
            next.reservations.remove(retired);
        }
        pruneInstalls(next);
        Install install = find(next, name, appId, first);
        if (install == null) {
            if (next.installs.size() == INSTALL_LIMIT) throw new IOException("Exit history installation quota exceeded");
            install = new Install(); install.name = name; install.appId = appId; install.first = first;
            install.epoch = UUID.randomUUID().toString(); next.installs.add(install);
        }
        for (Reservation old : next.reservations) {
            if (!old.retired && old.pid == pid && old.boot.equals(clock.boot)) {
                throw new IOException("Exit history PID reservation is still active");
            }
        }
        Reservation record = new Reservation(); record.epoch = install.epoch; record.process = process;
        record.physical = physical; record.pid = pid; record.generation = generation; record.boot = clock.boot;
        record.start = clock.wall; record.elapsed = clock.elapsed; record.baseline.addAll(baseline);
        // OS timestamps can change after death. A reused PID/slot or existing PID baseline is not a new identity proof.
        if (!baseline.isEmpty()) record.eligible = false;
        for (Reservation old : next.reservations) {
            if (old.pid == pid && old.physical.equals(physical) && old.boot.equals(clock.boot)) {
                old.eligible = false; record.eligible = false;
            }
        }
        next.reservations.add(record); commit(next); return record.eligible;
    }

    synchronized void retired(int pid, long generation, Clock clock) throws IOException {
        State next = copy(); sample(next, clock);
        for (Reservation record : next.reservations) if (record.pid == pid && record.generation == generation
                && record.boot.equals(clock.boot) && !record.retired) record.retired = true;
        commit(next);
    }

    synchronized Map<String, Owner> resolve(String name, int appId, long first, List<Row> rows, Clock clock) throws IOException {
        if (rows.size() > 4096) throw new IOException("OS exit history exceeds supported bound");
        State next = copy(); sample(next, clock);
        Install install = find(next, name, appId, first);
        if (install == null) throw new SecurityException("Exit history caller has no installation epoch");
        Map<String, Owner> answer = new HashMap<>();
        for (Row row : rows) {
            Saved existing = null;
            for (Saved saved : next.saved) if (saved.key.equals(row.key)) { existing = saved; break; }
            if (existing == null) {
                Reservation selected = null;
                for (Reservation candidate : next.reservations) {
                    if (!candidate.eligible || !candidate.boot.equals(clock.boot) || candidate.pid != row.pid
                            || !candidate.physical.equals(row.physicalProcess) || row.timestamp < candidate.start
                            || row.timestamp > clock.wall || candidate.baseline.contains(row.key)) continue;
                    boolean superseded = false;
                    for (Reservation later : next.reservations) {
                        if (later != candidate && later.boot.equals(candidate.boot) && later.pid == candidate.pid
                                && later.elapsed > candidate.elapsed && row.timestamp >= later.start) { superseded = true; break; }
                    }
                    if (superseded) continue;
                    if (selected != null) { selected = null; break; }
                    selected = candidate;
                }
                if (selected != null) {
                    existing = new Saved(); existing.key = row.key; existing.epoch = selected.epoch; existing.process = selected.process;
                    if (next.saved.size() == LIMIT) next.saved.remove(0);
                    next.saved.add(existing);
                }
            }
            if (existing != null && install.epoch.equals(existing.epoch)) answer.put(row.key, new Owner(existing.epoch, existing.process));
        }
        commit(next); return answer;
    }

    synchronized long discontinuities() { return state.discontinuities; }

    // A future real uninstall must retire this epoch before publishing a replacement installation.
    private static Install find(State state, String name, int appId, long first) {
        for (Install value : state.installs) if (value.name.equals(name) && value.appId == appId && value.first == first) return value;
        return null;
    }

    private static void sample(State state, Clock clock) throws IOException {
        if (clock.boot == null || !clock.boot.matches("[a-f0-9-]{36}") || clock.wall <= 0 || clock.elapsed < 0) {
            throw new IOException("Exit history clock identity is unavailable");
        }
        Clock old = state.clock;
        if (old != null && (!old.boot.equals(clock.boot) || clock.elapsed < old.elapsed
                || Math.abs((clock.wall - old.wall) - (clock.elapsed - old.elapsed)) > 1000)) {
            state.discontinuities++;
            for (Reservation record : state.reservations) record.eligible = false;
        }
        // Retired records remain correlatable after server restart, but only within a continuous boot/clock.
        state.clock = clock;
    }

    private State copy() throws IOException { return decode(encode(state)); }
    private void commit(State next) throws IOException { pruneInstalls(next); writer.write(encode(next)); state = next; }
    private static void pruneInstalls(State state) {
        state.installs.removeIf(install -> {
            for (Reservation record : state.reservations) if (record.epoch.equals(install.epoch)) return false;
            for (Saved record : state.saved) if (record.epoch.equals(install.epoch)) return false;
            return true;
        });
    }

    private static byte[] encode(State state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeBoolean(state.clock != null);
            if (state.clock != null) { out.writeUTF(state.clock.boot); out.writeLong(state.clock.wall); out.writeLong(state.clock.elapsed); }
            out.writeLong(state.discontinuities); out.writeInt(state.installs.size());
            for (Install value : state.installs) { out.writeUTF(value.name); out.writeInt(value.appId); out.writeLong(value.first); out.writeUTF(value.epoch); }
            out.writeInt(state.reservations.size());
            for (Reservation value : state.reservations) {
                out.writeUTF(value.epoch); out.writeUTF(value.process); out.writeUTF(value.physical); out.writeUTF(value.boot);
                out.writeInt(value.pid); out.writeLong(value.generation); out.writeLong(value.start); out.writeLong(value.elapsed);
                out.writeBoolean(value.retired); out.writeBoolean(value.eligible); out.writeInt(value.baseline.size());
                for (String key : value.baseline) out.writeUTF(key);
            }
            out.writeInt(state.saved.size());
            for (Saved value : state.saved) { out.writeUTF(value.key); out.writeUTF(value.epoch); out.writeUTF(value.process); }
        }
        if (bytes.size() + 32 > MAX_BYTES) throw new IOException("Exit history journal exceeds supported bound");
        byte[] payload = bytes.toByteArray(); bytes.write(digest(payload)); return bytes.toByteArray();
    }

    private static State decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES || bytes.length < 37) throw new IOException("Invalid exit history journal size");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(payload), Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length))) {
            throw new IOException("Exit history journal checksum mismatch");
        }
        State state = new State();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) throw new IOException("Unknown exit history journal format");
            if (input.readBoolean()) state.clock = new Clock(input.readUTF(), input.readLong(), input.readLong());
            state.discontinuities = input.readLong();
            for (int i = count(input, INSTALL_LIMIT); i > 0; i--) {
                Install value = new Install(); value.name = input.readUTF(); value.appId = input.readInt(); value.first = input.readLong();
                value.epoch = input.readUTF(); state.installs.add(value);
            }
            for (int i = count(input); i > 0; i--) {
                Reservation value = new Reservation(); value.epoch = input.readUTF(); value.process = input.readUTF();
                value.physical = input.readUTF(); value.boot = input.readUTF(); value.pid = input.readInt();
                value.generation = input.readLong(); value.start = input.readLong(); value.elapsed = input.readLong();
                value.retired = input.readBoolean(); value.eligible = input.readBoolean();
                for (int j = count(input); j > 0; j--) value.baseline.add(input.readUTF());
                state.reservations.add(value);
            }
            for (int i = count(input); i > 0; i--) {
                Saved value = new Saved(); value.key = input.readUTF(); value.epoch = input.readUTF(); value.process = input.readUTF(); state.saved.add(value);
            }
            if (input.available() != 0) throw new IOException("Trailing exit history journal bytes");
        }
        return state;
    }
    private static int count(DataInputStream input) throws IOException {
        return count(input, LIMIT);
    }
    private static int count(DataInputStream input, int limit) throws IOException {
        int count = input.readInt(); if (count < 0 || count > limit) throw new IOException("Invalid exit history journal count"); return count;
    }
    private static byte[] digest(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IOException("SHA-256 is unavailable", unavailable); }
    }
}
