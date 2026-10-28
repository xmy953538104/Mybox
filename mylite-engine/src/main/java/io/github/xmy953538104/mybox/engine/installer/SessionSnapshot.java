package io.github.xmy953538104.mybox.engine.installer;

/** Contains public session metadata only, with no staging paths or owner credentials. */
public final class SessionSnapshot {
    public final int id;
    public final String installerPackage, packageName, label, state;
    public final long sizeBytes, createdAt;
    public final float progress;
    public final int status;
    public final byte[] icon;
    SessionSnapshot(int id, String installerPackage, String packageName, String label, String state,
            long sizeBytes, long createdAt, float progress, int status, byte[] icon) {
        this.id = id; this.installerPackage = installerPackage; this.packageName = packageName;
        this.label = label; this.state = state; this.sizeBytes = sizeBytes; this.createdAt = createdAt;
        this.progress = progress; this.status = status;
        this.icon = icon == null ? null : icon.clone();
    }
    public boolean sealed() { return !"OPEN".equals(state); }
    public boolean committed() { return "COMMITTING".equals(state) || "SUCCEEDED".equals(state) || "FAILED".equals(state); }
    public boolean terminal() { return "SUCCEEDED".equals(state) || "FAILED".equals(state) || "ABANDONED".equals(state); }
}
