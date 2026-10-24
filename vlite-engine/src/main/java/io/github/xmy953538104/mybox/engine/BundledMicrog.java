package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.content.pm.PackageInfo;
import com.android.apksig.ApkVerifier;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Imports the audited service asset into the virtual package store, never the system installer. */
public final class BundledMicrog {
    public static InstalledPackage install() throws Exception {
        Engine engine = Engine.get();
        Context context = engine.hostContext();
        InstalledPackage installed = engine.packages().get("com.google.android.gms");
        if (installed != null) {
            try { TrustedMicrog.require(context, installed); return installed; }
            catch (SecurityException | IOException differentBuild) { /* Validate the replacement before changing its record. */ }
        }
        File apk = File.createTempFile("bundled-microg-", ".apk", context.getCacheDir());
        try {
            try (InputStream input = context.getAssets().open("mybox/microg-nohms.apk"); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    total += count;
                    if (total > 512L * 1024 * 1024) throw new IOException("Bundled service exceeds its size limit");
                    out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            ApkVerifier.Result verified = new ApkVerifier.Builder(apk).build().verify();
            if (!verified.isVerified()) throw new SecurityException("Bundled service APK signature is invalid");
            List<String> signers = new ArrayList<>();
            for (X509Certificate signer : verified.getSignerCertificates()) {
                StringBuilder hash = new StringBuilder(64);
                for (byte value : MessageDigest.getInstance("SHA-256").digest(signer.getEncoded())) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                signers.add(hash.toString());
            }
            PackageInfo parsed = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (parsed == null) throw new IOException("Bundled service manifest is unreadable");
            try (InputStream properties = context.getAssets().open("mybox/trusted-microg.properties"); InputStream bytes = new FileInputStream(apk)) {
                new TrustedMicrog(properties).verify(parsed.packageName, signers, bytes);
            }
            installed = engine.install(Collections.singletonList(apk));
            TrustedMicrog.require(context, installed);
            return installed;
        } finally { if (!apk.delete() && apk.exists()) android.util.Log.w("MyboxEngine", "Cannot remove temporary service APK"); }
    }
    private BundledMicrog() { }
}
