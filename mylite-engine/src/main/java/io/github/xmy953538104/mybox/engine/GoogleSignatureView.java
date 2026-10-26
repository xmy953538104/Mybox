package io.github.xmy953538104.mybox.engine;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.IOException;
import java.security.MessageDigest;

/** Compatibility metadata/default enumeration only. Installation and credential ACLs retain real signers. */
final class GoogleSignatureView {
    static PackageInfo apply(Engine engine, PackageInfo info, int flags) throws Exception {
        if (info == null || !"com.google.android.gms".equals(info.packageName)
                || (flags & (PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES)) == 0) return info;
        Signature signature = compatibilitySignature(engine, engine.packages().get(info.packageName));
        if ((flags & PackageManager.GET_SIGNATURES) != 0) info.signatures = new Signature[]{signature};
        if (Build.VERSION.SDK_INT >= 28 && (flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
            info.signingInfo = signingInfo(signature);
        }
        return info;
    }

    static boolean usesAccountVisibilityDefault(Engine engine, InstalledPackage authenticator,
            InstalledPackage consumer) throws IOException {
        if (authenticator == null || consumer == null || !"com.google.android.gms".equals(authenticator.packageName)) return false;
        InstalledPackage current = engine.packages().get(authenticator.packageName);
        if (current == null || !current.baseApk.equals(authenticator.baseApk)
                || !current.certificateSha256.equals(authenticator.certificateSha256)) return false;
        String google = hash("SHA-256", compatibilitySignature(engine, current));
        // shortcut: current key only; support rotations after explicit-denial migration is verified.
        return engine.packages().verifiedSigning(consumer).currentSigners.equals(java.util.Collections.singletonList(google));
    }

    private static Signature compatibilitySignature(Engine engine, InstalledPackage installed) throws IOException {
        TrustedMicrog.require(engine.hostContext(), installed);
        PackageInfo metadata = engine.packages().getPackageInfo("com.google.android.gms", PackageManager.GET_META_DATA);
        InstalledPackage current = engine.packages().get("com.google.android.gms");
        if (current == null || !current.baseApk.equals(installed.baseApk)
                || !current.certificateSha256.equals(installed.certificateSha256)) {
            throw new SecurityException("Approved microG changed during compatibility metadata lookup");
        }
        String encoded = metadata.applicationInfo.metaData == null ? null
                : metadata.applicationInfo.metaData.getString("fake-signature");
        if (encoded == null) throw new SecurityException("Approved microG has no compatibility signature metadata");
        Signature signature = new Signature(encoded.trim());
        if (!"38918a453d07199354f8b19af05ec6562ced5788".equals(hash("SHA-1", signature))) {
            throw new SecurityException("Unexpected Google compatibility certificate");
        }
        return signature;
    }

    private static String hash(String algorithm, Signature signature) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(signature.toByteArray());
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static SigningInfo signingInfo(Signature signature) throws Exception {
        Class<?> detailsType;
        try { detailsType = Class.forName("android.content.pm.SigningDetails"); }
        catch (ClassNotFoundException olderAndroid) { detailsType = Class.forName("android.content.pm.PackageParser$SigningDetails"); }
        Object details = detailsType.getConstructor(Signature[].class, int.class)
                .newInstance(new Signature[]{signature}, 2);
        return (SigningInfo) SigningInfo.class.getConstructor(detailsType).newInstance(details);
    }
}
