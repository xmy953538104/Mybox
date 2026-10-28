package io.github.xmy953538104.mybox.engine.packages;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real v2/v3/v3.1 APKs signed by the existing public test keys, not fabricated certificate strings. */
public final class SigningRotationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private File inputs, root;
    private PackageStore store;
    @Before public void prepare() throws Exception {
        inputs = temporary.newFolder("apks"); root = temporary.newFolder("installed"); store = reopen();
    }
    private PackageStore reopen() { return new PackageStore(null, root, 35, new String[]{"arm64-v8a"}); }
    private File rotated(SignedApkFixture fixture, boolean data) throws Exception {
        return fixture.writeRotated(inputs, "signer-a", "signer-b", data, 28);
    }

    @Test public void verifiedRotationIsDirectionalAndPlatformSelected() throws Exception {
        File original = new SignedApkFixture().write(inputs, "signer-a");
        SigningIdentity a = store.verifyArchive(original);
        File rotation = new SignedApkFixture().version(2).writeRotated(inputs, "signer-a", "signer-b", true, 33);
        SigningIdentity oldPlatform = VerifiedApk.read(rotation, 32).signing;
        SigningIdentity newPlatform = VerifiedApk.read(rotation, 35).signing;
        assertEquals(a.currentSigners, oldPlatform.currentSigners);
        assertNotEquals(a.currentSigners, newPlatform.currentSigners);
        assertEquals(a.currentSigners, newPlatform.legacyQuerySigners());
        assertEquals(a.currentSigners, oldPlatform.legacyQuerySigners());
        assertNotEquals(newPlatform.currentSigners, newPlatform.legacyQuerySigners());
        assertTrue(newPlatform.isSinglePublisher(a.packageName, a.currentSigners.get(0)));
        assertTrue(newPlatform.canUpdate(a)); assertFalse(a.canUpdate(newPlatform));
        SigningIdentity unrelated = store.verifyArchive(new SignedApkFixture().write(inputs, "signer-b"));
        assertFalse(unrelated.isSinglePublisher(a.packageName, a.currentSigners.get(0)));
        assertFalse(unrelated.canUpdate(a));
        assertFalse(newPlatform.isSinglePublisher("com.android.vending", a.currentSigners.get(0)));
        assertEquals("No public history-string constructor", 0, SigningIdentity.class.getConstructors().length);
    }

    @Test public void rotatedUpgradePreservesDataAndColdProof() throws Exception {
        InstalledPackage old = store.install(Collections.singletonList(new SignedApkFixture().write(inputs, "signer-a")));
        File ce = new File(old.dataDir, "state"), de = new File(old.deviceProtectedDataDir, "state");
        Files.write(ce.toPath(), new byte[]{17}); Files.write(de.toPath(), new byte[]{23});
        InstalledPackage next = store.install(Collections.singletonList(rotated(new SignedApkFixture().version(2), true)));
        assertEquals(old.appId, next.appId); assertEquals(old.firstInstallTime, next.firstInstallTime);
        assertNotEquals(old.certificateSha256, next.certificateSha256);
        assertArrayEquals(new byte[]{17}, Files.readAllBytes(ce.toPath()));
        assertArrayEquals(new byte[]{23}, Files.readAllBytes(de.toPath()));
        SigningIdentity cold = reopen().verifiedSigning(reopen().get(next.packageName));
        assertTrue(cold.isSinglePublisher(old.packageName, old.certificateSha256.get(0)));
        try {
            store.install(Collections.singletonList(new SignedApkFixture().version(3).write(inputs, "signer-a")));
            fail("A higher version with an old signer reversed the rotation");
        } catch (PackageValidationException expected) { }
        assertEquals(next.baseApk, reopen().get(next.packageName).baseApk);
    }

    @Test public void multipleCurrentSignersCannotBecomeSinglePublisher() throws Exception {
        File single = new SignedApkFixture().packageName("com.android.vending").write(inputs, "signer-a");
        SigningIdentity original = store.verifyArchive(single);
        File multi = new SignedApkFixture().packageName("com.android.vending")
                .writeMultiSigned(inputs, "signer-a", "signer-b");
        InstalledPackage installed = store.install(Collections.singletonList(multi));
        SigningIdentity actual = reopen().verifiedSigning(reopen().get(installed.packageName));
        assertEquals(2, actual.currentSigners.size());
        assertEquals(actual.currentSigners, actual.legacyQuerySigners());
        assertTrue(actual.currentSigners.contains(original.currentSigners.get(0)));
        for (String current : actual.currentSigners)
            assertFalse(actual.isSinglePublisher("com.android.vending", current));
        assertFalse(actual.canUpdate(original));
        assertFalse(original.canUpdate(actual));
    }

    @Test public void sameSignerUpdateCannotEraseHistoryBeforeAReverseRotation() throws Exception {
        InstalledPackage original = store.install(Collections.singletonList(new SignedApkFixture().write(inputs, "signer-a")));
        InstalledPackage rotated = store.install(Collections.singletonList(rotated(new SignedApkFixture().version(2), true)));
        File ce = new File(rotated.dataDir, "history-state"), de = new File(rotated.deviceProtectedDataDir, "history-state");
        Files.write(ce.toPath(), new byte[]{31}); Files.write(de.toPath(), new byte[]{47});
        PackageStore cold = reopen();
        SigningIdentity previous = cold.verifiedSigning(cold.get(rotated.packageName));
        File stripped = new SignedApkFixture().version(3).write(inputs, "signer-b");
        SigningIdentity withoutHistory = cold.verifyArchive(stripped);
        assertEquals(previous.currentSigners, withoutHistory.currentSigners);
        assertFalse("Same current signer must not erase the installed rotation proof", withoutHistory.canUpdate(previous));
        try { cold.install(Collections.singletonList(stripped)); fail("A same-signer APK removed installed signing history"); }
        catch (PackageValidationException expected) { }
        cold = reopen();
        InstalledPackage retained = cold.get(rotated.packageName);
        assertEquals(rotated.baseApk, retained.baseApk); assertEquals(rotated.versionCode, retained.versionCode);
        assertTrue(cold.verifiedSigning(retained).isSinglePublisher(original.packageName, original.certificateSha256.get(0)));
        assertArrayEquals(new byte[]{31}, Files.readAllBytes(ce.toPath()));
        assertArrayEquals(new byte[]{47}, Files.readAllBytes(de.toPath()));

        File complete = rotated(new SignedApkFixture().version(3), true);
        InstalledPackage next = cold.install(Collections.singletonList(complete));
        assertNotEquals(rotated.baseApk, next.baseApk); assertEquals(3, next.versionCode);
        assertEquals(rotated.appId, next.appId); assertEquals(rotated.firstInstallTime, next.firstInstallTime);
        assertEquals(rotated.certificateSha256, next.certificateSha256);
        cold = reopen();
        assertTrue(cold.verifiedSigning(cold.get(next.packageName)).isSinglePublisher(original.packageName, original.certificateSha256.get(0)));
        File reverse = new SignedApkFixture().version(4).writeRotated(inputs, "signer-b", "signer-a", true, 28);
        try { cold.install(Collections.singletonList(reverse)); fail("A later inverse proof reversed the retained history"); }
        catch (PackageValidationException expected) { }
        assertEquals(next.baseApk, reopen().get(next.packageName).baseApk);
        assertArrayEquals(new byte[]{31}, Files.readAllBytes(ce.toPath()));
        assertArrayEquals(new byte[]{47}, Files.readAllBytes(de.toPath()));
    }

    @Test public void reverseRotationProofCannotUndoInstalledLineage() throws Exception {
        InstalledPackage original = store.install(Collections.singletonList(new SignedApkFixture().write(inputs, "signer-a")));
        InstalledPackage current = store.install(Collections.singletonList(rotated(new SignedApkFixture().version(2), true)));
        File state = new File(current.dataDir, "state"); Files.write(state.toPath(), new byte[]{41});
        File reverse = new SignedApkFixture().version(3).writeRotated(inputs, "signer-b", "signer-a", true, 28);
        SigningIdentity reverseProof = store.verifyArchive(reverse);
        // The B-to-A artifact itself verifies; installed A-to-B history must still reject this reversal.
        assertEquals(original.certificateSha256, reverseProof.currentSigners);
        assertTrue(reverseProof.isSinglePublisher(current.packageName, current.certificateSha256.get(0)));
        assertFalse(reverseProof.canUpdate(store.verifiedSigning(current)));
        try { store.install(Collections.singletonList(reverse)); fail("A new reverse PoR undid installed signing history"); }
        catch (PackageValidationException expected) { }
        assertEquals(current.baseApk, reopen().get(current.packageName).baseApk);
        assertArrayEquals(new byte[]{41}, Files.readAllBytes(state.toPath()));
    }

    @Test public void publisherProofDoesNotInventInstalledDataCapability() throws Exception {
        InstalledPackage old = store.install(Collections.singletonList(new SignedApkFixture().write(inputs, "signer-a")));
        File withoutData = rotated(new SignedApkFixture().version(2), false);
        SigningIdentity publisher = store.verifyArchive(withoutData);
        assertTrue(publisher.isSinglePublisher(old.packageName, old.certificateSha256.get(0)));
        try { store.install(Collections.singletonList(withoutData)); fail("Update without installed-data capability passed"); }
        catch (PackageValidationException expected) { }
        assertEquals(old.baseApk, reopen().get(old.packageName).baseApk);
    }

    @Test public void everySplitHasTheSameActualSignerAndCompatibleProof() throws Exception {
        File base = rotated(new SignedApkFixture(), true);
        File wrongSigner = new SignedApkFixture().split("config.en").write(inputs, "signer-a");
        rejectSet(base, wrongSigner);
        File incompatible = rotated(new SignedApkFixture().split("config.en"), false);
        rejectSet(base, incompatible);
        // A current-key split needs no redundant PoR: its actual signature is the proven base key.
        File currentOnly = new SignedApkFixture().split("config.en").write(inputs, "signer-b");
        InstalledPackage installed = store.install(Arrays.asList(base, currentOnly));
        SigningIdentity actual = reopen().verifiedSigning(reopen().get(installed.packageName));
        String anchor = store.verifyArchive(new SignedApkFixture().write(inputs, "signer-a")).currentSigners.get(0);
        assertTrue(actual.isSinglePublisher(installed.packageName, anchor));
    }

    @Test public void stringsInApkOrRecordCannotCreateRotationProof() throws Exception {
        SigningIdentity rootSigner = store.verifyArchive(new SignedApkFixture().write(inputs, "signer-a"));
        File invented = new SignedApkFixture().entry("assets/signing-history.txt",
                rootSigner.currentSigners.get(0).getBytes(java.nio.charset.StandardCharsets.UTF_8)).write(inputs, "signer-b");
        InstalledPackage installed = store.install(Collections.singletonList(invented));
        assertFalse(store.verifiedSigning(installed).isSinglePublisher(installed.packageName, rootSigner.currentSigners.get(0)));
        File record = new File(root, "records/" + installed.packageName + ".properties");
        Properties values = new Properties(); try (FileInputStream input = new FileInputStream(record)) { values.load(input); }
        values.setProperty("signer.0", rootSigner.currentSigners.get(0));
        values.setProperty("history.0", rootSigner.currentSigners.get(0));
        try (FileOutputStream output = new FileOutputStream(record)) { values.store(output, "untrusted fixture metadata"); }
        PackageStore cold = reopen();
        try { cold.verifiedSigning(cold.get(installed.packageName)); fail("Record strings overrode actual APK signature"); }
        catch (SecurityException expected) { }
    }

    @Test public void corruptRotationAttributeCannotAuthorizeAnApk() throws Exception {
        File apk = rotated(new SignedApkFixture(), true);
        byte[] proof = com.android.apksig.SigningCertificateLineage.readFromApkFile(apk).encodeSigningCertificateLineage();
        byte[] bytes = Files.readAllBytes(apk.toPath());
        int found = -1;
        outer: for (int i = 0; i <= bytes.length - proof.length; i++) {
            for (int j = 0; j < proof.length; j++) if (bytes[i + j] != proof[j]) continue outer;
            found = i; break;
        }
        assertTrue("Fixture did not contain its actual PoR attribute", found >= 0);
        bytes[found + proof.length - 1] ^= 1; Files.write(apk.toPath(), bytes);
        try { store.verifyArchive(apk); fail("Corrupt cryptographic rotation proof was accepted"); }
        catch (PackageValidationException expected) { }
    }

    @Test public void sealedCacheInvalidatesPermissionAndByteChanges() throws Exception {
        InstalledPackage installed = store.install(Collections.singletonList(new SignedApkFixture().write(inputs, "signer-a")));
        Thread.sleep(2100); // Same coarse-timestamp cooldown as the production trust cache.
        java.util.concurrent.ExecutorService readers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<SigningIdentity>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(readers.submit(() -> store.verifiedSigning(installed)));
            SigningIdentity first = results.get(0).get();
            for (java.util.concurrent.Future<SigningIdentity> result : results) {
                SigningIdentity actual = result.get();
                assertEquals(first.currentSigners, actual.currentSigners);
                if (Files.getFileStore(installed.baseApk.toPath()).supportsFileAttributeView("unix")) assertSame(first, actual);
            }
        } finally { readers.shutdownNow(); }
        assertTrue(installed.baseApk.setWritable(true));
        try { store.verifiedSigning(installed); fail("Writable APK reused a trusted cache entry"); }
        catch (SecurityException expected) { }
        byte[] bytes = Files.readAllBytes(installed.baseApk.toPath()); bytes[64] ^= 1;
        Files.write(installed.baseApk.toPath(), bytes); assertTrue(installed.baseApk.setReadOnly());
        try { store.verifiedSigning(installed); fail("Modified APK reused an old verified identity"); }
        catch (PackageValidationException expected) { }
    }

    @Test public void importPolicyChecksTheCopiedPublisherBeforePublication() throws Exception {
        File apk = new SignedApkFixture().write(inputs, "signer-b");
        try { store.installWithPublisher(Collections.singletonList(apk), "org.mybox.fixture", identity -> false); fail("Rejected publisher was published"); }
        catch (SecurityException expected) { }
        assertNull(store.get("org.mybox.fixture"));
    }

    private void rejectSet(File base, File split) throws Exception {
        try { store.install(Arrays.asList(base, split)); fail("Inconsistent signed split set was accepted"); }
        catch (PackageValidationException expected) { }
        assertNull(store.get("org.mybox.fixture"));
    }
}
