package top.niunaijun.blackbox.core.system.pm;

import android.content.pm.Signature;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Properties;

import top.niunaijun.blackbox.BlackBoxCore;

/** Compatibility affects queries only. Update verification always uses BPackage.mSignatures. */
public final class MicroGSignaturePolicy {
    private static final String GOOGLE_CERT_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788";

    private MicroGSignaturePolicy() {}

    static boolean isApproved(File apk, BPackage pkg) {
        if (!"com.google.android.gms".equals(pkg.packageName) || pkg.mSignatures == null
                || pkg.mSignatures.length != 1 || requestedSignature(pkg) == null) return false;
        Properties pins = new Properties();
        try (InputStream in = BlackBoxCore.getContext().getAssets().open("mybox/trusted-microg.properties")) {
            pins.load(in);
            return digestFile(apk).equals(pins.getProperty("gms.apk.sha256", ""))
                    && digest("SHA-256", pkg.mSignatures[0].toByteArray())
                    .equals(pins.getProperty("gms.cert.sha256", ""));
        } catch (Exception e) {
            return false;
        }
    }

    public static Signature[] querySignatures(BPackage pkg) {
        Signature requested = pkg.trustedMicroG ? requestedSignature(pkg) : null;
        return requested == null ? pkg.mSignatures : new Signature[]{requested};
    }

    private static Signature requestedSignature(BPackage pkg) {
        try {
            if (!"com.google.android.gms".equals(pkg.packageName) || pkg.mAppMetaData == null) return null;
            Signature requested = new Signature(pkg.mAppMetaData.getString("fake-signature"));
            return GOOGLE_CERT_SHA1.equals(digest("SHA-1", requested.toByteArray())) ? requested : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String[] actualSignerDigests(Signature[] signatures) {
        if (signatures == null) return new String[0];
        String[] digests = new String[signatures.length];
        for (int i = 0; i < signatures.length; i++) digests[i] = digest("SHA-256", signatures[i].toByteArray());
        return digests;
    }

    private static String digestFile(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String digest(String algorithm, byte[] bytes) {
        try { return hex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = digits[(bytes[i] & 255) >>> 4];
            chars[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(chars);
    }
}
