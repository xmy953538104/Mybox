package io.github.xmy953538104.mybox.engine;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.AtomicFile;
import android.util.Base64;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual killed guest and real system exit data, including server cold recovery and failed journal writes. */
@android.annotation.TargetApi(30)
public final class ExitHistoryRuntimeTest extends InstrumentationTestCase {
    public void testActualOsHistoryIsGuestScopedAndSurvivesServerReplacement() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        InstalledPackage own = installIfAbsent("io.github.xmy953538104.mybox.fixture", "engine-fixture.apk");
        InstalledPackage peer = installIfAbsent("io.github.xmy953538104.mybox.broadcasttarget.same", "broadcast-permission-same.apk");
        String seedNonce = UUID.randomUUID().toString();
        JSONObject seed = launch(own, "exit-history-seed:", seedNonce, null);
        int killedPid = seed.getInt("pid"); assertTrue(seed.getBoolean("seededActualSummary"));
        Bundle ownProcess = acquire(own.packageName, own.packageName);
        assertEquals(killedPid, ownProcess.getInt("pid")); kill(ownProcess);
        byte[] summary = ("\u0000Mybox-exit:" + seedNonce + "\u0000").getBytes(StandardCharsets.UTF_8);
        ApplicationExitInfo physical = awaitRecord(killedPid, summary);
        assertTrue("OS returned an unknown death reason", physical.getReason() != ApplicationExitInfo.REASON_UNKNOWN);
        Bundle peerProcess = acquire(peer.packageName, peer.packageName); int peerPid = peerProcess.getInt("pid");
        kill(peerProcess); ApplicationExitInfo peerExit = awaitRecord(peerPid, null);
        assertTrue("Peer record must be newer to exercise filtering before max1", peerExit.getTimestamp() >= physical.getTimestamp());
        int oldServer = serverPid();
        JSONObject input = new JSONObject().put("targetPid", killedPid).put("seedNonce", seedNonce)
                .put("peerPid", peerPid).put("hostPackage", host.getPackageName()).put("peerPackage", peer.packageName)
                .put("forbiddenPids", new JSONArray().put(peerPid).put(oldServer).put(Process.myPid()));
        JSONObject first = launch(own, "exit-history:", UUID.randomUUID().toString(), input);
        verify(first, physical);
        IBinder original = engine.server();
        Process.killProcess(oldServer); awaitDeath(original);
        assertTrue("Host could not create the replacement server", engine.server().isBinderAlive());
        assertFalse("Cold recovery reused the dead server Binder", original.equals(engine.server()));
        JSONObject cold = launch(own, "exit-history:", UUID.randomUUID().toString(), input);
        verify(cold, awaitRecord(killedPid, summary));
        Bundle service = EngineProtocol.response(); service.putString("service", "exit_history");
        try { EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.GET_SERVICE, service); fail("Unregistered host obtained a guest history endpoint"); }
        catch (EngineException denied) { assertTrue(denied.toString(), denied.getMessage().contains("identity")); }
        System.out.println("MYLITE_EXIT_HISTORY actualPid=" + killedPid + " reason=" + physical.getReason()
                + " status=" + physical.getStatus() + " coldRecovery=true actualTraceAvailable=" + cold.getBoolean("actualTraceAvailable"));
    }

    public void testActualSealPersistenceFailureRetiresPreparedVm() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        InstalledPackage fixture = installIfAbsent("io.github.xmy953538104.mybox.fixture", "engine-fixture.apk");
        acquire(fixture.packageName, fixture.packageName); // Ensure a real committed journal already exists.
        File journal = new File(host.getNoBackupFilesDir(), "mylite-exit-history.bin"); assertTrue(journal.isFile());
        File pending = (File) RuntimeReflection.get(new AtomicFile(journal), "mNewName");
        assertEquals("Fault injection left the private journal directory", host.getNoBackupFilesDir().getCanonicalFile(), pending.getCanonicalFile().getParentFile());
        assertFalse("Another atomic write is active", pending.exists());
        Set<String> before = new HashSet<>(); for (ApplicationExitInfo row : actual(0)) before.add(ServerExitHistory.key(row));
        assertTrue(pending.mkdir()); File blocker = new File(pending, "owned-" + UUID.randomUUID()); Files.write(blocker.toPath(), new byte[]{1});
        IBinder server = engine.server(); int serverPid = serverPid();
        try {
            try { acquire(fixture.packageName, fixture.packageName + ":exit_storage_failure"); fail("An uncommitted process journal was accepted"); }
            catch (EngineException expected) { assertTrue(expected.toString(), expected.getMessage().contains("Failed to create") || expected.getMessage().contains("directory") || expected.getMessage().contains("Is a directory")); }
            long deadline = SystemClock.elapsedRealtime() + 10000; ApplicationExitInfo failed = null;
            while (failed == null && SystemClock.elapsedRealtime() < deadline) {
                for (ApplicationExitInfo row : actual(0)) if (!before.contains(ServerExitHistory.key(row))
                        && row.getProcessName() != null && row.getProcessName().startsWith(host.getPackageName() + ":engine_p")) {
                    assertNull("More than one guest died during the single seal failure", failed); failed = row;
                }
                if (failed == null) Thread.sleep(25);
            }
            assertNotNull("The failed PREPARED VM has no actual OS death record", failed);
            for (ActivityManager.RunningAppProcessInfo live : host.getSystemService(ActivityManager.class).getRunningAppProcesses()) {
                assertTrue("Uncommitted PREPARED VM remained alive", live.pid != failed.getPid());
            }
            assertTrue("A failed journal write killed the engine server", server.isBinderAlive());
        } finally {
            Files.deleteIfExists(blocker.toPath()); Files.deleteIfExists(pending.toPath());
            if (server.isBinderAlive()) { Process.killProcess(serverPid); awaitDeath(server); }
        }
        Bundle recovered = acquire(fixture.packageName, fixture.packageName + ":exit_storage_failure");
        assertTrue("A fresh server could not recover after the actual storage fault", recovered.getBinder("client").isBinderAlive());
        kill(recovered);
    }

    private void verify(JSONObject report, ApplicationExitInfo physical) throws Exception {
        for (String check : new String[]{"ownNullPidAndLimit", "peerAndHostDenied", "traceArgumentsDenied"}) assertTrue(report.toString(), report.getBoolean(check));
        JSONObject target = report.getJSONObject("target"); assertEquals(physical.getPid(), target.getInt("pid"));
        assertEquals(physical.getReason(), target.getInt("reason")); assertEquals(physical.getStatus(), target.getInt("status"));
        assertEquals(physical.getTimestamp(), target.getLong("timestamp")); assertEquals(physical.getPackageUid(), target.getInt("uid"));
        assertEquals(Base64.encodeToString(physical.getProcessStateSummary(), Base64.NO_WRAP), target.getString("summary"));
    }

    private JSONObject launch(InstalledPackage installed, String mode, String nonce, JSONObject input) throws Exception {
        File files = new File(installed.dataDir, "files"); if (!files.isDirectory()) assertTrue(files.mkdirs());
        File request = new File(files, "exit-history-" + nonce + ".request.json"), output = new File(files, "exit-history-" + nonce + ".json");
        assertFalse(output.exists());
        try {
            if (input != null) Files.write(request.toPath(), input.toString().getBytes(StandardCharsets.UTF_8));
            FixtureRun.prepare(installed, mode + nonce); Engine.get().launch(installed.packageName);
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (!output.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            assertTrue("Actual guest exit API probe did not finish", output.isFile());
            JSONObject result = new JSONObject(new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8));
            assertEquals(nonce, result.getString("nonce")); assertFalse(result.toString(), result.has("failure")); return result;
        } finally {
            Files.deleteIfExists(request.toPath()); Files.deleteIfExists(output.toPath());
            Files.deleteIfExists(new File(files, output.getName() + ".tmp").toPath());
        }
    }
    private Bundle acquire(String pkg, String process) {
        Bundle input = EngineProtocol.response(); input.putString("package", pkg); input.putString("process", process);
        return EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.ACQUIRE, input);
    }
    private static void kill(Bundle process) throws Exception {
        IBinder binder = process.getBinder("client"); assertNotNull(binder); assertTrue(binder.isBinderAlive());
        int pid = process.getInt("pid"); assertTrue(pid > 0 && pid != Process.myPid());
        Process.killProcess(pid); awaitDeath(binder);
    }
    private static void awaitDeath(IBinder binder) throws Exception {
        CountDownLatch death = new CountDownLatch(1);
        try { binder.linkToDeath(death::countDown, 0); } catch (android.os.DeadObjectException alreadyDead) { return; }
        assertTrue("The exact owned Binder did not die", death.await(10, TimeUnit.SECONDS));
    }
    private List<ApplicationExitInfo> actual(int pid) {
        Context host = getInstrumentation().getTargetContext();
        return host.getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(host.getPackageName(), pid, 0);
    }
    private ApplicationExitInfo awaitRecord(int pid, byte[] summary) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        int lastReason = -1, lastStatus = -1;
        do {
            for (ApplicationExitInfo value : actual(pid)) {
                if (value.getPid() != pid || summary != null && !Arrays.equals(summary, value.getProcessStateSummary())) continue;
                lastReason = value.getReason(); lastStatus = value.getStatus();
                // AMS can publish UNKNOWN before its separate zygote SIGCHLD message supplies wait status.
                if (lastReason == ApplicationExitInfo.REASON_SIGNALED && lastStatus == android.system.OsConstants.SIGKILL) return value;
                if (lastReason != ApplicationExitInfo.REASON_UNKNOWN && lastReason != ApplicationExitInfo.REASON_SIGNALED) {
                    fail("Owned SIGKILL probe has another actual OS reason/status: " + lastReason + "/" + lastStatus);
                }
            }
            Thread.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Actual OS did not complete the exact owned PID/summary within 10s; last reason/status=" + lastReason + "/" + lastStatus); return null;
    }
    private int serverPid() {
        Context host = getInstrumentation().getTargetContext();
        for (ActivityManager.RunningAppProcessInfo value : host.getSystemService(ActivityManager.class).getRunningAppProcesses()) {
            if (value.uid == Process.myUid() && (host.getPackageName() + ":engine_server").equals(value.processName)) return value.pid;
        }
        throw new AssertionError("No exact owned engine server");
    }
    private InstalledPackage installIfAbsent(String name, String asset) throws Exception {
        InstalledPackage installed = Engine.get().packages().get(name); if (installed != null) return installed;
        File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "exit-" + asset);
        try {
            try (InputStream stream = getInstrumentation().getContext().getAssets().open(asset)) { Files.copy(stream, apk.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            return Engine.get().install(Collections.singletonList(apk));
        } finally { Files.deleteIfExists(apk.toPath()); }
    }
}
