package top.niunaijun.blackbox.core.system.pm.installer;

import java.io.File;
import java.io.IOException;

/** A package's code and settings move together; user data lives outside this directory. */
public final class AtomicPackageDirectory {
    private final File current;
    private final File backup;
    public final File stage;

    public AtomicPackageDirectory(File root, String packageName) throws IOException {
        if (!packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IOException("Invalid package name");
        }
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create app root");
        current = new File(root, packageName);
        backup = new File(root, ".backup-" + packageName);
        stage = new File(root, ".stage-" + packageName);
        recover(root, packageName);
        delete(stage);
        if (!stage.mkdir()) throw new IOException("Cannot create install stage");
    }

    public void commit() throws IOException {
        if (current.exists() && !current.renameTo(backup)) throw new IOException("Cannot back up package");
        if (!stage.renameTo(current)) {
            if (backup.exists() && !backup.renameTo(current)) {
                throw new IOException("Install failed; backup retained for recovery");
            }
            throw new IOException("Cannot publish package");
        }
        // A leftover backup after a crash is removed on the next scan.
        // Cleanup belongs to recovery: a cleanup error must not report a committed install as failed.
    }

    public void abort() throws IOException {
        delete(stage);
    }

    public static void recover(File root, String packageName) throws IOException {
        File current = new File(root, packageName);
        File backup = new File(root, ".backup-" + packageName);
        if (backup.exists()) {
            if (current.exists()) delete(backup);
            else if (!backup.renameTo(current)) throw new IOException("Cannot recover package backup");
        }
    }

    public static void delete(File path) throws IOException {
        if (!path.exists()) return;
        // Never follow a guest-created link while cleaning a stage or backup.
        if (path.isDirectory() && path.getCanonicalFile().equals(path.getAbsoluteFile())) {
            File[] files = path.listFiles();
            if (files == null) throw new IOException("Cannot list install directory");
            for (File child : files) delete(child);
        }
        if (!path.delete()) throw new IOException("Cannot remove install file");
    }
}
