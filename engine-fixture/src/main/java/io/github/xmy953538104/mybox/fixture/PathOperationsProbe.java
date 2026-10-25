package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.os.FileObserver;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Ordinary ART guest APIs; reports actual kernel events and xattr outcomes. */
public final class PathOperationsProbe {
    private static final String ATTRIBUTE = "user.mylite.nativepath";
    private static final int XATTR_CREATE = 1, XATTR_REPLACE = 2; // Linux xattr.h; not public OsConstants fields.

    public static void run(Activity activity, String nonce) {
        if (!UUID.fromString(nonce).toString().equals(nonce)) throw new IllegalArgumentException("Invalid native path nonce");
        new Thread(() -> execute(activity, nonce), "MylitePathProbe").start();
    }

    private static void execute(Activity activity, String nonce) {
        JSONObject result = new JSONObject();
        FileObserver observer = null;
        try {
            result.put("nonce", nonce); result.put("pid", android.os.Process.myPid());
            File physical = new File(activity.getFilesDir(), "native-path-" + nonce);
            String logical = "/data/data/" + activity.getPackageName() + "/files/native-path-" + nonce;
            require(physical.isDirectory(), "Host did not prepare the owned probe directory");
            CountDownLatch created = new CountDownLatch(1);
            AtomicInteger eventMask = new AtomicInteger();
            observer = new FileObserver(logical, FileObserver.CREATE) {
                @Override public void onEvent(int event, String path) {
                    if ((event & CREATE) != 0 && "created.bin".equals(path)) {
                        eventMask.set(event); created.countDown();
                    }
                }
            };
            observer.startWatching();
            try (FileOutputStream output = new FileOutputStream(logical + "/created.bin")) {
                output.write(nonce.getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            require(created.await(5, TimeUnit.SECONDS), "FileObserver did not receive actual CREATE");
            result.put("watchEventMask", eventMask.get());
            require(new File(physical, "created.bin").isFile(), "Logical creation has no physical backing file");
            result.put("physicalFileExists", true);
            attributes(logical + "/attributes.bin", new File(physical, "attributes.bin").getAbsolutePath(), result);
        } catch (Exception | AssertionError failure) {
            putFailure(result, failure);
        } finally {
            try {
                if (observer != null) observer.stopWatching();
                result.put("observerStopped", true);
            } catch (Exception failure) { putFailure(result, failure); }
            try {
                File target = new File(activity.getFilesDir(), "native-path-" + nonce + ".json");
                File pending = new File(activity.getFilesDir(), target.getName() + ".tmp");
                Files.write(pending.toPath(), result.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception failure) { throw new IllegalStateException("Cannot publish native path result", failure); }
            finally { activity.runOnUiThread(activity::finish); }
        }
    }

    private static void attributes(String logical, String physical, JSONObject result) throws Exception {
        byte[] first = {0, 1, (byte) 0xff, 0, 42}, second = {(byte) 0x80, 0, 7};
        int actual = errno(() -> Os.setxattr(physical, ATTRIBUTE, first, XATTR_CREATE));
        result.put("xattrPhysicalErrno", actual);
        if (actual != 0) {
            int redirected = errno(() -> Os.setxattr(logical, ATTRIBUTE, first, XATTR_CREATE));
            result.put("xattrLogicalErrno", redirected);
            require(actual == redirected, "Logical xattr errno differs from the physical source");
            require(actual == OsConstants.ENOTSUP || actual == OsConstants.ENOSYS,
                    "Physical xattr denied or failed for a reason other than unsupported filesystem");
            result.put("xattrSupported", false); result.put("xattrPhysicalCompared", true);
            return;
        }
        try {
            result.put("xattrSupported", true);
            require(Arrays.equals(first, Os.getxattr(logical, ATTRIBUTE))
                    && Arrays.equals(first, Os.getxattr(physical, ATTRIBUTE)), "Binary xattr readback differs");
            require(Arrays.asList(Os.listxattr(logical)).contains(ATTRIBUTE)
                    && Arrays.asList(Os.listxattr(physical)).contains(ATTRIBUTE), "xattr list omitted the owned attribute");
            result.put("xattrBinaryReadback", true); result.put("xattrListed", true);
            int duplicate = compareErrno(
                    () -> Os.setxattr(logical, ATTRIBUTE, first, XATTR_CREATE),
                    () -> Os.setxattr(physical, ATTRIBUTE, first, XATTR_CREATE), OsConstants.EEXIST);
            result.put("xattrCreateErrno", duplicate);
            byte[] oversized = new byte[65537]; // Linux XATTR_SIZE_MAX + 1.
            result.put("xattrSizeErrno", compareErrno(
                    () -> Os.setxattr(logical, ATTRIBUTE, oversized, 0),
                    () -> Os.setxattr(physical, ATTRIBUTE, oversized, 0), OsConstants.E2BIG));
            Os.setxattr(logical, ATTRIBUTE, second, XATTR_REPLACE);
            require(Arrays.equals(second, Os.getxattr(physical, ATTRIBUTE)), "Logical xattr replacement missed the physical file");
            result.put("xattrReplaced", true);
            result.put("xattrReplaceMissingErrno", compareErrno(
                    () -> Os.setxattr(logical, ATTRIBUTE + ".missing", first, XATTR_REPLACE),
                    () -> Os.setxattr(physical, ATTRIBUTE + ".missing", first, XATTR_REPLACE), OsConstants.ENODATA));
            Os.removexattr(logical, ATTRIBUTE);
            result.put("xattrRemovedErrno", compareErrno(
                    () -> Os.getxattr(logical, ATTRIBUTE), () -> Os.getxattr(physical, ATTRIBUTE), OsConstants.ENODATA));
            result.put("xattrPhysicalCompared", true);
        } finally {
            int cleanup = errno(() -> Os.removexattr(physical, ATTRIBUTE));
            require(cleanup == 0 || cleanup == OsConstants.ENODATA, "Cannot clean the owned xattr");
        }
    }

    private interface Operation { void run() throws ErrnoException; }
    private static int errno(Operation operation) throws ErrnoException {
        try { operation.run(); return 0; }
        catch (ErrnoException failure) { return failure.errno; }
    }
    private static int compareErrno(Operation logical, Operation physical, int expected) throws ErrnoException {
        int redirected = errno(logical), direct = errno(physical);
        require(direct == expected && redirected == direct, "Logical/physical xattr error mismatch; expected="
                + expected + " logical=" + redirected + " physical=" + direct);
        return direct;
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void putFailure(JSONObject result, Throwable failure) {
        try { result.put("failure", failure.getClass().getName() + ": " + failure.getMessage()); }
        catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
    }
    private PathOperationsProbe() { }
}
