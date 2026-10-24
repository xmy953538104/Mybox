package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Properties;

/** Only the exact source-built service approved in the host's own asset may use privileged bridges. */
final class TrustedMicrog {
    private final String apkHash;
    private final String signerHash;

    TrustedMicrog(InputStream input) throws IOException {
        Properties pins = new Properties();
        pins.load(input);
        apkHash = requireHash(pins, "gms.apk.sha256");
        signerHash = requireHash(pins, "gms.cert.sha256");
    }

    static void require(Context context, InstalledPackage installed) throws IOException {
        if (installed == null || !installed.splitApks.isEmpty()) throw new SecurityException("Pinned microG package is absent");
        try (InputStream properties = context.getAssets().open("mybox/trusted-microg.properties");
             InputStream apk = new FileInputStream(installed.baseApk)) {
            new TrustedMicrog(properties).verify(installed.packageName, installed.certificateSha256, apk);
        }
    }

    void verify(String packageName, List<String> signers, InputStream apk) throws IOException {
        if (!"com.google.android.gms".equals(packageName) || signers.size() != 1 || !signerHash.equals(signers.get(0))) {
            throw new SecurityException("Service package or signing identity is not trusted");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = apk.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (!apkHash.equals(actual.toString())) throw new SecurityException("Service APK differs from the approved build");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String requireHash(Properties pins, String key) throws IOException {
        String hash = pins.getProperty(key, "");
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid service trust pin: " + key);
        return hash;
    }
}
