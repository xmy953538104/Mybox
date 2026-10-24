package io.github.xmy953538104.mybox.engine.accounts;

import io.github.xmy953538104.mybox.engine.CallerIdentity;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Single-server account state and ACL. No Android/system AccountManager is used. */
public final class AccountRepository {
    public static final int VISIBLE = 1, USER_VISIBLE = 2, HIDDEN = 3, USER_HIDDEN = 4;
    public static final String LEGACY_VISIBLE = "android:accounts:key_legacy_visible";
    public static final String LEGACY_HIDDEN = "android:accounts:key_legacy_not_visible";
    private static final int FORMAT = 1, MAX_ITEMS = 10000, MAX_STRING = 1024 * 1024;

    public interface Directory {
        AuthenticatorRegistration authenticator(String type) throws IOException;
        List<String> certificates(String packageName) throws IOException;
    }

    private final AccountStorage storage;
    private final Directory directory;
    private final AccountNotifier notifier;
    private Map<AccountKey, Record> records;
    private final Map<String, Subscription> listeners = new LinkedHashMap<>();

    public AccountRepository(AccountStorage storage, Directory directory, AccountNotifier notifier) throws IOException {
        this.storage = Objects.requireNonNull(storage);
        this.directory = Objects.requireNonNull(directory);
        this.notifier = Objects.requireNonNull(notifier);
        byte[] bytes = storage.read();
        records = bytes == null ? new LinkedHashMap<>() : decode(bytes);
    }

    public static void requireUser(int userId) {
        if (userId != 0) throw new SecurityException("Only virtual user 0 is supported");
    }

