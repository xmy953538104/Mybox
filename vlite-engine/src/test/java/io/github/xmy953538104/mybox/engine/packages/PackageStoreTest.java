package io.github.xmy953538104.mybox.engine.packages;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PackageStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private File root, inputs;
    private PackageStore store;

    @Before public void setUp() throws Exception {
        root = temporary.newFolder("store"); inputs = temporary.newFolder("inputs");
        store = reopen();
    }

    private PackageStore reopen() { return new PackageStore(null, root, 35, new String[]{"arm64-v8a"}); }
    private File apk(SignedApkFixture fixture) throws Exception { return fixture.write(inputs, "signer-a"); }
    private InstalledPackage install(SignedApkFixture fixture) throws Exception { return store.install(Collections.singletonList(apk(fixture))); }

    @Test public void signedBaseAndSplitReopenWithRealManifestAndCertificate() throws Exception {
        File base = apk(new SignedApkFixture());
        File split = apk(new SignedApkFixture().split("config.en"));
        InstalledPackage installed = store.install(Arrays.asList(split, base));
        assertEquals("org.mybox.fixture", installed.packageName);
        assertEquals(1, installed.versionCode);
        assertEquals(Collections.singletonList("config.en"), installed.splitNames);
        assertEquals("base.apk", installed.baseApk.getName());
        assertEquals("split_config.en.apk", installed.splitApks.get(0).getName());
        assertEquals(64, installed.certificateSha256.get(0).length());
        assertEquals(Arrays.asList("org.mybox.fixture.FirstActivity", "org.mybox.fixture.SecondAlias"), installed.launchActivities);
        assertEquals(installed.baseApk, reopen().get(installed.packageName).baseApk);
        assertEquals(1, reopen().list().size());
    }

    @Test public void updatePublishesNewCodeAndKeepsDataIdentityAndPreviousGeneration() throws Exception {
        InstalledPackage before = install(new SignedApkFixture());
        File ce = new File(before.dataDir, "private-state");
        File de = new File(before.deviceProtectedDataDir, "boot-state");
        Files.write(ce.toPath(), "keep-ce".getBytes(StandardCharsets.UTF_8));
        Files.write(de.toPath(), "keep-de".getBytes(StandardCharsets.UTF_8));
        InstalledPackage after = install(new SignedApkFixture().version(2));
        assertEquals(2, reopen().get(before.packageName).versionCode);
        assertNotEquals(before.baseApk, after.baseApk);
        assertEquals(before.appId, after.appId);
        assertEquals(before.firstInstallTime, after.firstInstallTime);
        assertEquals(before.dataDir, after.dataDir);
        assertArrayEquals("keep-ce".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(ce.toPath()));
        assertArrayEquals("keep-de".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(de.toPath()));
        assertTrue(before.baseApk.isFile());
    }

    @Test public void changedSignerAndDowngradeCannotReplaceCommittedCodeOrData() throws Exception {
        InstalledPackage before = install(new SignedApkFixture().version(2));
        File state = new File(before.dataDir, "state"); Files.write(state.toPath(), new byte[]{42});
        reject(new SignedApkFixture().version(3).write(inputs, "signer-b"));
        reject(apk(new SignedApkFixture().version(1)));
        assertEquals(before.baseApk, reopen().get(before.packageName).baseApk);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(state.toPath()));
        assertEquals(1, before.baseApk.getParentFile().getParentFile().list().length);
    }

    @Test public void cryptographicTamperIsRejectedDespiteIntactCertificate() throws Exception {
        File signed = apk(new SignedApkFixture());
        byte[] bytes = Files.readAllBytes(signed.toPath());
        byte[] marker = SignedApkFixture.payload();
        int found = -1;
        outer: for (int index = 0; index <= bytes.length - marker.length; index++) {
            for (int offset = 0; offset < marker.length; offset++) if (bytes[index + offset] != marker[offset]) continue outer;
            found = index; break;
        }
        assertTrue("Payload was stored inside the APK", found >= 0);
        bytes[found] ^= 1; Files.write(signed.toPath(), bytes);
        reject(signed);
        assertTrue(store.list().isEmpty());
    }

    @Test public void splitSetRejectsMissingBaseDuplicateAndPackageVersionSignerMismatch() throws Exception {
        File base = apk(new SignedApkFixture());
        File split = apk(new SignedApkFixture().split("config.en"));
        reject(split); reject(base, base); reject(base, split, split);
        reject(base, apk(new SignedApkFixture().split("config.fr").packageName("org.other.fixture")));
        reject(base, apk(new SignedApkFixture().split("config.fr").version(2)));
        reject(base, new SignedApkFixture().split("config.fr").write(inputs, "signer-b"));
        assertTrue(store.list().isEmpty());
    }

    @Test public void splitDependenciesMustBePresentAndAcyclic() throws Exception {
        File base = apk(new SignedApkFixture());
        File feature = apk(new SignedApkFixture().split("feature"));
        File config = apk(new SignedApkFixture().split("config.feature.en").configFor("feature"));
        reject(base, config);
        reject(base, apk(new SignedApkFixture().split("one").dependency("two")),
                apk(new SignedApkFixture().split("two").dependency("one")));
        assertEquals(2, store.install(Arrays.asList(base, config, feature)).splitApks.size());
    }

    @Test public void unsupportedSplitComponentsIsolationAndSdkFailExplicitly() throws Exception {
        File base = apk(new SignedApkFixture());
        SignedApkFixture components = new SignedApkFixture().split("feature"); components.component = true;
        reject(base, apk(components));
        SignedApkFixture isolation = new SignedApkFixture(); isolation.isolated = true;
        reject(apk(isolation));
        SignedApkFixture future = new SignedApkFixture(); future.minSdk = 36;
        reject(apk(future));
    }

    @Test public void nativeLibrariesUseProcessAbiAndRejectIncompatibleOrDuplicateNames() throws Exception {
        SignedApkFixture nativeBase = new SignedApkFixture()
                .entry("lib/arm64-v8a/libfixture.so", new byte[]{1,2,3})
                .entry("lib/armeabi-v7a/libfixture.so", new byte[]{4,5,6});
        InstalledPackage installed = install(nativeBase);
        assertEquals("arm64-v8a", installed.primaryAbi);
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(new File(installed.nativeLibraryDir, "libfixture.so").toPath()));
        reject(apk(new SignedApkFixture().version(2).entry("lib/armeabi-v7a/libfixture.so", new byte[]{4})));
        reject(apk(new SignedApkFixture().version(2).entry("lib/arm64-v8a/libfixture.so", new byte[]{1})),
                apk(new SignedApkFixture().version(2).split("config.arm64").entry("lib/arm64-v8a/libfixture.so", new byte[]{2})));
        reject(apk(new SignedApkFixture().version(2).entry("lib/arm64-v8a/libfixture.so", new byte[]{1})
                .entry("lib/arm64-v8a/../../escape.so", new byte[]{2})));
        assertEquals(installed.baseApk, store.get(installed.packageName).baseApk);
        assertFalse(new File(root, "escape.so").exists());
    }

    @Test public void allocationFailureKeepsPreviousRecordAndRemovesUnpublishedCode() throws Exception {
        InstalledPackage before = install(new SignedApkFixture());
        // A second record causes nextAppId to fail only for a new install; this does not touch the old package.
        File corrupt = new File(root, "records/org.invalid.fixture.properties");
        Files.write(corrupt.toPath(), "format=invalid".getBytes(StandardCharsets.UTF_8));
        try {
            install(new SignedApkFixture().packageName("org.new.fixture"));
            fail("Corrupt registry must prevent ID allocation");
        } catch (IOException expected) { }
        assertEquals(before.baseApk, store.get(before.packageName).baseApk);
        File unpublishedVersions = new File(root, "packages/org.new.fixture/versions");
        assertEquals(0, unpublishedVersions.list().length);
    }

    @Test public void failedRecordRenameDoesNotPublishOrRetainNewCode() throws Exception {
        InstalledPackage before = install(new SignedApkFixture());
        File obstruction = new File(root, "records/org.new.fixture.properties");
        assertTrue(obstruction.mkdir());
        Files.write(new File(obstruction, "block-replacement").toPath(), new byte[]{1});
        try {
            install(new SignedApkFixture().packageName("org.new.fixture"));
            fail("A failed record rename must fail the installation");
        } catch (IOException expected) { }
        assertEquals(before.baseApk, store.get(before.packageName).baseApk);
        assertNull(store.get("org.new.fixture"));
        assertEquals(0, new File(root, "packages/org.new.fixture/versions").list().length);
        assertEquals("No abandoned temporary record", 2, new File(root, "records").list().length);
    }

    @Test public void concurrentStoreInstancesAllocateDistinctIdsAndSeeEachOthersCommits() throws Exception {
        File firstApk = apk(new SignedApkFixture().packageName("org.first.fixture"));
        File secondApk = apk(new SignedApkFixture().packageName("org.second.fixture"));
        PackageStore secondStore = reopen();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<InstalledPackage> first = workers.submit(() -> store.install(Collections.singletonList(firstApk)));
            Future<InstalledPackage> second = workers.submit(() -> secondStore.install(Collections.singletonList(secondApk)));
            InstalledPackage one = first.get(), two = second.get();
            assertNotEquals(one.appId, two.appId);
            assertEquals(one.baseApk, secondStore.get(one.packageName).baseApk);
            assertEquals(two.baseApk, store.get(two.packageName).baseApk);
            assertEquals(2, reopen().list().size());
        } finally { workers.shutdownNow(); }
    }

    private void reject(File... files) throws Exception {
        try { store.install(Arrays.asList(files)); fail("Invalid installation accepted"); }
        catch (PackageValidationException expected) { }
        File staging = new File(root, "staging");
        assertEquals("Failed installations must leave no staging directories", 0, staging.list().length);
    }
}
