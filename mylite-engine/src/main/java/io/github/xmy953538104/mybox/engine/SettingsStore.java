package io.github.xmy953538104.mybox.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Container settings are durable before the caller receives success. Owned by the server only. */
final class SettingsStore {
    private static final int MAX_BYTES = 1024 * 1024;
    private final File file;
    private final Properties values = new Properties();

    SettingsStore(File file) throws IOException {
        this.file = file;
        if (file.exists()) {
            if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() > MAX_BYTES)
                throw new IOException("Invalid container settings file");
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > MAX_BYTES) throw new IOException("Container settings exceed bound");
            values.load(new ByteArrayInputStream(bytes));
            if (values.size() > 1024) throw new IOException("Too many container settings");
            for (String key : values.stringPropertyNames()) {
                int separator = key.indexOf(':');
                if (separator < 0) throw new IOException("Invalid stored settings domain");
                String value = values.getProperty(key);
                if (!value.isEmpty() && value.charAt(0) != 'v') throw new IOException("Invalid stored settings value");
                validate(key.substring(0, separator), key.substring(separator + 1), value.isEmpty() ? null : value.substring(1));
            }
        }
    }

    synchronized boolean contains(String domain, String name) {
        validate(domain, name, null);
        return values.containsKey(domain + ":" + name);
    }

    synchronized String get(String domain, String name) {
        validate(domain, name, null);
        String value = values.getProperty(domain + ":" + name);
        return value == null || value.isEmpty() ? null : value.substring(1);
    }

    synchronized void put(String domain, String name, String value) throws IOException {
        validate(domain, name, value);
        Properties next = new Properties(); next.putAll(values);
        // Empty means a virtual deletion; it must not expose the physical value again.
        next.setProperty(domain + ":" + name, value == null ? "" : "v" + value);
        if (next.size() > 1024) throw new IOException("Too many container settings");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); next.store(bytes, null);
        if (bytes.size() > MAX_BYTES) throw new IOException("Container settings exceed bound");
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create container settings directory");
        File temporary = new File(parent, file.getName() + ".new");
        if (Files.isSymbolicLink(temporary.toPath()) || Files.isSymbolicLink(file.toPath()))
            throw new IOException("Container settings path is a symbolic link");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes.toByteArray()); output.getFD().sync();
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        values.clear(); values.putAll(next);
    }

    static void validate(String domain, String name, String value) {
        if (!"secure".equals(domain) && !"global".equals(domain)) throw new SecurityException("Unsupported virtual settings domain");
        if (name == null || name.isEmpty() || name.length() > 256 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid container setting name");
        if (value != null && value.length() > 16384) throw new IllegalArgumentException("Container setting value exceeds bound");
    }
}
