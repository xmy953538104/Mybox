package io.github.xmy953538104.mybox.engine;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AtomicFile;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Durable, bounded capabilities. A signed delivery is independent of the live token map. */
final class OsPendingStore {
    static final String PAYLOAD = "mylite.alarm.snapshot", SIGNATURE = "mylite.alarm.mac";
    static final int MAX_PAYLOAD = 32_768, MAX_RECORDS = 256;
    static final String FILL_IN = "mylite.activity.fill_in", FILL_TYPE = "mylite.activity.fill_type";
    private static final int MAGIC = 0x4d4c5049, VERSION = 2, FILE_VERSION = 3, MAX_FILE = 10 * 1024 * 1024;
    private final Engine engine;
    private final AtomicFile file;
    private final byte[] key;
    final int boot;
    private Map<String, Record> records = new LinkedHashMap<>();
    private java.util.Set<String> activityClaims = new TreeSet<>();

    static final class Creator {
        final String packageName, generation;
        final int user, appId;
        final List<String> certificates;
        Creator(InstalledPackage pkg) {
            packageName = pkg.packageName; generation = pkg.baseApk.getAbsolutePath();
            user = 0; appId = pkg.appId;
            certificates = Collections.unmodifiableList(new ArrayList<>(pkg.certificateSha256));
        }
        private Creator(String name, String generation, int user, int appId, List<String> certificates) {
            this.packageName = name; this.generation = generation; this.user = user; this.appId = appId;
            this.certificates = Collections.unmodifiableList(certificates);
        }
        InstalledPackage requireCurrent(Engine engine) throws Exception {
            InstalledPackage pkg = engine.packages().get(packageName);
            if (user != 0 || pkg == null || appId != pkg.appId || !certificates.equals(pkg.certificateSha256)
                    || !generation.equals(pkg.baseApk.getAbsolutePath())) {
                throw new SecurityException("OS PendingIntent creator generation is no longer installed");
            }
            return pkg;
        }
        void requireCaller(CallerIdentity caller) {
            if (caller.pid <= 0 || caller.userId != user || caller.appId != appId
                    || !packageName.equals(caller.packageName) || !certificates.equals(caller.certificateSha256)) {
                throw new SecurityException("OS PendingIntent belongs to another creator");
            }
        }
    }

    static final class Snapshot {
        final String capId, keyId, resolvedType;
        final long revision;
        final int boot, requestCode, flags, senderType;
        final Creator creator;
        final Intent intent;
        Snapshot(String capId, String keyId, long revision, int boot, int requestCode,
                 int flags, Creator creator, Intent intent, String resolvedType) throws IOException {
            this(capId, keyId, revision, boot, requestCode, flags, creator, intent, resolvedType, ServerPendingIntents.BROADCAST);
        }
        Snapshot(String capId, String keyId, long revision, int boot, int requestCode,
                 int flags, Creator creator, Intent intent, String resolvedType, int senderType) throws IOException {
            this.capId = capId; this.keyId = keyId; this.revision = revision; this.boot = boot;
            this.requestCode = requestCode; this.flags = flags; this.creator = creator;
            this.senderType = senderType;
            this.intent = copyIntent(intent); this.resolvedType = resolvedType;
        }
    }

    static final class Record {
        final Snapshot snapshot;
        final String journal;
        Record(Snapshot snapshot, String journal) { this.snapshot = snapshot; this.journal = journal; }
    }

