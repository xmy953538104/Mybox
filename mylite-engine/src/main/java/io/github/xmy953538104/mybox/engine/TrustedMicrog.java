package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Properties;

/** Only the exact source-built service approved in the host's own asset may use privileged bridges. */
final class TrustedMicrog {
    private static final Object CACHE_LOCK = new Object();
    private static String verifiedFile;
    private static long verifiedClockOffset;
    private final String apkHash;
    private final String signerHash;

    TrustedMicrog(InputStream input) throws IOException {
        Properties pins = new Properties();
        pins.load(input);
        apkHash = requireHash(pins, "gms.apk.sha256");
        signerHash = requireHash(pins, "gms.cert.sha256");
    }

    static void require(Context context, InstalledPackage installed) throws IOException {
        if (installed == null || !installed.splitApks.isEmpty()) throw new SecurityException("Pinned microG package is absent");
        try (InputStream properties = context.getAssets().open("mybox/trusted-microg.properties")) {
            new TrustedMicrog(properties).verifySealedFile(installed.packageName, installed.certificateSha256, installed.baseApk);
        }
    }

    private void requireSigner(String packageName, List<String> signers) {
        if (!"com.google.android.gms".equals(packageName) || signers.size() != 1 || !signerHash.equals(signers.get(0))) {
            throw new SecurityException("Service package or signing identity is not trusted");
        }
    }

    /** Returns true only when this exact sealed file and these pins were already fully verified. */
    boolean verifySealedFile(String packageName, List<String> signers, File file) throws IOException {
        requireSigner(packageName, signers);
        try (FileInputStream apk = new FileInputStream(file)) {
            // shortcut: API26 has only second-resolution timestamps; keep full verification there.
            if (android.os.Build.VERSION.SDK_INT < 27) { verify(packageName, signers, apk); return false; }
            synchronized (CACHE_LOCK) {
                android.system.StructStat before = android.system.Os.fstat(apk.getFD());
                String stamp = Api27.stamp(before);
                if (!android.system.OsConstants.S_ISREG(before.st_mode) || (before.st_mode & 0222) != 0) {
                    throw new SecurityException("Trusted service APK must be a sealed regular file");
                }
                if (!stamp.equals(Api27.stamp(android.system.Os.stat(file.getPath())))) throw new SecurityException("Service APK path changed during verification");
                String key = file.getAbsolutePath() + '\n' + apkHash + '\n' + signerHash + '\n' + stamp;
                long clockOffset = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
                if (key.equals(verifiedFile) && Math.abs(clockOffset - verifiedClockOffset) < 100) return true;
                long start = android.os.SystemClock.elapsedRealtime();
                verify(packageName, signers, apk);
                if (!stamp.equals(Api27.stamp(android.system.Os.fstat(apk.getFD())))
                        || !stamp.equals(Api27.stamp(android.system.Os.stat(file.getPath())))) {
                    throw new SecurityException("Service APK changed during full verification");
                }
                // Do not cache a newly changed inode within the filesystem's coarse timestamp tick.
                long changedAt = before.st_ctime * 1000 + before.st_ctim.tv_nsec / 1_000_000;
                verifiedFile = System.currentTimeMillis() - changedAt >= 2000 ? key : null;
                verifiedClockOffset = clockOffset;
                android.util.Log.i("MyliteTrust", "Full sealed service verification completed: bytes=" + before.st_size
                        + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start));
                return false;
            }
        } catch (android.system.ErrnoException failure) { throw new IOException("Cannot identify the sealed service APK", failure); }
    }

    @android.annotation.TargetApi(27)
    private static final class Api27 {
        static String stamp(android.system.StructStat stat) {
            return stat.st_dev + ":" + stat.st_ino + ":" + stat.st_mode + ":" + stat.st_uid + ":" + stat.st_gid + ":" + stat.st_nlink
                    + ":" + stat.st_size + ":" + stat.st_mtim.tv_sec + ":" + stat.st_mtim.tv_nsec + ":" + stat.st_ctim.tv_sec + ":" + stat.st_ctim.tv_nsec;
        }
    }

    void verify(String packageName, List<String> signers, InputStream apk) throws IOException {
        requireSigner(packageName, signers);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = apk.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (!apkHash.equals(actual.toString())) throw new SecurityException("Service APK differs from the approved build");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String requireHash(Properties pins, String key) throws IOException {
        String hash = pins.getProperty(key, "");
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid service trust pin: " + key);
        return hash;
    }
}
