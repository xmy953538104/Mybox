package io.github.xmy953538104.mybox.engine.packages;

import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Actual APK verification evidence; neither package records nor compatibility signatures construct it. */
public final class SigningIdentity {
    public final String packageName;
    public final long versionCode;
    public final List<String> currentSigners;
    private final List<String> lineage;
    private final List<Integer> capabilities;

    private SigningIdentity(String name, long version, List<String> current, List<String> lineage, List<Integer> capabilities) {
        packageName = name; versionCode = version;
        currentSigners = Collections.unmodifiableList(current);
        this.lineage = Collections.unmodifiableList(lineage);
        this.capabilities = Collections.unmodifiableList(capabilities);
    }

    static SigningIdentity verified(ApkVerifier.Result result, ApkManifest manifest) throws Exception {
        if (!result.isVerified() || result.getSignerCertificates().isEmpty()) throw new PackageValidationException("Unverified signing identity");
        List<String> current = new ArrayList<>();
        for (X509Certificate certificate : result.getSignerCertificates()) current.add(hash(certificate));
        Collections.sort(current);
        List<String> history = new ArrayList<>(); List<Integer> capabilities = new ArrayList<>();
        SigningCertificateLineage proof = result.getSigningCertificateLineage();
        if (proof != null) {
            if (current.size() != 1 || !(result.isVerifiedUsingV3Scheme() || result.isVerifiedUsingV31Scheme()))
                throw new PackageValidationException("Rotation proof does not belong to the effective signing scheme");
            proof = proof.getSubLineage(result.getSignerCertificates().get(0));
            for (X509Certificate certificate : proof.getCertificatesInLineage()) {
                history.add(hash(certificate));
                SigningCertificateLineage.SignerCapabilities c = proof.getSignerCapabilities(certificate);
                capabilities.add((c.hasInstalledData() ? 1 : 0) | (c.hasSharedUid() ? 2 : 0)
                        | (c.hasPermission() ? 4 : 0) | (c.hasRollback() ? 8 : 0) | (c.hasAuth() ? 16 : 0));
            }
            if (history.isEmpty() || !current.get(0).equals(history.get(history.size() - 1)))
                throw new PackageValidationException("Rotation proof endpoint differs from the effective signer");
        }
        return new SigningIdentity(manifest.packageName, manifest.versionCode, current, history, capabilities);
    }

    public boolean isSinglePublisher(String packageName, String anchor) {
        int ancestor = lineage.indexOf(anchor);
        return this.packageName.equals(packageName) && currentSigners.size() == 1
                && (anchor.equals(currentSigners.get(0)) || ancestor >= 0 && ancestor < lineage.size() - 1);
    }

    boolean canUpdate(SigningIdentity previous) {
        if (!packageName.equals(previous.packageName)) return false;
        // Dropping installed history would let a later proof reverse an already accepted rotation.
        if (previous.lineage.size() > 1 && lineage.size() < previous.lineage.size()) return false;
        if (currentSigners.equals(previous.currentSigners)) return compatibleProof(previous, false);
        if (currentSigners.size() != 1 || previous.currentSigners.size() != 1) return false;
        int old = lineage.indexOf(previous.currentSigners.get(0));
        return old >= 0 && old < lineage.size() - 1 && (capabilities.get(old) & 1) != 0
                && compatibleProof(previous, false);
    }

    boolean sameSplitSigner(SigningIdentity other) {
        return currentSigners.equals(other.currentSigners) && compatibleProof(other, true);
    }

    private boolean compatibleProof(SigningIdentity other, boolean compareCapabilities) {
        if (lineage.isEmpty() || other.lineage.isEmpty()) return true;
        int common = Math.min(lineage.size(), other.lineage.size());
        for (int i = 0; i < common; i++) {
            if (!lineage.get(i).equals(other.lineage.get(i))
                    || compareCapabilities && !capabilities.get(i).equals(other.capabilities.get(i))) return false;
        }
        return true;
    }

    private static String hash(X509Certificate certificate) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()))
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }
}
