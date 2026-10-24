/* SPDX-License-Identifier: Apache-2.0 */
package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Process;
import android.system.OsConstants;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/**
 * Optional guest Binder UID view. Engine authorization must use the raw getters.
 * Installing the entry does not enable mapping: an authenticated, explicit scope is required.
 */
public final class NativeBinderIdentity {
    public static final class Result {
        public final boolean installed;
        public final int errno;
        public final int entryOffset;
        public final String abi;
        public final String detail;

        private Result(boolean installed, int errno, int entryOffset, String abi, String detail) {
            this.installed = installed; this.errno = errno; this.entryOffset = entryOffset;
            this.abi = abi; this.detail = detail;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private long token;

        private Scope(long token) { this.token = token; }

        @Override public void close() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Binder identity scope belongs to another thread");
            }
            if (token != 0) {
                long closing = token;
                token = 0;
                nativeLeave(closing);
            }
        }
    }

    private static final boolean LOADED;
    // ART native-method flags; Executable copies these from ArtMethod (see docs/vlite-binder-identity.md).
    private static final int FAST_NATIVE = 0x00080000;
    private static final int CRITICAL_NATIVE = 0x00100000;
    private static volatile Result result;
    static {
        boolean loaded;
        try { System.loadLibrary("mybox-vlite"); loaded = true; }
        catch (LinkageError error) { loaded = false; }
        LOADED = loaded;
    }

    private NativeBinderIdentity() { }

    /** Call once before guest code starts. Does not install an automatic Binder observer. */
    public static synchronized Result initializeGuest(Context host) {
        return initialize(host, false);
    }

    // Used only by the debug-manifest synthetic service; absent in release process declarations.
    static synchronized Result initializeTestProcess(Context host) {
        return initialize(host, true);
    }

    private static Result initialize(Context host, boolean testProcess) {
        if (result != null) return result;
        if (!LOADED) return failed(OsConstants.ENOSYS, "Native UID backend is unavailable");
        if (android.os.Build.VERSION.SDK_INT < 26 || android.os.Build.VERSION.SDK_INT > 35) {
            return failed(OsConstants.ENOTSUP, "This Android ART version has not been reviewed");
        }
        try {
            String physical = processName();
            String owner = host.getPackageName();
            boolean allowed = testProcess
                    ? (host.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        && physical.equals(owner + ":identity_test")
                    : physical.matches(java.util.regex.Pattern.quote(owner) + ":engine_p[0-9]+");
            if (!allowed) return failed(OsConstants.EPERM, "Binder identity requires a dedicated process");
            if (android.os.Build.VERSION.SDK_INT >= 28 && !HiddenApiBypass.addHiddenApiExemptions(
                    "Ljava/lang/reflect/Executable;")) {
                return failed(OsConstants.ENOTSUP, "ART method metadata is unavailable");
            }

            Method uid = Binder.class.getDeclaredMethod("getCallingUid");
            Method pid = Binder.class.getDeclaredMethod("getCallingPid");
            Method one = NativeBinderIdentity.class.getDeclaredMethod("markerOne");
            Method two = NativeBinderIdentity.class.getDeclaredMethod("markerTwo");
            requireGetter(uid); requireGetter(pid);
            // These annotations have CLASS retention, so getDeclaredAnnotations cannot detect their ABI.
            int uidFlags = nativeFlags(uid), pidFlags = nativeFlags(pid);
            boolean criticalUid = (uidFlags & CRITICAL_NATIVE) != 0;
            boolean criticalPid = (pidFlags & CRITICAL_NATIVE) != 0;
            if (criticalUid && !criticalPid) return failed(OsConstants.ENOTSUP, "Mixed Binder native ABIs are unsupported");
            if ((nativeFlags(one) | nativeFlags(two)) != 0) {
                return failed(OsConstants.ENOTSUP, "Calibration markers have an unexpected native ABI");
            }
            String abi = criticalUid ? "CriticalNative ()I" : (uidFlags & FAST_NATIVE) != 0
                    ? "FastNative (JNIEnv*, jclass)" : "JNI (JNIEnv*, jclass)";

            // Resolve the boot-class native methods before reading their saved entries.
            int originalUid = Binder.getCallingUid();
            int originalPid = Binder.getCallingPid();
            long[] methods = {artMethod(one), artMethod(two), artMethod(uid), artMethod(pid)};
            int error = nativeInstall(methods, criticalUid, criticalPid, Process.myUid());
            if (error != 0) return failed(error, "Binder JNI calibration/registration failed");
            long before = nativeHookCalls();
            int visibleUid = Binder.getCallingUid();
            if (nativeHookCalls() <= before || visibleUid != originalUid
                    || nativeRawUid() != originalUid || nativeRawPid() != originalPid) {
                int rollback = nativeRestore();
                return failed(rollback == 0 ? OsConstants.ENOSYS : rollback,
                        "Binder entry verification failed; restore errno=" + rollback);
            }
            result = new Result(true, 0, nativeEntryOffset(), abi,
                    "Calibrated native entry; raw identity is preserved outside verified scopes");
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            int rollback;
            try { rollback = nativeRestore(); }
            catch (RuntimeException | LinkageError ignored) { rollback = OsConstants.ENOSYS; }
            return failed(OsConstants.ENOTSUP, "Binder capability unavailable: "
                    + error.getClass().getSimpleName() + "; restore errno=" + rollback);
        }
    }

    /** Kernel Binder identity, unaffected by this class's virtual scopes. */
    public static int rawCallingUid() {
        Result current = result;
        return current != null && current.installed ? nativeRawUid() : Binder.getCallingUid();
    }

    public static int rawCallingPid() {
        Result current = result;
        return current != null && current.installed ? nativeRawPid() : Binder.getCallingPid();
    }

    /**
     * The engine must verify this physical caller and current package generation first.
     * No WorkSource value, package string from a Parcel, or virtual UID is authentication.
     * Only application UIDs are accepted; this API cannot synthesize root/system identity.
     */
    public static Scope enterVerifiedCall(int physicalUid, int physicalPid, int virtualUid,
                                          long packageGeneration) {
        requireInstalled();
        return new Scope(nativeEnter(physicalUid, physicalPid, virtualUid, packageGeneration));
    }

    /** Suppresses a surrounding virtual view while engine/framework code uses real identity. */
    public static Scope enterRawScope() {
        requireInstalled();
        return new Scope(nativeEnterRaw());
    }

    private static void requireInstalled() {
        Result current = result;
        if (current == null || !current.installed) throw new IllegalStateException("Binder UID backend is not installed");
    }

    private static Result failed(int error, String detail) {
        result = new Result(false, error, -1, "unavailable", detail);
        return result;
    }

    private static long artMethod(Method method) throws ReflectiveOperationException {
        return RuntimeReflection.field(method.getClass(), "artMethod").getLong(method);
    }

    private static void requireGetter(Method method) {
        int modifiers = method.getModifiers();
        if (!Modifier.isNative(modifiers) || !Modifier.isStatic(modifiers)
                || method.getReturnType() != int.class || method.getParameterTypes().length != 0) {
            throw new IllegalStateException("Unexpected Binder native signature");
        }
    }

    private static int nativeFlags(Method method) throws ReflectiveOperationException {
        int flags = RuntimeReflection.field(method.getClass(), "accessFlags").getInt(method);
        // ART moved the CriticalNative flag in Android 12; it is absent in 8/8.1.
        int sdk = android.os.Build.VERSION.SDK_INT;
        int criticalMask = sdk >= 31 ? 0x00100000 : sdk >= 28 ? 0x00200000 : 0;
        int abi = (flags & FAST_NATIVE) | ((flags & criticalMask) != 0 ? CRITICAL_NATIVE : 0);
        if ((flags & (Modifier.STATIC | Modifier.NATIVE)) != (Modifier.STATIC | Modifier.NATIVE)
                || abi == (FAST_NATIVE | CRITICAL_NATIVE)) {
            throw new IllegalStateException("Unexpected ART native method flags");
        }
        return abi;
    }

    private static String processName() {
        if (android.os.Build.VERSION.SDK_INT >= 28) return android.app.Application.getProcessName();
        try (java.io.FileInputStream input = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] bytes = new byte[256];
            int count = input.read(bytes), end = 0;
            while (end < count && bytes[end] != 0) end++;
            return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { return ""; }
    }

    private static native int markerOne();
    private static native int markerTwo();
    private static native int nativeInstall(long[] methods, boolean criticalUid, boolean criticalPid, int realUid);
    private static native int nativeRestore();
    private static native int nativeEntryOffset();
    private static native long nativeHookCalls();
    private static native int nativeRawUid();
    private static native int nativeRawPid();
    private static native long nativeEnter(int uid, int pid, int virtualUid, long generation);
    private static native long nativeEnterRaw();
    private static native void nativeLeave(long token);
}
