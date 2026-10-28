package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.FileObserver;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;

public final class NativePathRuntimeTest extends InstrumentationTestCase {
    public void testActualGuestFileObserverAndPathXattrsMatchPhysicalBacking() throws Exception {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            File apk = new File(host.getCacheDir(), "native-path-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                    Files.copy(input, apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                installed = engine.install(Collections.singletonList(apk));
            } finally { Files.deleteIfExists(apk.toPath()); }
        }
        String nonce = UUID.randomUUID().toString();
        File directory = new File(installed.dataDir, "files/native-path-" + nonce);
        File attributes = new File(directory, "attributes.bin"), created = new File(directory, "created.bin");
        File report = new File(installed.dataDir, "files/native-path-" + nonce + ".json");
        assertFalse("Unique native path result already exists", report.exists());
        assertTrue("Cannot create the owned physical probe directory", directory.mkdirs());
        boolean completed = false;
        try {
            Files.write(attributes.toPath(), new byte[]{9, 0, 2});
            int physicalErrno = 0;
            try {
                Os.setxattr(attributes.getAbsolutePath(), "user.mylite.nativepath", new byte[]{0, 1, (byte) 0xff}, 0);
            } catch (ErrnoException unsupported) {
                physicalErrno = unsupported.errno;
                assertTrue("Physical xattr failed for a reason other than unsupported filesystem",
                        physicalErrno == OsConstants.ENOTSUP || physicalErrno == OsConstants.ENOSYS);
            }
            if (physicalErrno == 0) Os.removexattr(attributes.getAbsolutePath(), "user.mylite.nativepath");
            FixtureRun.prepare(installed, "native-path:" + nonce); engine.launch(name);
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (!report.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            completed = report.isFile();
            if (!completed) {
                File mode = new File(installed.dataDir, "files/fixture-mode");
                System.out.println("MYLITE_NATIVE_PATH_TIMEOUT nonce=" + nonce + " modePending=" + mode.isFile()
                        + " created=" + created.isFile() + " reportPending=" + new File(report.getPath() + ".tmp").isFile());
                try {
                    android.os.Bundle scope = EngineProtocol.response(); scope.putString("operation", "diagnose_activity_wait");
                    scope.putString("package", name); scope.putString("package_generation", installed.baseApk.getAbsolutePath());
                    System.out.println("MYLITE_NATIVE_PATH_STACK_CAPTURE " + EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, scope));
                    Thread.sleep(250);
                } catch (Exception captureFailure) {
                    if (captureFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                    android.util.Log.w("MylitePathProbe", "Timeout diagnostics unavailable", captureFailure);
                }
            }
            assertTrue("Actual native path guest probe did not complete", completed);
            JSONObject result = new JSONObject(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
            assertEquals(nonce, result.getString("nonce")); assertFalse(result.toString(), result.has("failure"));
            assertTrue("Native path probe ran in the instrumentation process", result.getInt("pid") != android.os.Process.myPid());
            assertTrue(result.toString(), (result.getInt("watchEventMask") & FileObserver.CREATE) != 0);
            assertTrue(result.getBoolean("physicalFileExists")); assertTrue(result.getBoolean("observerStopped"));
            assertTrue("Actual physical creation is missing", created.isFile());
            assertEquals("Physical file belongs to a different launch", nonce,
                    new String(Files.readAllBytes(created.toPath()), StandardCharsets.UTF_8));
            assertTrue(result.getBoolean("xattrPhysicalCompared"));
            assertEquals("Guest xattr support differs from the direct physical host baseline",
                    physicalErrno == 0, result.getBoolean("xattrSupported"));
            assertEquals(physicalErrno, result.getInt("xattrPhysicalErrno"));
            if (physicalErrno == 0) {
                for (String check : new String[]{"xattrBinaryReadback", "xattrListed", "xattrReplaced"}) assertTrue(result.toString(), result.getBoolean(check));
                assertEquals(OsConstants.EEXIST, result.getInt("xattrCreateErrno"));
                assertEquals(OsConstants.E2BIG, result.getInt("xattrSizeErrno"));
                assertEquals(OsConstants.ENODATA, result.getInt("xattrReplaceMissingErrno"));
                assertEquals(OsConstants.ENODATA, result.getInt("xattrRemovedErrno"));
                try { Os.getxattr(attributes.getAbsolutePath(), "user.mylite.nativepath"); fail("Guest removal left the physical xattr installed"); }
                catch (ErrnoException removed) { assertEquals(OsConstants.ENODATA, removed.errno); }
            } else assertEquals(physicalErrno, result.getInt("xattrLogicalErrno"));
            System.out.println("MYLITE_NATIVE_PATH " + result);
        } finally {
            if (completed) {
                Files.deleteIfExists(new File(report.getParentFile(), report.getName() + ".tmp").toPath());
                Files.deleteIfExists(report.toPath()); Files.deleteIfExists(created.toPath());
                Files.deleteIfExists(attributes.toPath()); Files.deleteIfExists(directory.toPath());
            } else System.out.println("MYLITE_NATIVE_PATH_RETAINED " + directory);
        }
    }
}
