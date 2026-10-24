/* SPDX-License-Identifier: Apache-2.0 */
package io.github.xmy953538104.mybox.engine;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/** Source-built guest-process I/O backend. It never changes the Linux or Binder UID. */
public final class NativeBridge {
    public static final class Result {
        public final boolean supported;
        public final boolean ioEnabled;
        public final int errno;
        public final String detail;

        private Result(boolean supported, boolean ioEnabled, int errno, String detail) {
            this.supported = supported;
            this.ioEnabled = ioEnabled;
            this.errno = errno;
            this.detail = detail;
        }
    }

    private static final boolean LOADED;
    private static String boundPackage;
    private static Result result;
    static {
        boolean loaded;
        try { System.loadLibrary("mybox-vlite"); loaded = true; }
        catch (LinkageError error) { loaded = false; }
        LOADED = loaded;
    }

    private NativeBridge() {}

    /** Must run before guest Application construction, once per dedicated engine process. */
    public static synchronized Result initializeGuest(Context host, ApplicationInfo guest) {
        if (host == null || guest == null || guest.packageName == null) {
            return failure(OsConstants.EINVAL, "Guest identity is incomplete");
        }
        if (boundPackage != null) {
            return boundPackage.equals(guest.packageName) ? result
                    : failure(OsConstants.EBUSY, "This process is already bound to another guest");
        }
        String hostPackage = host.getPackageName();
        try {
            String physical = physicalProcessName();
            if (hostPackage.equals(guest.packageName)
                    || !physical.matches(java.util.regex.Pattern.quote(hostPackage) + ":engine_p[0-9]+")) {
                return failure(OsConstants.EPERM, "Native I/O requires a dedicated guest process");
            }
            if (!guest.packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
                return failure(OsConstants.EINVAL, "Invalid guest package name");
            }
            if (!LOADED) return failure(OsConstants.ENOSYS, "Native backend is not available for this ABI");
            ApplicationInfo owner = host.getApplicationInfo();
            List<String> sources = new ArrayList<>();
            List<String> targets = new ArrayList<>();
            String data = ownedDirectory(guest.dataDir, owner);
            add(sources, targets, "/data/data/" + guest.packageName, data);
            add(sources, targets, "/data/user/0/" + guest.packageName, data);
            if (guest.deviceProtectedDataDir != null) {
                add(sources, targets, "/data/user_de/0/" + guest.packageName,
                        ownedDirectory(guest.deviceProtectedDataDir, owner));
            }
            if (guest.nativeLibraryDir != null) {
                String libs = ownedDirectory(guest.nativeLibraryDir, owner);
                add(sources, targets, "/data/data/" + guest.packageName + "/lib", libs);
                add(sources, targets, "/data/user/0/" + guest.packageName + "/lib", libs);
            }
            int error = nativeInitialize(sources.toArray(new String[0]), targets.toArray(new String[0]));
            boundPackage = guest.packageName;
            result = new Result(error != OsConstants.ENOSYS, error == 0, error,
                    error == 0 ? "Guest path syscalls use seccomp/SIGSYS; UID and Binder identity remain real"
                            : "Native I/O initialization failed (errno=" + error + ")");
            return result;
        } catch (Exception error) {
            return failure(OsConstants.EINVAL, "Native path configuration failed: "
                    + error.getClass().getSimpleName());
        }
    }

    public static String queryRedirectedPath(String path) {
        if (!LOADED) throw new IllegalStateException("Native backend is unavailable");
        if (path == null) throw new IllegalArgumentException("path == null");
        return nativeQueryPath(path);
    }

    private static Result failure(int error, String detail) {
        return new Result(LOADED, false, error, detail);
    }

    private static void add(List<String> sources, List<String> targets, String source, String target) {
        sources.add(source);
        targets.add(target);
    }

    private static String ownedDirectory(String path, ApplicationInfo owner) throws Exception {
        if (path == null) throw new IllegalArgumentException("Missing guest directory");
        String canonical = new File(path).getCanonicalPath();
        if (under(canonical, owner.dataDir) || under(canonical, owner.deviceProtectedDataDir)) return canonical;
        throw new IllegalArgumentException("Guest directory is outside host storage");
    }

    private static boolean under(String path, String root) throws Exception {
        if (root == null) return false;
        String canonicalRoot = new File(root).getCanonicalPath();
        return path.startsWith(canonicalRoot + "/");
    }

    private static String physicalProcessName() throws Exception {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        try (FileInputStream input = new FileInputStream("/proc/self/cmdline")) {
            byte[] bytes = new byte[256];
            int count = input.read(bytes);
            int end = 0;
            while (end < count && bytes[end] != 0) end++;
            return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static native int nativeInitialize(String[] sources, String[] destinations);
    private static native String nativeQueryPath(String path);
}