    public synchronized List<AccountKey> accounts(CallerIdentity caller, String type) throws IOException {
        requireCurrent(caller);
        List<AccountKey> result = new ArrayList<>();
        for (Record record : records.values()) {
            if ((type == null || type.equals(record.key.type)) && visible(record, caller.packageName, caller.certificateSha256)) {
                result.add(record.key);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized boolean add(CallerIdentity caller, AccountKey key, String password,
            Map<String, String> extras, Map<String, Integer> visibility) throws IOException {
        AuthenticatorRegistration registration = requireOwner(caller, key.type);
        if (records.containsKey(key)) return false;
        Record record = new Record(key, registration.packageName, registration.certificateSha256);
        record.password = password;
        if (extras != null) record.extras.putAll(extras);
        if (visibility != null) {
            for (Map.Entry<String, Integer> entry : visibility.entrySet()) {
                Visibility item = visibility(entry.getKey(), entry.getValue());
                if (item != null) record.visibility.put(entry.getKey(), item);
            }
        }
        Map<AccountKey, Record> next = copy(); next.put(key, record); commit(next); return true;
    }

    public synchronized boolean remove(CallerIdentity caller, AccountKey key) throws IOException {
        ownerRecord(caller, key);
        if (!records.containsKey(key)) return false;
        Map<AccountKey, Record> next = copy(); next.remove(key); commit(next); return true;
    }

    public synchronized AccountKey rename(CallerIdentity caller, AccountKey key, String newName) throws IOException {
        Record current = ownerRecord(caller, key);
        if (current == null) throw new IllegalArgumentException("Account does not exist");
        AccountKey renamed = new AccountKey(newName, key.type);
        if (records.containsKey(renamed)) throw new IllegalArgumentException("Destination account already exists");
        Map<AccountKey, Record> next = copy();
        Record record = next.remove(key); record.key = renamed; record.previousName = key.name;
        next.put(renamed, record); commit(next); return renamed;
    }

    public synchronized String password(CallerIdentity caller, AccountKey key) throws IOException {
        Record record = ownerRecord(caller, key); return record == null ? null : record.password;
    }

    public synchronized String userData(CallerIdentity caller, AccountKey key, String name) throws IOException {
        Record record = ownerRecord(caller, key); return record == null ? null : record.extras.get(name);
    }

    public synchronized String peekToken(CallerIdentity caller, AccountKey key, String tokenType) throws IOException {
        required(tokenType);
        Record record = ownerRecord(caller, key); return record == null ? null : record.tokens.get(tokenType);
    }

    public synchronized String previousName(CallerIdentity caller, AccountKey key) throws IOException {
        requireVisible(caller, key);
        Record record = records.get(key); return record == null ? null : record.previousName;
    }

    public synchronized void setPassword(CallerIdentity caller, AccountKey key, String password) throws IOException {
        if (ownerRecord(caller, key) == null) throw new IllegalArgumentException("Account does not exist");
        Map<AccountKey, Record> next = copy(); Record record = next.get(key);
        record.password = password; record.tokens.clear(); record.issued.clear(); commit(next);
    }

    public synchronized void setUserData(CallerIdentity caller, AccountKey key, String name, String value) throws IOException {
        required(name);
        if (ownerRecord(caller, key) == null) throw new IllegalArgumentException("Account does not exist");
        Map<AccountKey, Record> next = copy(); putOrRemove(next.get(key).extras, name, value); commit(next);
    }

    public synchronized void setToken(CallerIdentity caller, AccountKey key, String type, String value) throws IOException {
        required(type);
        if (ownerRecord(caller, key) == null) throw new IllegalArgumentException("Account does not exist");
        Map<AccountKey, Record> next = copy(); putOrRemove(next.get(key).tokens, type, value); commit(next);
    }

    public synchronized boolean authenticated(CallerIdentity caller, AccountKey key, long now) throws IOException {
        if (ownerRecord(caller, key) == null) return false;
        Map<AccountKey, Record> next = copy(); next.get(key).lastAuthenticated = now; commit(next); return true;
    }

    public synchronized boolean setVisibility(CallerIdentity caller, AccountKey key, String targetPackage, int value) throws IOException {
        if (ownerRecord(caller, key) == null) return false;
        Visibility item = visibility(targetPackage, value);
        Map<AccountKey, Record> next = copy();
        if (item == null) next.get(key).visibility.remove(targetPackage);
        else next.get(key).visibility.put(targetPackage, item);
        commit(next); return true;
    }

    public synchronized int getVisibility(CallerIdentity caller, AccountKey key, String targetPackage) throws IOException {
        requireCurrent(caller);
        if (!caller.packageName.equals(targetPackage)) requireOwner(caller, key.type);
        Record record = records.get(key);
        if (record == null) return 0;
        if (isLegacy(targetPackage)) {
            Visibility setting = record.visibility.get(targetPackage);
            return setting == null ? 0 : setting.value;
        }
        List<String> certificates = directory.certificates(targetPackage);
        return certificates == null ? HIDDEN : visibilityValue(record, targetPackage, certificates);
    }

    public synchronized Map<String, Integer> visibilityMap(CallerIdentity caller, AccountKey key) throws IOException {
        Record record = ownerRecord(caller, key);
        Map<String, Integer> result = new HashMap<>();
        if (record != null) for (Map.Entry<String, Visibility> entry : record.visibility.entrySet()) result.put(entry.getKey(), entry.getValue().value);
        return Collections.unmodifiableMap(result);
    }

    public synchronized void grantToken(CallerIdentity owner, AccountKey key, String type, String consumerPackage, boolean grant) throws IOException {
        required(type);
        if (ownerRecord(owner, key) == null) throw new IllegalArgumentException("Account does not exist");
        List<String> certificates = targetCertificates(consumerPackage);
        TokenAudience audience = new TokenAudience(consumerPackage, certificates, type);
        Map<AccountKey, Record> next = copy();
        if (grant) next.get(key).grants.add(audience); else next.get(key).grants.remove(audience);
        commit(next);
    }

    public synchronized String grantedToken(CallerIdentity caller, AccountKey key, String type) throws IOException {
        required(type); requireVisible(caller, key);
        Record record = records.get(key);
        if (record == null) return null;
        if (!record.ownerMatches(caller.packageName, caller.certificateSha256)
                && !record.grants.contains(new TokenAudience(caller.packageName, caller.certificateSha256, type))) {
            throw new SecurityException("Token access requires an explicit grant");
        }
        return record.tokens.get(type);
    }

    public synchronized void issuedToken(CallerIdentity authenticator, CallerIdentity consumer, AccountKey key,
            String type, String token, long expiresAt) throws IOException {
        required(type); required(token); requireCurrent(consumer);
        Record record = ownerRecord(authenticator, key);
        if (record == null) throw new IllegalArgumentException("Account does not exist");
        AuthenticatorRegistration registration = requireOwner(authenticator, key.type);
        if (!registration.customTokens) throw new SecurityException("Authenticator does not own per-client tokens");
        requireVisible(consumer, key);
        Map<AccountKey, Record> next = copy();
        next.get(key).issued.put(new TokenAudience(consumer.packageName, consumer.certificateSha256, type), new Issued(token, expiresAt));
        commit(next);
    }

    public synchronized String cachedIssuedToken(CallerIdentity caller, AccountKey key, String type, long now) throws IOException {
        required(type); requireVisible(caller, key);
        Record record = records.get(key);
        if (record == null) return null;
        Issued item = record.issued.get(new TokenAudience(caller.packageName, caller.certificateSha256, type));
        return item == null || item.expiresAt <= now ? null : item.value;
    }

    public synchronized void invalidateToken(CallerIdentity caller, String type, String token) throws IOException {
        requireCurrent(caller); required(type); required(token);
        AuthenticatorRegistration registration = directory.authenticator(type);
        boolean owner = registration != null && registration.owns(caller);
        Map<AccountKey, Record> next = copy(); boolean changed = false;
        for (Record record : next.values()) {
            if (!type.equals(record.key.type)) continue;
            if (owner && record.ownerMatches(caller.packageName, caller.certificateSha256)) {
                changed |= record.tokens.values().removeIf(token::equals);
                changed |= record.issued.values().removeIf(item -> token.equals(item.value));
            } else {
                if (!visible(record, caller.packageName, caller.certificateSha256)) continue;
                changed |= record.issued.entrySet().removeIf(entry -> entry.getKey().matches(caller)
                        && token.equals(entry.getValue().value));
                // Possession of another caller's string is not authority to invalidate its grant/token.
                for (TokenAudience audience : record.grants) {
                    if (audience.matches(caller) && token.equals(record.tokens.get(audience.type))) {
                        record.tokens.remove(audience.type); changed = true;
                    }
                }
            }
        }
        if (changed) commit(next);
    }

    public synchronized void registerListener(CallerIdentity caller, String[] types) throws IOException {
        requireCurrent(caller);
        Subscription subscription = listeners.get(caller.packageName);
        if (subscription == null || !subscription.certificates.equals(caller.certificateSha256)) {
            subscription = new Subscription(caller.certificateSha256); listeners.put(caller.packageName, subscription);
        }
        for (String type : listenerTypes(types)) subscription.types.put(type, subscription.types.getOrDefault(type, 0) + 1);
    }

    public synchronized void unregisterListener(CallerIdentity caller, String[] types) throws IOException {
        requireCurrent(caller);
        Subscription subscription = listeners.get(caller.packageName);
        List<String> requested = listenerTypes(types);
        if (subscription == null || !subscription.certificates.equals(caller.certificateSha256)) throw new IllegalArgumentException("Listener is not registered");
        for (String type : requested) if (!subscription.types.containsKey(type)) throw new IllegalArgumentException("Listener type is not registered");
        for (String type : requested) {
            int count = subscription.types.get(type) - 1;
            if (count == 0) subscription.types.remove(type); else subscription.types.put(type, count);
        }
        if (subscription.types.isEmpty()) listeners.remove(caller.packageName);
    }

    public synchronized AuthenticatorRegistration requireVisible(CallerIdentity caller, AccountKey key) throws IOException {
        requireCurrent(caller);
        Record record = records.get(key);
        if (record == null || !visible(record, caller.packageName, caller.certificateSha256)) throw new SecurityException("Account is not visible to this caller");
        AuthenticatorRegistration registration = directory.authenticator(key.type);
        if (registration == null || !record.ownerMatches(registration.packageName, registration.certificateSha256)) throw new SecurityException("Account authenticator is no longer trusted");
        return registration;
    }

    public synchronized AuthenticatorRegistration requireOwner(CallerIdentity caller, String type) throws IOException {
        requireCurrent(caller);
        AuthenticatorRegistration registration = directory.authenticator(type);
        if (registration == null || !registration.owns(caller)) throw new SecurityException("Caller does not own this account type");
        return registration;
    }

    private Record ownerRecord(CallerIdentity caller, AccountKey key) throws IOException {
        requireOwner(caller, key.type);
        Record record = records.get(key);
        if (record != null && !record.ownerMatches(caller.packageName, caller.certificateSha256)) throw new SecurityException("Stored account owner certificate changed");
        return record;
    }

    private void requireCurrent(CallerIdentity caller) throws IOException {
        if (caller == null) throw new SecurityException("No authenticated account caller");
        requireUser(caller.userId);
        List<String> current = directory.certificates(caller.packageName);
        if (current == null || !current.equals(caller.certificateSha256)) throw new SecurityException("Caller certificate is no longer installed");
    }

    private Visibility visibility(String target, int value) throws IOException {
        required(target);
        if (value < 0 || value > 4) throw new IllegalArgumentException("Invalid account visibility");
        if (value == 0) return null;
        return new Visibility(value, isLegacy(target) ? Collections.emptyList() : targetCertificates(target));
    }

    private List<String> targetCertificates(String target) throws IOException {
        List<String> certificates = directory.certificates(target);
        if (certificates == null || certificates.isEmpty()) throw new IllegalArgumentException("Target package is not installed");
        return certificates;
    }

    private boolean visible(Record record, String target, List<String> certificates) throws IOException {
        int value = visibilityValue(record, target, certificates);
        return value == VISIBLE || value == USER_VISIBLE;
    }

    private int visibilityValue(Record record, String target, List<String> certificates) throws IOException {
        AuthenticatorRegistration owner = directory.authenticator(record.key.type);
        if (owner == null || !record.ownerMatches(owner.packageName, owner.certificateSha256)) return HIDDEN;
        if (record.ownerMatches(target, certificates)) return VISIBLE;
        Visibility explicit = record.visibility.get(target);
        if (explicit != null && explicit.certificates.equals(certificates)) return explicit.value;
        Visibility fallback = record.visibility.get(LEGACY_HIDDEN);
        return fallback == null ? HIDDEN : fallback.value;
    }

    private static boolean isLegacy(String target) { return LEGACY_VISIBLE.equals(target) || LEGACY_HIDDEN.equals(target); }
    private static void required(String value) { if (value == null || value.isEmpty()) throw new IllegalArgumentException("Required account field is empty"); }
    private static void putOrRemove(Map<String,String> map, String key, String value) { if (value == null) map.remove(key); else map.put(key, value); }
    private static List<String> listenerTypes(String[] types) {
        if (types == null) return Collections.singletonList(null);
        List<String> result = new ArrayList<>();
        for (String type : types) { required(type); if (!result.contains(type)) result.add(type); }
        return result;
    }

    private Map<AccountKey, Record> copy() throws IOException { return decode(encode(records)); }

    private void commit(Map<AccountKey, Record> next) throws IOException {
        Set<String> changed = new HashSet<>();
        for (Map.Entry<String, Subscription> item : listeners.entrySet()) {
            String target = item.getKey(); Subscription subscription = item.getValue();
            if (!subscription.certificates.equals(directory.certificates(target))) continue;
            if (!visibleSet(records, target, subscription).equals(visibleSet(next, target, subscription))) changed.add(target);
        }
        storage.write(encode(next));
        records = next;
        if (!changed.isEmpty()) notifier.notifyAccountsChanged(0, Collections.unmodifiableSet(changed));
    }

    private Set<AccountKey> visibleSet(Map<AccountKey, Record> state, String target, Subscription subscription) throws IOException {
        Set<AccountKey> keys = new HashSet<>();
        for (Record record : state.values()) {
            if ((subscription.types.containsKey(null) || subscription.types.containsKey(record.key.type))
                    && visible(record, target, subscription.certificates)) keys.add(record.key);
        }
        return keys;
    }

    private static final class Record {
        AccountKey key;
        final String owner;
        final List<String> ownerCertificates;
        String password, previousName;
        long lastAuthenticated;
        final Map<String,String> extras = new LinkedHashMap<>(), tokens = new LinkedHashMap<>();
        final Map<String,Visibility> visibility = new LinkedHashMap<>();
        final Set<TokenAudience> grants = new HashSet<>();
        final Map<TokenAudience,Issued> issued = new LinkedHashMap<>();
        Record(AccountKey key, String owner, List<String> certificates) {
            this.key = key; this.owner = owner; ownerCertificates = new ArrayList<>(certificates);
        }
        boolean ownerMatches(String name, List<String> certificates) { return owner.equals(name) && ownerCertificates.equals(certificates); }
    }

    private static final class Visibility {
        final int value; final List<String> certificates;
        Visibility(int value, List<String> certificates) { this.value = value; this.certificates = new ArrayList<>(certificates); }
    }

    private static final class TokenAudience {
        final String name, type; final List<String> certificates;
        TokenAudience(String name, List<String> certificates, String type) {
            this.name = name; this.certificates = new ArrayList<>(certificates); this.type = type;
        }
        boolean matches(CallerIdentity caller) { return name.equals(caller.packageName) && certificates.equals(caller.certificateSha256); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof TokenAudience)) return false;
            TokenAudience value = (TokenAudience) other;
            return name.equals(value.name) && type.equals(value.type) && certificates.equals(value.certificates);
        }
        @Override public int hashCode() { return Objects.hash(name, type, certificates); }
    }

    private static final class Issued {
        final String value; final long expiresAt;
        Issued(String value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
    }

    private static final class Subscription {
        final List<String> certificates; final Map<String,Integer> types = new HashMap<>();
        Subscription(List<String> certificates) { this.certificates = new ArrayList<>(certificates); }
    }

    private static byte[] encode(Map<AccountKey, Record> records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new FilterOutputStream(bytes) {
            @Override public void write(int value) throws IOException {
                if (bytes.size() >= 32 * 1024 * 1024) throw new IOException("Account store exceeds size limit");
                out.write(value);
            }
            @Override public void write(byte[] data, int offset, int length) throws IOException {
                if (length > 32 * 1024 * 1024 - bytes.size()) throw new IOException("Account store exceeds size limit");
                out.write(data, offset, length);
            }
        })) {
            output.writeInt(FORMAT); count(output, records.size());
            for (Record record : records.values()) {
                string(output, record.key.name); string(output, record.key.type); string(output, record.owner);
                strings(output, record.ownerCertificates); string(output, record.password); string(output, record.previousName);
                output.writeLong(record.lastAuthenticated); stringMap(output, record.extras); stringMap(output, record.tokens);
                count(output, record.visibility.size());
                for (Map.Entry<String,Visibility> entry : record.visibility.entrySet()) {
                    string(output, entry.getKey()); output.writeInt(entry.getValue().value); strings(output, entry.getValue().certificates);
                }
                count(output, record.grants.size()); for (TokenAudience audience : record.grants) audience(output, audience);
                count(output, record.issued.size());
                for (Map.Entry<TokenAudience,Issued> entry : record.issued.entrySet()) {
                    audience(output, entry.getKey()); string(output, entry.getValue().value); output.writeLong(entry.getValue().expiresAt);
                }
            }
        }
        if (bytes.size() > 32 * 1024 * 1024) throw new IOException("Account store exceeds size limit");
        return bytes.toByteArray();
    }

