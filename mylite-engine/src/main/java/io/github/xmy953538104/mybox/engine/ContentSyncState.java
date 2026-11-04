package io.github.xmy953538104.mybox.engine;

import android.accounts.Account;
import android.os.Bundle;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Server-private durable state. Account names and extras are never diagnostic fields. */
final class ContentSyncState {
    private static final int MAX_BYTES = 1024 * 1024;
    private final AtomicFile file;
    JSONObject value;

    ContentSyncState(File path) throws Exception {
        if (Files.isSymbolicLink(path.toPath()) || Files.isSymbolicLink(new File(path + ".bak").toPath())
                || Files.isSymbolicLink(new File(path + ".new").toPath())) throw new IOException("Sync state is a symbolic link");
        file = new AtomicFile(path);
        if (path.exists() || new File(path + ".bak").exists()) {
            try (java.io.InputStream input = file.openRead(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                for (int n; (n = input.read(buffer)) != -1;) {
                    if (output.size() + n > MAX_BYTES) throw new IOException("Sync state exceeds bound");
                    output.write(buffer, 0, n);
                }
                value = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } else value = new JSONObject().put("version", 1).put("master", false).put("next", 1).put("nextAuthority", 1)
                .put("settings", new JSONArray()).put("pending", new JSONArray());
        validate(value);
    }

    JSONObject copy() throws Exception { return new JSONObject(value.toString()); }

    void commit(JSONObject next) throws Exception {
        validate(next);
        byte[] bytes = next.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Sync state exceeds bound");
        FileOutputStream output = null;
        try {
            output = file.startWrite(); output.write(bytes); file.finishWrite(output);
        } catch (IOException failure) { if (output != null) file.failWrite(output); throw failure; }
        value = next;
    }

    static void text(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid sync state text");
    }

    static JSONObject account(Account account) throws Exception {
        if (account == null) throw new IllegalArgumentException("Missing sync account");
        text(account.name); text(account.type);
        return new JSONObject().put("name", account.name).put("type", account.type);
    }
    static Account account(JSONObject entry) throws Exception {
        String name = entry.getString("name"), type = entry.getString("type"); text(name); text(type);
        return new Account(name, type);
    }

    static JSONObject extras(Bundle bundle) throws Exception {
        Bundle input = bundle == null ? Bundle.EMPTY : bundle;
        if (input.hasFileDescriptors() || input.size() > 64) throw new IllegalArgumentException("Invalid sync extras");
        JSONObject result = new JSONObject();
        for (String key : input.keySet()) {
            text(key); Object value = input.get(key); JSONObject item = new JSONObject();
            if (value == null) item.put("t", "null");
            else if (value instanceof Account) item.put("t", "account").put("v", account((Account) value));
            else if (value instanceof String) {
                if (((String) value).length() > 16384) throw new IllegalArgumentException("Sync extra exceeds bound");
                item.put("t", "string").put("v", value);
            } else if (value instanceof Boolean) item.put("t", "boolean").put("v", value);
            else if (value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double) {
                if (value instanceof Float && !Float.isFinite((Float) value)
                        || value instanceof Double && !Double.isFinite((Double) value)) throw new IllegalArgumentException("Non-finite sync extra");
                item.put("t", value.getClass().getSimpleName()).put("v", value.toString());
            } else throw new IllegalArgumentException("Unsupported sync extra type");
            result.put(key, item);
        }
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > 65536) throw new IllegalArgumentException("Sync extras exceed bound");
        return result;
    }

    static Bundle extras(JSONObject value) throws Exception {
        if (value.length() > 64) throw new IOException("Too many sync extras");
        Bundle result = new Bundle();
        for (Iterator<String> keys = value.keys(); keys.hasNext();) {
            String key = keys.next(); text(key); JSONObject item = value.getJSONObject(key);
            switch (item.getString("t")) {
                case "null": result.putString(key, null); break;
                case "account": result.putParcelable(key, account(item.getJSONObject("v"))); break;
                case "string": result.putString(key, item.getString("v")); break;
                case "boolean": result.putBoolean(key, item.getBoolean("v")); break;
                case "Integer": result.putInt(key, Integer.parseInt(item.getString("v"))); break;
                case "Long": result.putLong(key, Long.parseLong(item.getString("v"))); break;
                case "Float": result.putFloat(key, Float.parseFloat(item.getString("v"))); break;
                case "Double": result.putDouble(key, Double.parseDouble(item.getString("v"))); break;
                default: throw new IOException("Unknown persisted sync extra type");
            }
        }
        // Apply the same size/type checks on disk and Binder input.
        extras(result); return result;
    }

    private static void validate(JSONObject root) throws Exception {
        if (root.getInt("version") != 1 || root.getLong("next") <= 0 || root.getInt("nextAuthority") <= 0) throw new IOException("Invalid sync state version or id");
        root.getBoolean("master");
        JSONArray settings = root.getJSONArray("settings"), pending = root.getJSONArray("pending");
        if (settings.length() > 256 || pending.length() > 64) throw new IOException("Sync state quota exceeded");
        java.util.HashSet<Long> ids = new java.util.HashSet<>();
        java.util.HashSet<Integer> authorityIds = new java.util.HashSet<>();
        java.util.HashSet<String> settingKeys = new java.util.HashSet<>();
        for (int i = 0; i < settings.length(); i++) {
            JSONObject entry = settings.getJSONObject(i); validateOwner(entry); account(entry.getJSONObject("account"));
            int authorityId = entry.getInt("id");
            if (authorityId <= 0 || authorityId >= root.getInt("nextAuthority") || !authorityIds.add(authorityId)) throw new IOException("Invalid sync authority id");
            String settingKey = entry.getString("component") + "\n" + entry.getString("authority") + "\n" + entry.getJSONObject("account").getString("name")
                    + "\n" + entry.getJSONObject("account").getString("type") + "\n" + entry.getString("certificate") + "\n" + entry.getInt("appId");
            if (!settingKeys.add(settingKey)) throw new IOException("Duplicate sync settings");
            int syncable = entry.getInt("syncable");
            if (syncable < -1 || syncable > 1) throw new IOException("Invalid persisted syncability");
            entry.getBoolean("auto");
        }
        for (int i = 0; i < pending.length(); i++) {
            JSONObject entry = pending.getJSONObject(i); validateOwner(entry); account(entry.getJSONObject("account"));
            text(entry.getString("generation")); extras(entry.getJSONObject("extras"));
            long id = entry.getLong("id");
            if (id <= 0 || id >= root.getLong("next") || !ids.add(id) || entry.getLong("due") < 0 || entry.getInt("attempt") < 0)
                throw new IOException("Invalid persisted sync work");
        }
    }
    private static void validateOwner(JSONObject entry) throws Exception {
        for (String key : new String[]{"component", "certificate", "authority"}) text(entry.getString(key));
        if (entry.getInt("appId") <= 0) throw new IOException("Invalid sync publisher id");
    }
}
