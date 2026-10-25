package io.github.xmy953538104.mybox.engine.packages;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.TreeMap;

/** Same sealed-file witness/cooldown policy as TrustedMicrog; no content hash on a stable hit. */
final class SealedApkIdentity {
    final String stamp;
    final boolean cacheable;
    private SealedApkIdentity(String stamp, boolean cacheable) { this.stamp = stamp; this.cacheable = cacheable; }

    static SealedApkIdentity read(File file, boolean androidRuntime) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) { return read(file, input.getFD(), androidRuntime); }
    }

    static SealedApkIdentity read(File file, FileDescriptor descriptor, boolean androidRuntime) throws IOException {
        if (androidRuntime) {
            try {
                android.system.StructStat fd = android.system.Os.fstat(descriptor);
                android.system.StructStat path = android.system.Os.lstat(file.getPath());
                if (!android.system.OsConstants.S_ISREG(fd.st_mode) || !android.system.OsConstants.S_ISREG(path.st_mode)
                        || (fd.st_mode & 0222) != 0) throw new SecurityException("Verified APK must be a sealed regular file");
                if (android.os.Build.VERSION.SDK_INT >= 27) {
                    String stamp = Api27.stamp(fd);
                    if (!stamp.equals(Api27.stamp(path))) throw new SecurityException("APK path differs from its open file");
                    long changedAt = fd.st_ctime * 1000 + fd.st_ctim.tv_nsec / 1_000_000;
                    return new SealedApkIdentity(stamp, System.currentTimeMillis() - changedAt >= 2000);
                }
                String stamp = coarse(fd);
                if (!stamp.equals(coarse(path))) throw new SecurityException("APK path differs from its open file");
                return new SealedApkIdentity(stamp, false);
            } catch (android.system.ErrnoException failure) { throw new IOException("Cannot identify sealed APK", failure); }
        }
        // JVM package fixtures use the host filesystem, never an Android compatibility signature view.
        try {
            Map<String, Object> attributes = Files.readAttributes(file.toPath(),
                    "unix:dev,ino,mode,uid,gid,nlink,size,lastModifiedTime,ctime", LinkOption.NOFOLLOW_LINKS);
            int mode = ((Number) attributes.get("mode")).intValue();
            if ((mode & 0170000) != 0100000 || (mode & 0222) != 0) throw new SecurityException("Verified APK must be a sealed regular file");
            long changedAt = ((FileTime) attributes.get("ctime")).toMillis();
            return new SealedApkIdentity(new TreeMap<>(attributes).toString(), System.currentTimeMillis() - changedAt >= 2000);
        } catch (UnsupportedOperationException noUnixAttributes) {
            BasicFileAttributes basic = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            DosFileAttributes dos = Files.readAttributes(file.toPath(), DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!basic.isRegularFile() || !dos.isReadOnly()) throw new SecurityException("Verified APK must be a sealed regular file");
            return new SealedApkIdentity(basic.fileKey() + ":" + basic.size() + ":" + basic.lastModifiedTime(), false);
        }
    }

    private static String coarse(android.system.StructStat stat) {
        return stat.st_dev + ":" + stat.st_ino + ":" + stat.st_mode + ":" + stat.st_uid + ":" + stat.st_gid
                + ":" + stat.st_nlink + ":" + stat.st_size + ":" + stat.st_mtime + ":" + stat.st_ctime;
    }

    @android.annotation.TargetApi(27)
    private static final class Api27 {
        static String stamp(android.system.StructStat stat) {
            return coarse(stat) + ":" + stat.st_mtim.tv_sec + ":" + stat.st_mtim.tv_nsec
                    + ":" + stat.st_ctim.tv_sec + ":" + stat.st_ctim.tv_nsec;
        }
    }
}