    private static Map<AccountKey, Record> decode(byte[] bytes) throws IOException {
        if (bytes.length > 32 * 1024 * 1024) throw new IOException("Account store exceeds size limit");
        Map<AccountKey, Record> records = new LinkedHashMap<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != FORMAT) throw new IOException("Unsupported account store format");
            int count = count(input);
            for (int index = 0; index < count; index++) {
                Record record = new Record(new AccountKey(string(input), string(input)), string(input), strings(input));
                record.password = string(input); record.previousName = string(input); record.lastAuthenticated = input.readLong();
                stringMap(input, record.extras); stringMap(input, record.tokens);
                int visibility = count(input);
                for (int i = 0; i < visibility; i++) record.visibility.put(string(input), new Visibility(input.readInt(), strings(input)));
                int grants = count(input); for (int i = 0; i < grants; i++) record.grants.add(audience(input));
                int issued = count(input);
                for (int i = 0; i < issued; i++) record.issued.put(audience(input), new Issued(string(input), input.readLong()));
                if (records.put(record.key, record) != null) throw new IOException("Duplicate stored account");
            }
            if (input.available() != 0) throw new IOException("Trailing account store content");
            return records;
        } catch (IllegalArgumentException | NullPointerException malformed) { throw new IOException("Malformed account store", malformed); }
    }

    private static int count(DataInputStream input) throws IOException {
        int value = input.readInt();
        if (value < 0 || value > MAX_ITEMS) throw new IOException("Invalid account store item count");
        return value;
    }
    private static void count(DataOutputStream output, int count) throws IOException {
        if (count < 0 || count > MAX_ITEMS) throw new IOException("Account store item count exceeds limit");
        output.writeInt(count);
    }
    private static void string(DataOutputStream output, String value) throws IOException {
        if (value == null) { output.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING) throw new IOException("Account field exceeds size limit");
        output.writeInt(bytes.length); output.write(bytes);
    }
    private static String string(DataInputStream input) throws IOException {
        int length = input.readInt(); if (length == -1) return null;
        if (length < 0 || length > MAX_STRING || length > input.available()) throw new IOException("Invalid account field length");
        byte[] bytes = new byte[length]; input.readFully(bytes); return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void strings(DataOutputStream output, List<String> values) throws IOException {
        count(output, values.size()); for (String value : values) string(output, value);
    }
    private static List<String> strings(DataInputStream input) throws IOException {
        int count = count(input); List<String> values = new ArrayList<>();
        for (int i = 0; i < count; i++) values.add(string(input)); return values;
    }
    private static void stringMap(DataOutputStream output, Map<String,String> values) throws IOException {
        count(output, values.size());
        for (Map.Entry<String,String> entry : values.entrySet()) { string(output, entry.getKey()); string(output, entry.getValue()); }
    }
    private static void stringMap(DataInputStream input, Map<String,String> values) throws IOException {
        int count = count(input); for (int i = 0; i < count; i++) values.put(string(input), string(input));
    }
    private static void audience(DataOutputStream output, TokenAudience value) throws IOException {
        string(output, value.name); strings(output, value.certificates); string(output, value.type);
    }
    private static TokenAudience audience(DataInputStream input) throws IOException {
        return new TokenAudience(string(input), strings(input), string(input));
    }
}
