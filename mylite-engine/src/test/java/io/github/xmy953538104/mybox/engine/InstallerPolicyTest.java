package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import io.github.xmy953538104.mybox.engine.packages.InstallerFixturePackages;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;

public final class InstallerPolicyTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String FIXTURE = "io.github.xmy953538104.mybox.fixture";
    private static final String CERT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String GOOGLE = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83";
    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    private static ByteArrayInputStream pins() {
        return bytes("fixture.package=" + FIXTURE + "\nfixture.cert.sha256=" + CERT
                + "\nfixture.apk.sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\n");
    }

    @Test public void publicDevelopmentSignerAloneCannotAuthorizeAnInstaller() throws Exception {
        InstallerPolicy.verifyFixture(pins(), FIXTURE, Collections.singletonList(CERT), bytes("abc"));
        try {
            InstallerPolicy.verifyFixture(pins(), FIXTURE, Collections.singletonList(CERT), bytes("different APK signed by same key"));
            fail("Same signer authorized unpinned code");
        } catch (SecurityException expected) { }
        try {
            InstallerPolicy.verifyFixture(pins(), "other", Collections.singletonList(CERT), bytes("abc"));
            fail("Pin authorized another package");
        } catch (SecurityException expected) { }
        try {
            InstallerPolicy.verifyFixture(pins(), FIXTURE, Collections.singletonList(GOOGLE), bytes("abc"));
            fail("Compatibility signer replaced real signer");
        } catch (SecurityException expected) { }
    }

    @Test public void storeAuthorityConsumesVerifiedApkEvidenceNotSignerStrings() throws Exception {
        PackageStore store = InstallerFixturePackages.store(temporary.newFolder("store"));
        java.io.File apk = InstallerFixturePackages.apk(temporary.newFolder("inputs"), "com.android.vending", 1, null, "signer-a");
        assertFalse(InstallerPolicy.isGoogleStore(store.verifyArchive(apk)));
        assertFalse(InstallerPolicy.isGoogleStore(store, store.install(Collections.singletonList(apk))));
        assertFalse(InstallerPolicy.isGoogleStore((io.github.xmy953538104.mybox.engine.packages.SigningIdentity) null));
        java.lang.reflect.Field anchor = InstallerPolicy.class.getDeclaredField("GOOGLE"); anchor.setAccessible(true);
        assertEquals("Production anchor changed", GOOGLE, anchor.get(null));
    }
}
