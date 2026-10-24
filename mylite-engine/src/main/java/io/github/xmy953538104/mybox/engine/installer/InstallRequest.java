package io.github.xmy953538104.mybox.engine.installer;

/** Platform-independent subset: complete APK sets, virtual user 0, ordinary non-staged installs. */
public final class InstallRequest {
    public final String expectedPackage;
    public final long sizeBytes;
    public final String label;
    public InstallRequest(String expectedPackage, long sizeBytes, String label) {
        if (expectedPackage != null && !expectedPackage.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("Invalid installation target package");
        }
        if (sizeBytes < -1 || sizeBytes > SessionRepository.MAX_SESSION_BYTES) throw new IllegalArgumentException("Installation size exceeds limit");
        if (label != null && label.length() > 512) throw new IllegalArgumentException("Installation label exceeds limit");
        this.expectedPackage = expectedPackage; this.sizeBytes = sizeBytes; this.label = label;
    }
}
