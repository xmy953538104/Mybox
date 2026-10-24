package io.github.xmy953538104.mybox.engine;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import java.security.MessageDigest;

/** Compatibility metadata only. Package installation and account ACLs retain the real signer. */
final class GoogleSignatureView {
    static PackageInfo apply(Engine engine, PackageInfo info, int flags) throws Exception {
        if (info == null || !"com.google.android.gms".equals(info.packageName)
                || (flags & (PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES)) == 0) return info;
        TrustedMicrog.require(engine.hostContext(), engine.packages().get(info.packageName));
        PackageInfo metadata = engine.packages().getPackageInfo(info.packageName, PackageManager.GET_META_DATA);
        String encoded = metadata.applicationInfo.metaData == null ? null
                : metadata.applicationInfo.metaData.getString("fake-signature");
        if (encoded == null) throw new SecurityException("Approved microG has no compatibility signature metadata");
        Signature signature = new Signature(encoded.trim());
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray());
        StringBuilder hex = new StringBuilder(40);
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!"38918a453d07199354f8b19af05ec6562ced5788".equals(hex.toString())) {
            throw new SecurityException("Unexpected Google compatibility certificate");
        }
        if ((flags & PackageManager.GET_SIGNATURES) != 0) info.signatures = new Signature[]{signature};
        if (Build.VERSION.SDK_INT >= 28 && (flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
            info.signingInfo = signingInfo(signature);
        }
        return info;
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
