package io.github.xmy953538104.mybox.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class DiagnosticJournalTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void offByDefaultSharedSwitchRestartAndExportExcludeSecretMessages() throws Exception {
        File root = temporary.newFolder();
        DiagnosticJournal host = new DiagnosticJournal(root, 10, "host");
        DiagnosticJournal guest = new DiagnosticJournal(root, 11, "engine_p0");
        host.record("installer", "create", 1, 2, null);
        assertFalse(new File(root, "events.0.jsonl").exists());
        host.setEnabled(true); assertTrue(guest.enabled());
        guest.bound("org.fixture.app", 8);
        guest.record("installer", "commit", 17, 4, new IllegalStateException("SecretAccountToken123"));
        host.record("installer", "user@example.com?auth=secret", 18, 0, null);
        DiagnosticJournal restarted = new DiagnosticJournal(root, 12, "engine_server");
        assertTrue(restarted.enabled());
        String text = unzip(restarted);
        assertTrue(text.contains("IllegalStateException"));
        assertTrue(text.contains("org.fixture.app"));
        assertTrue(text.contains("\"generation\":8"));
        assertFalse(text.contains("SecretAccountToken123"));
        assertFalse(text.contains("user@example.com"));
        assertFalse(text.contains("auth=secret"));
        host.setEnabled(false); assertFalse(guest.enabled());
        long size = new File(root, "events.0.jsonl").length();
        guest.record("installer", "disabled_write", 1, 0, null);
        assertEquals(size, new File(root, "events.0.jsonl").length());
        assertTrue(unzip(host).contains("commit"));
        host.clear(); assertFalse(unzip(host).contains("commit"));
    }

    @Test public void rotationBoundsStorageAndConcurrentLockDoesNotBlockOperations() throws Exception {
        File root = temporary.newFolder(); DiagnosticJournal journal = new DiagnosticJournal(root, 10, "host");
        journal.setEnabled(true);
        try (FileChannel lock = FileChannel.open(new File(root, "lock").toPath(), StandardOpenOption.WRITE);
                FileLock ignored = lock.lock()) {
            journal.record("ipc", "busy", 0, 0, null);
            assertEquals(1, journal.lost());
        }
        journal.record("ipc", "after_contention", 1, 0, null);
        assertTrue(unzip(journal).contains("\"dropped\":1"));
        for (int i = 0; i < 5000; i++) journal.record("installer", "state", i, i, null);
        long total = 0;
        for (File file : root.listFiles()) if (file.getName().startsWith("events.")) {
            assertTrue(file.length() <= DiagnosticJournal.SEGMENT_BYTES); total += file.length();
        }
        assertTrue(new File(root, "events.3.jsonl").isFile());
        assertTrue(total <= DiagnosticJournal.SEGMENTS * DiagnosticJournal.SEGMENT_BYTES);
        assertFalse(unzip(journal).contains("after_contention"));
    }

    @Test public void failedDiagnosticStorageDoesNotBecomeAnApplicationException() throws Exception {
        File root = temporary.newFolder(); DiagnosticJournal journal = new DiagnosticJournal(root, 10, "host");
        journal.setEnabled(true);
        Files.createDirectory(new File(root, "events.0.jsonl").toPath());
        journal.record("engine", "failure", 0, 0, new IllegalStateException("private"));
        assertEquals(1, journal.lost());
    }

    private static String unzip(DiagnosticJournal journal) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); journal.export(bytes, 34, 3);
        StringBuilder result = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry entry; byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                assertFalse(entry.getName().contains("/"));
                int count; ByteArrayOutputStream part = new ByteArrayOutputStream();
                while ((count = zip.read(buffer)) != -1) part.write(buffer, 0, count);
                result.append(new String(part.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }
}
