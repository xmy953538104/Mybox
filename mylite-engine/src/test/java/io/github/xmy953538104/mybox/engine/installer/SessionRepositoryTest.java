package io.github.xmy953538104.mybox.engine.installer;

import static org.junit.Assert.*;
import io.github.xmy953538104.mybox.engine.CallerIdentity;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.packages.InstallerFixturePackages;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SessionRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File inputs, sessionsRoot;
    private PackageStore packages;
    private PackageInstallerBackend backend;
    private SessionRepository sessions;
    private CallerIdentity owner, other;
    private static final String TARGET = "org.mybox.installtarget";

    @Before public void setup() throws Exception {
        inputs = temporary.newFolder("inputs"); sessionsRoot = temporary.newFolder("sessions");
        packages = InstallerFixturePackages.store(temporary.newFolder("packages"));
        owner = identity(packages.install(Collections.singletonList(apk("org.mybox.installer", 1, null, "signer-a"))), 100);
        other = identity(packages.install(Collections.singletonList(apk("org.mybox.other", 1, null, "signer-b"))), 101);
        backend = new PackageInstallerBackend(packages); sessions = reopen();
    }
    private SessionRepository reopen() throws IOException { return new SessionRepository(sessionsRoot, backend, caller -> { if (!caller.packageName.equals(owner.packageName)) throw new SecurityException("Not approved"); }); }
    private File apk(String name, int version, String split, String signer) throws Exception { return InstallerFixturePackages.apk(inputs, name, version, split, signer); }
    private int create() throws IOException { return sessions.create(owner, new InstallRequest(TARGET, -1, "Fixture")); }
    private void write(int id, String name, File apk) throws IOException {
        byte[] bytes = Files.readAllBytes(apk.toPath());
        try (SessionRepository.Lease lease = sessions.openWrite(owner, id, name, 0, bytes.length)) { assertEquals(bytes.length, lease.write(0, bytes, bytes.length)); lease.sync(); }
    }
    private static CallerIdentity identity(InstalledPackage pkg, int pid) throws Exception {
        Constructor<CallerIdentity> constructor = CallerIdentity.class.getDeclaredConstructor(String.class, int.class, int.class, List.class);
        constructor.setAccessible(true); return constructor.newInstance(pkg.packageName, pkg.appId, pid, pkg.certificateSha256);
    }

    @Test public void interruptedInitialJournalOnlyCleansProvablyEmptyUnpublishedSession() throws Exception {
        File empty = new File(sessionsRoot, "123456"); assertTrue(new File(empty, "stage").mkdirs());
        Files.write(new File(empty, "journal-12345678-1234-1234-1234-123456789abc.tmp").toPath(), new byte[]{1});
        reopen(); assertFalse(empty.exists());
        File nonempty = new File(sessionsRoot, "123457"); File stage = new File(nonempty, "stage"); assertTrue(stage.mkdirs());
        File retained = new File(stage, "unpublished-input"); Files.write(retained.toPath(), new byte[]{2});
        try { reopen(); fail("Unknown unpublished payload must not be erased"); } catch (IOException expected) { }
        assertTrue(retained.isFile());
    }

    @Test public void failedWriterRemainsUncommittableAfterServerRestart() throws Exception {
        int id = create(); byte[] bytes = Files.readAllBytes(apk(TARGET, 1, null, "signer-a").toPath());
        SessionRepository.Lease lease = sessions.openWrite(owner, id, "base", 0, bytes.length);
        lease.write(0, bytes, bytes.length);
        java.lang.reflect.Field channel = lease.getClass().getDeclaredField("channel"); channel.setAccessible(true);
        ((java.nio.channels.FileChannel) channel.get(lease)).close();
        try { lease.sync(); fail(); } catch (IOException expected) { }
        try { lease.close(); } catch (IOException expected) { }
        SessionRepository recovered = reopen();
        try { recovered.prepareCommit(owner, id); fail("A persisted write failure must prevent commit"); } catch (IOException expected) { }
        assertNull(packages.get(TARGET));
    }

    @Test public void fullSignedBaseAndConfigSplitsPublishOnlyAfterWriterClose() throws Exception {
        int id = create(); byte[] bytes = Files.readAllBytes(apk(TARGET, 1, null, "signer-a").toPath());
        SessionRepository.Lease writer = sessions.openWrite(owner, id, "download-base", 0, bytes.length);
        writer.write(0, bytes, bytes.length);
        try { sessions.prepareCommit(owner, id); fail(); } catch (IllegalStateException expected) { }
        assertNull(packages.get(TARGET)); writer.close();
        write(id, "download-config", apk(TARGET, 1, "config.en", "signer-a"));
        sessions.seal(owner, id);
        try { sessions.openWrite(owner, id, "another", 0, 1); fail(); } catch (SecurityException expected) { }
        SessionRepository.CommitOutcome outcome = sessions.prepareCommit(owner, id).run();
        assertEquals(0, outcome.status); assertTrue(outcome.journaled);
        assertEquals(Collections.singletonList("config.en"), packages.get(TARGET).splitNames);
        assertEquals("SUCCEEDED", reopen().get(owner, id).state);
        assertEquals(owner.packageName, packages.get(TARGET).installerPackageName);
    }

    @Test public void ownerAndRealSignerAreRequiredForReadWriteQueryAndCommit() throws Exception {
        int id = create(); write(id, "base", apk(TARGET, 1, null, "signer-a"));
        assertTrue(sessions.list(other).isEmpty());
        deny(() -> sessions.get(other, id)); deny(() -> sessions.openRead(other, id, "base"));
        deny(() -> sessions.openWrite(other, id, "base", 0, -1)); deny(() -> sessions.prepareCommit(other, id));
        deny(() -> sessions.abandon(other, id)); deny(() -> sessions.create(other, new InstallRequest(TARGET, -1, null)));
        Constructor<CallerIdentity> constructor = CallerIdentity.class.getDeclaredConstructor(String.class, int.class, int.class, List.class); constructor.setAccessible(true);
        CallerIdentity forged = constructor.newInstance(owner.packageName, owner.appId, owner.pid, other.certificateSha256);
        deny(() -> sessions.get(forged, id));
    }

    @Test public void stagingBoundsAndNamesCannotEscapeOrOverflow() throws Exception {
        int id = create();
        for (String name : Arrays.asList("../outside", "a/b", "a\\b", ".", "..", "", "bad,name")) {
            try { sessions.openWrite(owner, id, name, 0, 1); fail(name); } catch (IllegalArgumentException expected) { }
        }
        try { sessions.openWrite(owner, id, "base", -1, 1); fail(); } catch (IllegalArgumentException expected) { }
        try { sessions.openWrite(owner, id, "base", 0, SessionRepository.MAX_FILE_BYTES + 1); fail(); } catch (IllegalArgumentException expected) { }
        try (SessionRepository.Lease lease = sessions.openWrite(owner, id, "base", 0, 4)) {
            try { lease.write(3, new byte[2], 2); fail(); } catch (IOException expected) { }
            assertEquals(4, lease.size());
        }
    }

    @Test public void resumeReadAndAbandonRevokeOutstandingDescriptors() throws Exception {
        int id = create();
        try (SessionRepository.Lease write = sessions.openWrite(owner, id, "base", 0, 6)) { write.write(0, new byte[]{1, 2, 3}, 3); }
        try (SessionRepository.Lease write = sessions.openWrite(owner, id, "base", 3, 6)) { write.write(3, new byte[]{4, 5, 6}, 3); }
        SessionRepository.Lease reader = sessions.openRead(owner, id, "base"); byte[] bytes = new byte[6];
        assertEquals(6, reader.read(0, bytes, 6)); assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, bytes);
        sessions.abandon(owner, id);
        try { reader.read(0, bytes, 6); fail(); } catch (IOException expected) { }
        assertEquals("ABANDONED", reopen().get(owner, id).state);
    }

    @Test public void signedUpdatesKeepDataAndRejectWrongSignerDowngradeAndWrongTarget() throws Exception {
        InstalledPackage before = packages.install(Collections.singletonList(apk(TARGET, 2, null, "signer-a")));
        File data = new File(before.dataDir, "keep"); Files.write(data.toPath(), new byte[]{42});
        for (File rejected : Arrays.asList(apk(TARGET, 3, null, "signer-b"), apk(TARGET, 1, null, "signer-a"), apk("org.mybox.wrong", 3, null, "signer-a"))) {
            int id = create(); write(id, "base", rejected); assertEquals(4, sessions.prepareCommit(owner, id).run().status);
            assertEquals(before.baseApk, packages.get(TARGET).baseApk); assertArrayEquals(new byte[]{42}, Files.readAllBytes(data.toPath()));
        }
        int id = create(); write(id, "base", apk(TARGET, 3, null, "signer-a")); assertEquals(0, sessions.prepareCommit(owner, id).run().status);
        InstalledPackage after = packages.get(TARGET); assertEquals(before.appId, after.appId); assertEquals(before.dataDir, after.dataDir);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(data.toPath()));
    }

    @Test public void restartRecoversOnlyExactPublishedApkBytes() throws Exception {
        int id = create(); File apk = apk(TARGET, 1, null, "signer-a"); write(id, "base", apk); sessions.prepareCommit(owner, id);
        backend.install(Collections.singletonList(apk), TARGET, owner);
        assertEquals("SUCCEEDED", reopen().get(owner, id).state);
        sessions = reopen(); int interrupted = create(); write(interrupted, "base", apk(TARGET, 2, null, "signer-a")); sessions.prepareCommit(owner, interrupted);
        assertEquals("FAILED", reopen().get(owner, interrupted).state);
        assertEquals(1, packages.get(TARGET).versionCode);
    }

    @Test public void identicalManualInstallDoesNotProveAnInterruptedInstallerCommit() throws Exception {
        int id = create(); File input = apk(TARGET, 1, null, "signer-a"); write(id, "base", input); sessions.prepareCommit(owner, id);
        packages.install(Collections.singletonList(input));
        assertNull(packages.get(TARGET).installerPackageName);
        assertEquals("FAILED", reopen().get(owner, id).state);
    }

    @Test public void publicationRejectsForgedInstallerBeforeChangingPackageOrProvenance() throws Exception {
        File input = apk(TARGET, 1, null, "signer-a"); backend.install(Collections.singletonList(input), TARGET, owner);
        Constructor<CallerIdentity> constructor = CallerIdentity.class.getDeclaredConstructor(String.class, int.class, int.class, List.class);
        constructor.setAccessible(true);
        CallerIdentity forged = constructor.newInstance(owner.packageName, owner.appId, owner.pid, other.certificateSha256);
        deny(() -> backend.install(Collections.singletonList(apk(TARGET, 2, null, "signer-a")), TARGET, forged));
        assertEquals(1, packages.get(TARGET).versionCode); assertEquals(owner.packageName, packages.get(TARGET).installerPackageName);
    }

    @Test public void commitIsSingleUseAndUnauthorizedOnNewIdentity() throws Exception {
        int id = create(); write(id, "base", apk(TARGET, 1, null, "signer-a"));
        SessionRepository.CommitPlan plan = sessions.prepareCommit(owner, id);
        try { sessions.abandon(owner, id); fail(); } catch (IllegalStateException expected) { }
        assertEquals(0, plan.run().status);
        try { plan.run(); fail(); } catch (IllegalStateException expected) { }
        try { sessions.prepareCommit(owner, id); fail(); } catch (IllegalStateException expected) { }
    }

    private interface Action { void run() throws Exception; }
    private static void deny(Action action) throws Exception { try { action.run(); fail("Unauthorized installer operation passed"); } catch (SecurityException expected) { } }
}
