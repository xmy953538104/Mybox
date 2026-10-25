package io.github.xmy953538104.mybox.engine;

import io.github.xmy953538104.mybox.engine.installer.InstallerAuthorization;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;
import io.github.xmy953538104.mybox.engine.packages.SigningIdentity;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Properties;

/** Installation authority uses verified APK signers, never the Google compatibility view. */
final class InstallerPolicy implements InstallerAuthorization {
    private static final String FIXTURE = "io.github.xmy953538104.mybox.fixture";
    private static final String GOOGLE = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83";
    private final Engine engine;

    InstallerPolicy(Engine engine) { this.engine = engine; }

    @Override public void requireApproved(CallerIdentity caller) throws IOException {
        InstalledPackage installed = engine.packages().get(caller.packageName);
        if (installed == null || caller.userId != 0 || installed.appId != caller.appId
                || !installed.certificateSha256.equals(caller.certificateSha256)) {
            throw new SecurityException("Installer identity is no longer current");
        }
        if (isGoogleStore(engine.packages(), installed)) return;
        if (!FIXTURE.equals(installed.packageName) || !installed.splitApks.isEmpty()) {
            throw new SecurityException("This package is not an approved virtual installer");
        }
        // This asset is generated only in debug from the fixture APK built in the same run.
        try (InputStream pins = engine.hostContext().getAssets().open("mybox/trusted-installer-fixture.properties");
             InputStream apk = new FileInputStream(installed.baseApk)) {
            verifyFixture(pins, installed.packageName, installed.certificateSha256, apk);
        }
    }

    static boolean isGoogleStore(PackageStore packages, InstalledPackage installed) throws IOException {
        return installed != null && "com.android.vending".equals(installed.packageName)
                && isGoogleStore(packages.verifiedSigning(installed));
    }

    static boolean isGoogleStore(SigningIdentity verified) {
        return verified != null && verified.isSinglePublisher("com.android.vending", GOOGLE);
    }

    static void verifyFixture(InputStream input, String name, List<String> realCertificates, InputStream apk) throws IOException {
        Properties pins = new Properties();
        pins.load(input);
        String expectedApk = pins.getProperty("fixture.apk.sha256", "");
        String expectedCert = pins.getProperty("fixture.cert.sha256", "");
        if (!FIXTURE.equals(name) || !FIXTURE.equals(pins.getProperty("fixture.package"))
                || !expectedApk.matches("[0-9a-f]{64}") || !expectedCert.matches("[0-9a-f]{64}")
                || realCertificates.size() != 1 || !expectedCert.equals(realCertificates.get(0))) {
            throw new SecurityException("Installer fixture package or real signer is not pinned");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int count; (count = apk.read(buffer)) != -1;) digest.update(buffer, 0, count);
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (!expectedApk.equals(actual.toString())) throw new SecurityException("Installer fixture bytes are not pinned");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
