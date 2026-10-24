package io.github.xmy953538104.mybox.engine.packages;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class VerifiedApk {
    final File file;
    final ApkManifest manifest;
    final List<String> signers;

    private VerifiedApk(File file, ApkManifest manifest, List<String> signers) {
        this.file = file;
        this.manifest = manifest;
        this.signers = signers;
    }

    static VerifiedApk read(File file, int platformSdk) throws IOException {
        try {
            ApkVerifier.Result result = new ApkVerifier.Builder(file)
                    .setMinCheckedPlatformVersion(platformSdk)
                    .setMaxCheckedPlatformVersion(platformSdk).build().verify();
            if (!result.isVerified() || result.getSignerCertificates().isEmpty()) {
                throw new PackageValidationException("APK signature verification failed");
            }
            List<String> signers = new ArrayList<>();
            for (X509Certificate certificate : result.getSignerCertificates()) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
                StringBuilder hex = new StringBuilder(64);
                for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
                signers.add(hex.toString());
            }
            Collections.sort(signers);
            try (ZipFile zip = new ZipFile(file)) {
                Set<String> names = new HashSet<>();
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    if (!names.add(entries.nextElement().getName())) {
                        throw new PackageValidationException("APK contains duplicate ZIP entries");
                    }
                }
            }
            ApkManifest manifest = ApkManifest.read(file);
            if (manifest.minSdk > platformSdk) throw new PackageValidationException("APK requires a newer Android version");
            if (manifest.isolatedSplits) throw new PackageValidationException("Isolated split class loaders are not implemented");
            if (manifest.splitName != null && manifest.hasComponents) {
                throw new PackageValidationException("Split-declared components require merged manifest support");
            }
            return new VerifiedApk(file, manifest, signers);
        } catch (PackageValidationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PackageValidationException("Cannot verify APK", failure);
        }
    }
}
