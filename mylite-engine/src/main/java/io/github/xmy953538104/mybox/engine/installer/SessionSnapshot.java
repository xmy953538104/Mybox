package io.github.xmy953538104.mybox.engine.installer;

/** Contains public session metadata only, with no staging paths or owner credentials. */
public final class SessionSnapshot {
    public final int id;
    public final String installerPackage, packageName, label, state;
    public final long sizeBytes, createdAt;
    public final float progress;
    public final int status;
    SessionSnapshot(int id, String installerPackage, String packageName, String label, String state,
            long sizeBytes, long createdAt, float progress, int status) {
        this.id = id; this.installerPackage = installerPackage; this.packageName = packageName;
        this.label = label; this.state = state; this.sizeBytes = sizeBytes; this.createdAt = createdAt;
        this.progress = progress; this.status = status;
    }
    public boolean sealed() { return !"OPEN".equals(state); }
    public boolean committed() { return "COMMITTING".equals(state) || "SUCCEEDED".equals(state) || "FAILED".equals(state); }
}
