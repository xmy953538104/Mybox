package io.github.xmy953538104.mybox.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class SettingsStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void realCommitReopensAndDeletionHidesPhysicalFallback() throws Exception {
        File file = new File(temporary.getRoot(), "user0.properties");
        SettingsStore store = new SettingsStore(file);
        assertFalse(store.contains("secure", "key"));
        store.put("secure", "key", "一=two\nthree\u0000");
        store.put("global", "key", "");
        SettingsStore cold = new SettingsStore(file);
        assertEquals("一=two\nthree\u0000", cold.get("secure", "key"));
        assertEquals("", cold.get("global", "key"));
        cold.put("secure", "key", null);
        SettingsStore deleted = new SettingsStore(file);
        assertTrue(deleted.contains("secure", "key")); assertNull(deleted.get("secure", "key"));
        assertEquals("", deleted.get("global", "key"));
    }

    @Test public void failedDiskCommitKeepsPreviouslyPublishedState() throws Exception {
        File file = new File(temporary.getRoot(), "user0.properties");
        SettingsStore store = new SettingsStore(file); store.put("secure", "key", "before");
        assertTrue(new File(temporary.getRoot(), "user0.properties.new").mkdir());
        try { store.put("secure", "key", "after"); fail("Write failure returned success"); }
        catch (IOException expected) { }
        assertEquals("before", store.get("secure", "key"));
        assertEquals("before", new SettingsStore(file).get("secure", "key"));
    }

    @Test public void rejectsUnboundedOrMalformedDataWithoutChangingState() throws Exception {
        File file = new File(temporary.getRoot(), "user0.properties"); SettingsStore store = new SettingsStore(file);
        store.put("secure", "key", "before");
        for (String domain : new String[]{null, "system", "../secure"}) {
            try { store.put(domain, "key", "after"); fail("Foreign domain accepted"); }
            catch (SecurityException expected) { }
        }
        try { store.put("secure", "key", new String(new char[16385])); fail("Huge value accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals("before", store.get("secure", "key"));
        Files.write(file.toPath(), "secure\\:key=unmarked".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        try { new SettingsStore(file); fail("Malformed persisted value accepted"); }
        catch (IOException expected) { }
    }
}
