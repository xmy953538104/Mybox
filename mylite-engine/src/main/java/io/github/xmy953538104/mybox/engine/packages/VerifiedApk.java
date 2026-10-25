package io.github.xmy953538104.mybox.engine.packages;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import com.android.apksig.util.DataSources;
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
    final SigningIdentity signing;
    final SealedApkIdentity sealed;

    private VerifiedApk(File file, ApkManifest manifest, SigningIdentity signing, SealedApkIdentity sealed) {
        this.file = file;
        this.manifest = manifest;
        this.signing = signing;
        this.signers = signing.currentSigners;
        this.sealed = sealed;
    }

    static VerifiedApk read(File file, int platformSdk) throws IOException {
        return read(file, platformSdk, false, false);
    }

    static VerifiedApk readSealed(File file, int platformSdk, boolean android) throws IOException {
        return read(file, platformSdk, true, android);
    }

    private static VerifiedApk read(File file, int platformSdk, boolean sealed, boolean android) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            SealedApkIdentity before = sealed ? SealedApkIdentity.read(file, input.getFD(), android) : null;
            ApkVerifier.Result result = new ApkVerifier.Builder(DataSources.asDataSource(input, 0, input.length()))
                    .setMinCheckedPlatformVersion(platformSdk)
                    .setMaxCheckedPlatformVersion(platformSdk).build().verify();
            if (!result.isVerified() || result.getSignerCertificates().isEmpty()) {
                throw new PackageValidationException("APK signature verification failed");
            }
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
            SigningIdentity signing = SigningIdentity.verified(result, manifest);
            SealedApkIdentity after = sealed ? SealedApkIdentity.read(file, input.getFD(), android) : null;
            if (sealed && !before.stamp.equals(after.stamp)) throw new SecurityException("APK changed during signature verification");
            return new VerifiedApk(file, manifest, signing, after);
        } catch (PackageValidationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PackageValidationException("Cannot verify APK", failure);
        }
    }
}
