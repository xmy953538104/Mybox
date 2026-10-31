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

    @Test public void deepFrameworkAndPurePlatformFailuresRemainBoundedAndExcludePayloads() throws Exception {
        File root = temporary.newFolder(); DiagnosticJournal journal = new DiagnosticJournal(root, 10, "engine_p0");
        journal.setEnabled(true);
        RuntimeException cause = new RuntimeException("SecretAccountToken123");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("android.security.net.config.NetworkSecurityConfigProvider", "handleNewApplication", "SecretFilename", 99),
                new StackTraceElement("android.app.LoadedApk", "makeApplicationInner", "SecretFilename", 101),
                new StackTraceElement("private.guest.SecretClass", "privateMethod", "SecretFilename", 1)});
        RuntimeException wrapped = new RuntimeException("user@example.com", cause);
        StackTraceElement[] own = new StackTraceElement[12];
        for (int i = 0; i < own.length; i++) own[i] = new StackTraceElement(
                "io.github.xmy953538104.mybox.EngineLayer" + i, "invoke", "SecretFilename", i);
        wrapped.setStackTrace(own); journal.record("engine", "guest_bind", 1, 0, wrapped);
        SecurityException blob = new SecurityException("SecretAccountToken123");
        blob.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("android.os.Parcel", "readException", "SecretFilename", 1),
                new StackTraceElement("android.app.blob.BlobStoreManager", "getLeasedBlobs", "SecretFilename", 42),
                new StackTraceElement("private.guest.SecretClass", "privateMethod", "SecretFilename", 1)});
        journal.record("engine", "uncaught", 2, 0, blob);
        SecurityException media = new SecurityException("SecretAccountToken123");
        media.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("android.os.Parcel", "readException", "SecretFilename", 1),
                new StackTraceElement("android.media.IMediaRouterService$Stub$Proxy", "registerRouter2", "SecretFilename", 1059),
                new StackTraceElement("android.media.MediaRouter2", "registerRouteCallback", "SecretFilename", 406),
                new StackTraceElement("android.app.ActivityThread", "main", "SecretFilename", 8789)});
        journal.record("engine", "uncaught", 3, 0, media);
        String exported = unzip(journal);
        assertTrue(exported.contains("android.security.net.config.NetworkSecurityConfigProvider.handleNewApplication:99"));
        assertTrue(exported.contains("android.app.blob.BlobStoreManager.getLeasedBlobs:42"));
        assertTrue(exported.contains("android.media.IMediaRouterService$Stub$Proxy.registerRouter2:1059"));
        assertTrue(exported.contains("android.media.MediaRouter2.registerRouteCallback:406"));
        SecurityException widgets=new SecurityException("SecretAccountToken123");
        widgets.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.android.internal.appwidget.IAppWidgetService$Stub$Proxy","getAppWidgetIds","SecretFilename",1124),
                new StackTraceElement("android.appwidget.AppWidgetManager","getAppWidgetIds","SecretFilename",1207),
                new StackTraceElement("android.accounts.AccountManager","getAuthToken","SecretFilename",100)});
        journal.record("fixture","widget_callback_failure",20,0,widgets);
        exported=unzip(journal);
        assertTrue(exported.contains("com.android.internal.appwidget.IAppWidgetService$Stub$Proxy.getAppWidgetIds:1124"));
        assertTrue(exported.contains("android.appwidget.AppWidgetManager.getAppWidgetIds:1207"));
        assertTrue(exported.contains("android.accounts.AccountManager.getAuthToken:100"));
        assertTrue(exported.contains("EngineLayer5.invoke:5"));
        assertFalse(exported.contains("EngineLayer6"));
        assertFalse(exported.contains("SecretAccountToken123"));
        assertFalse(exported.contains("user@example.com"));
        assertFalse(exported.contains("SecretFilename"));
        assertFalse(exported.contains("SecretClass"));
        for (String row : Files.readAllLines(new File(root, "events.0.jsonl").toPath(), StandardCharsets.UTF_8)) {
            java.util.regex.Matcher stack = java.util.regex.Pattern.compile("\"engine_stack\":\"([^\"]*)\"").matcher(row);
            assertTrue(stack.find()); assertFalse(stack.group(1).isEmpty());
            assertTrue(stack.group(1).split(";").length <= 8);
        }
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
        journal.record("engine", "retained_fault", 7, 0, new IllegalStateException("private"));
        for (int i = 0; i < 5000; i++) journal.record("installer", "state", i, i, null);
        long total = 0;
        for (File file : root.listFiles()) if (file.getName().startsWith("events.")) {
            assertTrue(file.length() <= DiagnosticJournal.SEGMENT_BYTES); total += file.length();
        }
        assertTrue(new File(root, "events.3.jsonl").isFile());
        assertTrue(total <= DiagnosticJournal.SEGMENTS * DiagnosticJournal.SEGMENT_BYTES);
        assertFalse(unzip(journal).contains("after_contention"));
        assertTrue("Ordinary traffic erased the bounded error history", unzip(journal).contains("retained_fault"));
        long errorBytes = 0;
        for (File file : root.listFiles()) if (file.getName().startsWith("errors.")) {
            assertTrue(file.length() <= DiagnosticJournal.ERROR_BYTES); errorBytes += file.length();
        }
        assertTrue(errorBytes <= DiagnosticJournal.ERROR_SEGMENTS * DiagnosticJournal.ERROR_BYTES);
    }

    @Test public void completedAccountCallbackSurvivesBriefJournalContention() throws Exception {
        File root = temporary.newFolder(); DiagnosticJournal journal = new DiagnosticJournal(root, 10, "engine_p0");
        journal.setEnabled(true);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (FileChannel channel = FileChannel.open(new File(root, "lock").toPath(), StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<?> result = pool.submit(() -> {
                started.countDown(); journal.record("account_callback", "getAuthToken.error_forwarded", 7, 7, null);
            });
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
            try { result.get(10, java.util.concurrent.TimeUnit.MILLISECONDS); fail("Callback did not wait for the held lock"); }
            catch (java.util.concurrent.TimeoutException expected) { }
            held.release(); result.get(1, java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(0, journal.lost());
        assertTrue(unzip(journal).contains("getAuthToken.error_forwarded"));
    }

    @Test public void completedCallbackLockWaitExpiresAndReportsLoss() throws Exception {
        File root = temporary.newFolder(); DiagnosticJournal journal = new DiagnosticJournal(root, 10, "engine_p0");
        journal.setEnabled(true);
        try (FileChannel channel = FileChannel.open(new File(root, "lock").toPath(), StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            long start = System.nanoTime();
            journal.record("account_callback", "addAccount.result_forwarded", 8, 33, null);
            long elapsed = System.nanoTime() - start;
            assertTrue("Held lock was not given a bounded wait", elapsed >= 100_000_000L);
            assertTrue("Journal lock blocked indefinitely", elapsed < 2_000_000_000L);
            assertEquals(1, journal.lost());
        }
        journal.record("ipc", "after_contention", 9, 0, null);
        assertTrue(unzip(journal).contains("\"dropped\":1"));
        assertFalse(unzip(journal).contains("addAccount.result_forwarded"));
    }

    @Test public void failedDiagnosticStorageDoesNotBecomeAnApplicationException() throws Exception {
        File root = temporary.newFolder(); DiagnosticJournal journal = new DiagnosticJournal(root, 10, "host");
        journal.setEnabled(true);
        Files.createDirectory(new File(root, "events.0.jsonl").toPath());
        journal.record("engine", "failure", 0, 0, new IllegalStateException("private"));
        assertEquals(1, journal.lost());
    }

    @Test public void slowExportProviderDoesNotHoldTheWriterMonitorOrFileLock() throws Exception {
        File root = temporary.newFolder(); DiagnosticJournal journal = new DiagnosticJournal(root, 10, "host");
        DiagnosticJournal other = new DiagnosticJournal(root, 11, "engine_server"); journal.setEnabled(true);
        journal.record("engine", "before_export", 1, 0, null);
        java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1), resume = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.io.OutputStream slow = new java.io.OutputStream() {
            @Override public void write(int value) throws java.io.IOException {
                if (first.compareAndSet(true, false)) {
                    writing.countDown();
                    try { if (!resume.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Fixture timeout"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
                }
            }
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<?> export = pool.submit(() -> { try { journal.export(slow, 34, 3, "fixture"); } catch (java.io.IOException failure) { throw new AssertionError(failure); } });
        try {
            assertTrue(writing.await(5, java.util.concurrent.TimeUnit.SECONDS));
            pool.submit(() -> journal.record("engine", "during_export", 2, 0, null)).get(1, java.util.concurrent.TimeUnit.SECONDS);
            other.record("engine", "other_process_during_export", 3, 0, null);
            assertEquals(0, other.lost());
        } finally { resume.countDown(); try { export.get(5, java.util.concurrent.TimeUnit.SECONDS); } finally { pool.shutdownNow(); } }
        String contents = unzip(journal); assertTrue(contents.contains("during_export")); assertTrue(contents.contains("other_process_during_export"));
    }

    private static String unzip(DiagnosticJournal journal) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); journal.export(bytes, 34, 3, "fixture");
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