    OsPendingStore(Engine engine) throws IOException {
        this.engine = engine;
        boot = Settings.Global.getInt(engine.hostContext().getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) throw new IOException("Boot identity is unavailable; durable alarms cannot be enabled");
        File directory = new File(engine.hostContext().getFilesDir(), "mylite-os-pending");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create OS PendingIntent storage");
        file = new AtomicFile(new File(directory, "capabilities.bin"));
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            File witness = new File(directory, "initialized");
            if (witness.exists()) throw new IOException("OS PendingIntent key/store was lost");
            key = new byte[32]; new SecureRandom().nextBytes(key);
            persist(records);
            try (FileOutputStream stream = new FileOutputStream(witness)) { stream.write(FILE_VERSION); stream.getFD().sync(); }
        } else {
            byte[] bytes;
            try (java.io.InputStream input = file.openRead()) { bytes = boundedRead(input, MAX_FILE); }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (input.readInt() != MAGIC) throw new IOException("Unknown OS PendingIntent store schema");
                int version = input.readInt();
                if (version < 1 || version > FILE_VERSION) throw new IOException("Unknown OS PendingIntent store schema");
                key = blob(input, 32);
                if (key.length != 32) throw new IOException("Invalid OS PendingIntent signing key");
                int count = count(input, MAX_RECORDS);
                for (int i = 0; i < count; i++) {
                    Snapshot snapshot = decode(blob(input, MAX_PAYLOAD));
                    String journal = string(input);
                    if (records.put(snapshot.keyId, new Record(snapshot, journal)) != null) throw new IOException("Duplicate stored PendingIntent key");
                }
                if (version >= 3) {
                    int claimBoot = input.readInt();
                    if (claimBoot < 0) throw new IOException("Invalid Activity claim boot identity");
                    int claims = count(input, ActivityDeliveryClaims.MAX_CLAIMS);
                    for (int i = 0; i < claims; i++) {
                        String cap = string(input);
                        if (!ActivityDeliveryClaims.valid(cap)) throw new IOException("Invalid Activity delivery claim");
                        if (claimBoot == boot && !activityClaims.add(cap)) throw new IOException("Duplicate Activity delivery claim");
                    }
                }
                if (input.available() != 0) throw new IOException("Trailing OS PendingIntent store data");
            }
        }
    }

    // Only the server mutation queue calls these methods. Receive verification uses immutable key/bytes.
    Record get(String keyId) { return records.get(keyId); }
    List<Record> all() { return new ArrayList<>(records.values()); }
    void put(Snapshot snapshot, String journal) throws IOException {
        Map<String, Record> next = new LinkedHashMap<>(records);
        next.put(snapshot.keyId, new Record(snapshot, journal));
        if (next.size() > MAX_RECORDS) throw new IOException("OS PendingIntent quota exceeded");
        persist(next); records = next;
    }
    void remove(String keyId) throws IOException {
        Map<String, Record> next = new LinkedHashMap<>(records); next.remove(keyId);
        persist(next); records = next;
    }

    void claimActivity(Snapshot snapshot) throws IOException {
        if (snapshot.senderType != ServerPendingIntents.ACTIVITY) throw new SecurityException("Not an Activity delivery");
        if ((snapshot.flags & PendingIntent.FLAG_ONE_SHOT) == 0) return;
        java.util.Set<String> next = ActivityDeliveryClaims.consume(activityClaims, snapshot.capId);
        persist(records, next);
        activityClaims = next;
    }

    Intent envelope(Snapshot snapshot) throws Exception {
        byte[] bytes = encode(snapshot);
        return identity(snapshot).putExtra(PAYLOAD, bytes).putExtra(SIGNATURE, sign(bytes));
    }
    Intent identity(Snapshot snapshot) {
        boolean activity = snapshot.senderType == ServerPendingIntents.ACTIVITY;
        return new Intent(engine.hostContext(), activity ? OsPendingActivity.class : OsPendingReceiver.class)
                .setAction(engine.hostContext().getPackageName() + (activity ? ".MYLITE_OS_ACTIVITY" : ".MYLITE_OS_ALARM"))
                .setData(Uri.parse((activity ? "mylite-activity" : "mylite-alarm") + "://cap/" + snapshot.capId))
                .setFlags(snapshot.intent.getFlags());
    }
    Snapshot verify(Intent envelope) throws Exception {
        if (envelope == null || envelope.getClass() != Intent.class || envelope.getClipData() != null
                || envelope.getSelector() != null) throw new SecurityException("Invalid OS delivery envelope");
        byte[] bytes = envelope.getByteArrayExtra(PAYLOAD), signature = envelope.getByteArrayExtra(SIGNATURE);
        if (bytes == null || bytes.length > MAX_PAYLOAD || signature == null || signature.length != 32
                || !MessageDigest.isEqual(sign(bytes), signature)) throw new SecurityException("Invalid OS delivery signature");
        Snapshot snapshot = decode(bytes);
        if (snapshot.boot != boot || !identity(snapshot).filterEquals(envelope)) throw new SecurityException("OS delivery boot or identity changed");
        snapshot.creator.requireCurrent(engine);
        return snapshot;
    }

    Snapshot verifyCompletion(Intent envelope) throws Exception {
        if (envelope == null || envelope.getClass() != Intent.class) throw new SecurityException("Invalid OS completion envelope");
        Intent received = new Intent(envelope);
        // Android clears the manifest component before its terminal OnFinished callback.
        if (received.getComponent() == null) {
            Snapshot snapshot = decode(received.getByteArrayExtra(PAYLOAD));
            received.setComponent(new ComponentName(engine.hostContext(), snapshot.senderType == ServerPendingIntents.ACTIVITY
                    ? OsPendingActivity.class : OsPendingReceiver.class));
        }
        return verify(received);
    }

    private void persist(Map<String, Record> records) throws IOException {
        persist(records, activityClaims);
    }
    private void persist(Map<String, Record> records, java.util.Set<String> claims) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC); output.writeInt(FILE_VERSION); blob(output, key);
            output.writeInt(records.size());
            for (Record record : records.values()) { blob(output, encode(record.snapshot)); string(output, record.journal); }
            output.writeInt(boot); output.writeInt(claims.size());
            for (String claim : claims) string(output, claim);
        }
        if (bytes.size() > MAX_FILE) throw new IOException("OS PendingIntent storage limit exceeded");
        FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write(bytes.toByteArray()); file.finishWrite(stream); }
        catch (IOException failure) { if (stream != null) file.failWrite(stream); throw failure; }
    }
    private byte[] sign(byte[] bytes) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(bytes);
    }

    byte[] signJob(byte[] bytes) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update("mylite-job-v1\u0000".getBytes(StandardCharsets.UTF_8));
        return mac.doFinal(bytes);
    }

    static String keyId(Creator creator, int requestCode, int flags, Intent intent, String type) throws Exception {
        return keyId(creator, requestCode, flags, intent, type, ServerPendingIntents.BROADCAST);
    }
    static String keyId(Creator creator, int requestCode, int flags, Intent intent, String type, int senderType) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            creator(output, creator); output.writeInt(requestCode); output.writeInt(flags);
            intent(output, intent.cloneFilter().setFlags(0)); string(output, type);
            if (senderType != ServerPendingIntents.BROADCAST) output.writeInt(senderType);
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        StringBuilder value = new StringBuilder(64);
        for (byte octet : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", octet & 255));
        return value.toString();
    }

    static void validate(Intent intent, int flags, Creator creator) throws IOException {
        int allowedFlags = Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_NO_ABORT;
        if (intent == null || intent.getClass() != Intent.class || intent.getComponent() == null
                || !creator.packageName.equals(intent.getComponent().getPackageName())
                || intent.getPackage() != null && !creator.packageName.equals(intent.getPackage())
                || intent.getSelector() != null || intent.getClipData() != null || intent.getSourceBounds() != null
                || (intent.getFlags() & ~allowedFlags) != 0 || (flags & PendingIntent.FLAG_IMMUTABLE) == 0
                || (flags & PendingIntent.FLAG_MUTABLE) != 0 || creator.user != 0) {
            throw new IllegalArgumentException("OS alarms require an immutable, explicit same-package user-0 manifest broadcast");
        }
        copyIntent(intent); // Reject unsupported values before any OS or disk mutation.
    }

    static void validateActivity(Intent intent, int flags, Creator creator) throws IOException {
        int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        if (intent == null || intent.getClass() != Intent.class || intent.getComponent() == null
                || !creator.packageName.equals(intent.getComponent().getPackageName())
                || intent.getPackage() != null && !creator.packageName.equals(intent.getPackage())
                || (intent.getFlags() & grants) != 0 || creator.user != 0) {
            throw new SecurityException("Activity capability requires an explicit, ungranted same-package user-0 target");
        }
        if ((flags & PendingIntent.FLAG_IMMUTABLE) != 0 && (flags & PendingIntent.FLAG_MUTABLE) != 0)
            throw new IllegalArgumentException("Conflicting capability mutability flags");
        copyIntent(intent);
    }

    static Intent activityIntent(Snapshot snapshot, Intent outer) throws IOException {
        Intent result = new Intent(snapshot.intent);
        if ((snapshot.flags & PendingIntent.FLAG_IMMUTABLE) != 0) return result;
        byte[] packed = outer.getByteArrayExtra(FILL_IN);
        Intent fill;
        if (packed != null) {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packed))) {
                fill = intent(input);
                if (input.available() != 0) throw new IOException("Trailing Activity fill-in data");
            }
        } else {
            // OS notification RemoteInput supplies extras without our guest send adapter.
            Bundle extras = outer.getExtras() == null ? null : new Bundle(outer.getExtras());
            if (extras != null) {
                extras.remove(PAYLOAD); extras.remove(SIGNATURE); extras.remove(FILL_IN); extras.remove(FILL_TYPE);
            }
            fill = new Intent(); if (extras != null) fill.putExtras(extras);
        }
        result.fillIn(fill, snapshot.flags & 0xff);
        validateActivity(result, snapshot.flags, snapshot.creator);
        return result;
    }

    static Intent activityFillIn(Intent fill, String type) throws IOException {
        if (fill == null) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) { intent(output, fill); }
        if (bytes.size() > MAX_PAYLOAD) throw new IOException("Activity fill-in exceeds limit");
        return new Intent().putExtra(FILL_IN, bytes.toByteArray()).putExtra(FILL_TYPE, type);
    }

    static byte[] encode(Snapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(VERSION); string(output, snapshot.capId); string(output, snapshot.keyId);
            output.writeLong(snapshot.revision); output.writeInt(snapshot.boot); output.writeInt(snapshot.requestCode);
            output.writeInt(snapshot.flags); creator(output, snapshot.creator); intent(output, snapshot.intent);
            string(output, snapshot.resolvedType);
            output.writeInt(snapshot.senderType);
        }
        if (bytes.size() > MAX_PAYLOAD) throw new IOException("OS PendingIntent payload exceeds 32 KiB");
        return bytes.toByteArray();
    }
    static Snapshot decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_PAYLOAD) throw new IOException("Oversized OS PendingIntent payload");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = input.readInt();
            if (version < 1 || version > VERSION) throw new IOException("Unknown OS delivery schema");
            String cap = string(input), keyId = string(input); long revision = input.readLong();
            int boot = input.readInt(), request = input.readInt(), flags = input.readInt();
            Creator creator = creator(input); Intent intent = intent(input); String type = string(input);
            int senderType = version >= 2 ? input.readInt() : ServerPendingIntents.BROADCAST;
            if (input.available() != 0 || cap == null || !cap.matches("[a-f0-9-]{36}")
                    || keyId == null || !keyId.matches("[a-f0-9]{64}") || revision < 1 || boot < 0) throw new IOException("Invalid OS delivery identity");
            if (senderType == ServerPendingIntents.ACTIVITY) validateActivity(intent, flags, creator);
            else if (senderType == ServerPendingIntents.BROADCAST) validate(intent, flags, creator);
            else throw new IOException("Unsupported durable sender type");
            return new Snapshot(cap, keyId, revision, boot, request, flags, creator, intent, type, senderType);
        }
    }
    private static void creator(DataOutputStream output, Creator creator) throws IOException {
        string(output, creator.packageName); string(output, creator.generation);
        output.writeInt(creator.user); output.writeInt(creator.appId); output.writeInt(creator.certificates.size());
        for (String certificate : creator.certificates) string(output, certificate);
    }
    private static Creator creator(DataInputStream input) throws IOException {
        String name = string(input), generation = string(input); int user = input.readInt(), appId = input.readInt();
        int count = count(input, 8); List<String> certificates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String certificate = string(input);
            if (certificate == null || !certificate.matches("[a-fA-F0-9]{64}")) throw new IOException("Invalid stored certificate");
            certificates.add(certificate);
        }
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                || generation == null || user != 0 || appId < 10000 || certificates.isEmpty()) throw new IOException("Invalid saved creator");
        return new Creator(name, generation, user, appId, certificates);
    }
    private static Intent copyIntent(Intent intent) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) { intent(output, intent); }
        if (bytes.size() > MAX_PAYLOAD) throw new IOException("Oversized persistent Intent");
        return intent(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }
    private static void intent(DataOutputStream output, Intent intent) throws IOException {
        if (intent.getClass() != Intent.class || intent.getSelector() != null || intent.getClipData() != null
                || intent.getSourceBounds() != null) throw new IOException("Unsupported persistent Intent structure");
        string(output, intent.getAction()); string(output, intent.getDataString()); string(output, intent.getType());
        string(output, intent.getPackage()); string(output, intent.getComponent() == null ? null : intent.getComponent().flattenToString());
        string(output, Build.VERSION.SDK_INT >= 29 ? intent.getIdentifier() : null);
        output.writeInt(intent.getFlags()); TreeSet<String> categories = new TreeSet<>();
        if (intent.getCategories() != null) categories.addAll(intent.getCategories());
        if (categories.size() > 32) throw new IOException("Too many Intent categories");
        output.writeInt(categories.size()); for (String category : categories) string(output, category);
        bundle(output, intent.getExtras(), 0);
    }
    private static Intent intent(DataInputStream input) throws IOException {
        Intent intent = new Intent(string(input)); String data = string(input), type = string(input);
        intent.setDataAndType(data == null ? null : Uri.parse(data), type); intent.setPackage(string(input));
        String component = string(input); if (component != null) {
            ComponentName value = ComponentName.unflattenFromString(component);
            if (value == null) throw new IOException("Invalid stored component"); intent.setComponent(value);
        }
        String identifier = string(input);
        if (identifier != null) {
            if (Build.VERSION.SDK_INT < 29) throw new IOException("Intent identifier requires Android 10");
            intent.setIdentifier(identifier);
        }
        intent.setFlags(input.readInt()); int count = count(input, 32);
        for (int i = 0; i < count; i++) intent.addCategory(string(input));
        Bundle extras = bundle(input, 0); if (extras != null) intent.putExtras(extras); return intent;
    }
    private static void bundle(DataOutputStream output, Bundle bundle, int depth) throws IOException {
        if (bundle == null) { output.writeInt(-1); return; }
        if (depth > 4 || bundle.hasFileDescriptors() || bundle.size() > 64) throw new IOException("Unsupported persistent Bundle");
        TreeSet<String> keys = new TreeSet<>(bundle.keySet()); output.writeInt(keys.size());
        for (String key : keys) {
            string(output, key); Object value = bundle.get(key);
            if (value == null) output.writeByte(0);
            else if (value instanceof String) { output.writeByte(1); string(output, (String) value); }
            else if (value instanceof Boolean) { output.writeByte(2); output.writeBoolean((Boolean) value); }
            else if (value instanceof Integer) { output.writeByte(3); output.writeInt((Integer) value); }
            else if (value instanceof Long) { output.writeByte(4); output.writeLong((Long) value); }
            else if (value instanceof Double) { output.writeByte(5); output.writeDouble((Double) value); }
            else if (value instanceof Float) { output.writeByte(6); output.writeFloat((Float) value); }
            else if (value instanceof byte[]) { output.writeByte(7); blob(output, (byte[]) value); }
            else if (value.getClass() == Bundle.class) { output.writeByte(8); bundle(output, (Bundle) value, depth + 1); }
            else if (value instanceof Short) { output.writeByte(9); output.writeShort((Short) value); }
            else if (value instanceof Byte) { output.writeByte(10); output.writeByte((Byte) value); }
            else if (value instanceof Character) { output.writeByte(11); output.writeChar((Character) value); }
            else throw new IOException("Persistent Intent extras support primitives, strings, byte arrays and bounded Bundles only");
        }
    }
    private static Bundle bundle(DataInputStream input, int depth) throws IOException {
        int count = input.readInt(); if (count == -1) return null;
        if (count < 0 || count > 64 || depth > 4) throw new IOException("Invalid persistent Bundle size");
        Bundle bundle = new Bundle();
        for (int i = 0; i < count; i++) {
            String key = string(input); if (key == null || bundle.containsKey(key)) throw new IOException("Invalid persistent Bundle key");
            switch (input.readUnsignedByte()) {
                case 0: bundle.putString(key, null); break;
                case 1: bundle.putString(key, string(input)); break;
                case 2: bundle.putBoolean(key, input.readBoolean()); break;
                case 3: bundle.putInt(key, input.readInt()); break;
                case 4: bundle.putLong(key, input.readLong()); break;
                case 5: bundle.putDouble(key, input.readDouble()); break;
                case 6: bundle.putFloat(key, input.readFloat()); break;
                case 7: bundle.putByteArray(key, blob(input, MAX_PAYLOAD)); break;
                case 8: bundle.putBundle(key, bundle(input, depth + 1)); break;
                case 9: bundle.putShort(key, input.readShort()); break;
                case 10: bundle.putByte(key, input.readByte()); break;
                case 11: bundle.putChar(key, input.readChar()); break;
                default: throw new IOException("Unknown persistent Bundle type");
            }
        }
        return bundle;
    }
    private static void string(DataOutputStream output, String value) throws IOException {
        if (value == null) { output.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8192) throw new IOException("Persistent string too long"); blob(output, bytes);
    }
    private static String string(DataInputStream input) throws IOException {
        int size = input.readInt(); if (size == -1) return null;
        if (size < 0 || size > 8192 || size > input.available()) throw new IOException("Invalid persistent string");
        byte[] bytes = new byte[size]; input.readFully(bytes); return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void blob(DataOutputStream output, byte[] bytes) throws IOException {
        if (bytes.length > MAX_PAYLOAD) throw new IOException("Persistent bytes too large");
        output.writeInt(bytes.length); output.write(bytes);
    }
    private static byte[] blob(DataInputStream input, int max) throws IOException {
        int size = input.readInt(); if (size < 0 || size > max || size > input.available()) throw new IOException("Invalid persistent bytes");
        byte[] bytes = new byte[size]; input.readFully(bytes); return bytes;
    }
    private static int count(DataInputStream input, int max) throws IOException {
        int count = input.readInt(); if (count < 0 || count > max) throw new IOException("Persistent count out of bounds"); return count;
    }
    private static byte[] boundedRead(java.io.InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int read;
        while ((read = input.read(bytes)) != -1) { if (output.size() + read > max) throw new IOException("Store too large"); output.write(bytes, 0, read); }
        return output.toByteArray();
    }
}
