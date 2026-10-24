package io.github.xmy953538104.mybox.engine;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.json.JSONObject;

public final class JobRuntimeTest extends InstrumentationTestCase {
    public void testExecutorDeathLetsAndroidRescheduleTheOriginalJob() throws Exception { recover(false); }
    public void testServerDeathLetsAndroidColdStartAndRescheduleTheOriginalJob() throws Exception { recover(true); }

    private void recover(boolean serverDeath) throws Exception {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        File previousDiagnostic = new File(host.getFilesDir(), "job-recovery-failure-" + (serverDeath ? "server" : "executor") + ".txt");
        if (previousDiagnostic.exists() && !previousDiagnostic.delete()) throw new java.io.IOException("Cannot clear prior scheduler failure diagnostic");
        InstalledPackage fixture = engine.packages().get("io.github.xmy953538104.mybox.fixture"); assertNotNull(fixture);
        for (String name : new String[]{"job-recovery-scheduled.json", "job-recovery-first.json", "job-recovery-second.json"}) {
            File file = new File(fixture.dataDir, "files/" + name); if (file.exists() && !file.delete()) throw new java.io.IOException("Cannot clear recovery report");
        }
        FixtureRun.prepare(fixture, "jobs-recover"); engine.launch(fixture.packageName);
        JSONObject scheduled = report(fixture, "job-recovery-scheduled.json", 10000);
        assertFalse(scheduled.toString(), scheduled.has("failure"));
        JSONObject first = report(fixture, "job-recovery-first.json", 20000);
        assertEquals(scheduled.getString("nonce"), first.getString("nonce")); assertEquals(7005, first.getInt("jobId"));
        int executorPid = first.getInt("pid");
        int proxyPid = ownedPid(host, ":engine_jobs"), serverPid = ownedPid(host, ":engine_server");
        assertTrue(proxyPid > 0 && serverPid > 0 && executorPid > 0 && executorPid != android.os.Process.myPid());
        boolean ownedExecutor = false;
        for (int slot = 0; slot < EngineProtocol.SLOT_COUNT; slot++) if (executorPid == ownedPid(host, ":engine_p" + slot)) ownedExecutor = true;
        assertTrue("Refusing to kill a PID outside this host's guest slots", ownedExecutor);
        JobInfo pending = null;
        for (JobInfo job : host.getSystemService(JobScheduler.class).getAllPendingJobs()) {
            if (!new ComponentName(host, OsJobService.class).equals(job.getService())) continue;
            JobEnvelope envelope = JobEnvelope.read(job.getExtras(), job.getId(), new OsPendingStore(engine));
            if (envelope.original.getId() == 7005 && fixture.packageName.equals(envelope.packageName)) pending = job;
        }
        assertNotNull("No original OS recovery job", pending);
        android.os.Process.killProcess(serverDeath ? serverPid : executorPid);
        JSONObject second;
        try { second = report(fixture, "job-recovery-second.json", 90000); }
        catch (Exception | AssertionError failure) {
            try { captureSchedulerFailure(serverDeath); } catch (Exception diagnostic) { failure.addSuppressed(diagnostic); }
            throw failure;
        }
        assertEquals("Fixture resubmitted a new job instead of Android rescheduling the original", first.getString("nonce"), second.getString("nonce"));
        assertEquals(7005, second.getInt("jobId")); assertTrue(executorPid != second.getInt("pid"));
        assertTrue("Physical proxy did not die with its executor", proxyPid != ownedPid(host, ":engine_jobs"));
        if (serverDeath) assertTrue("Android did not cold-start the engine server", serverPid != ownedPid(host, ":engine_server"));
        long end = SystemClock.elapsedRealtime() + 5000;
        while (host.getSystemService(JobScheduler.class).getPendingJob(pending.getId()) != null && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
        assertNull("Recovered original OS job did not actually finish", host.getSystemService(JobScheduler.class).getPendingJob(pending.getId()));
    }
    private void captureSchedulerFailure(boolean serverDeath) throws Exception {
        File destination = new File(getInstrumentation().getTargetContext().getFilesDir(), "job-recovery-failure-" + (serverDeath ? "server" : "executor") + ".txt");
        android.os.ParcelFileDescriptor descriptor = getInstrumentation().getUiAutomation().executeShellCommand("dumpsys jobscheduler");
        try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] bytes = new byte[16384]; int total = 0;
            for (int size; (size = input.read(bytes)) != -1;) {
                total += size; if (total > 2 * 1024 * 1024) throw new java.io.IOException("Scheduler diagnostic exceeded its bound");
                output.write(bytes, 0, size);
            }
        }
    }
    private static int ownedPid(Context host, String suffix) {
        for (android.app.ActivityManager.RunningAppProcessInfo process : host.getSystemService(android.app.ActivityManager.class).getRunningAppProcesses()) {
            if (process.uid == android.os.Process.myUid() && (host.getPackageName() + suffix).equals(process.processName)) return process.pid;
        }
        return -1;
    }
    public void testJobEnvelopeBindsActualOwnerIdAndAuthenticatedBytes() throws Exception {
        Engine engine = Engine.get(); engine.server();
        InstalledPackage fixture = engine.packages().get("io.github.xmy953538104.mybox.fixture"); assertNotNull(fixture);
        CallerIdentity owner = new CallerIdentity(fixture.packageName, fixture.appId, 1, fixture.certificateSha256);
        ComponentName service = new ComponentName(fixture.packageName, fixture.packageName + ".jobs.JobProbe$Worker");
        JobInfo original = new JobInfo.Builder(7001, service).setMinimumLatency(60000).build();
        OsPendingStore store = new OsPendingStore(engine);
        JobInfo physical = new JobEnvelope(owner, "probe", 12345, original).physical(engine, store);
        JobEnvelope valid = JobEnvelope.read(physical.getExtras(), 12345, store);
        assertTrue(valid.belongs(owner, "probe")); assertFalse(valid.belongs(owner, null));
        assertFalse(valid.belongs(new CallerIdentity(owner.packageName, owner.appId + 1, 1, owner.certificateSha256), "probe"));
        assertFalse(valid.belongs(new CallerIdentity(owner.packageName, owner.appId, 1, Collections.singletonList("wrong")), "probe"));
        assertEquals(7001, valid.original.getId()); assertEquals(service, valid.original.getService());
        assertEquals(7001, original.getId()); assertEquals(service, original.getService());
        OsPendingStore nextBoot = new OsPendingStore(engine); RuntimeReflection.set(nextBoot, "boot", store.boot + 1);
        try { JobEnvelope.read(physical.getExtras(), 12345, nextBoot); fail("Nonpersistent job crossed a boot boundary"); }
        catch (SecurityException expected) { }
        JobInfo persistent = new JobInfo.Builder(7003, service).setPersisted(true).setMinimumLatency(60000).build();
        JobInfo durable = new JobEnvelope(owner, null, 12347, persistent).physical(engine, store);
        assertTrue(durable.isPersisted());
        assertTrue(JobEnvelope.read(durable.getExtras(), 12347, nextBoot).original.isPersisted());
        InstalledPackage noBoot = engine.packages().get("io.github.xmy953538104.mybox.installtarget");
        assertNotNull("The installer target is required for the boot-grant boundary", noBoot);
        CallerIdentity noBootOwner = new CallerIdentity(noBoot.packageName, noBoot.appId, 1, noBoot.certificateSha256);
        JobInfo notDeclared = new JobInfo.Builder(7004, new ComponentName(noBoot.packageName, noBoot.packageName + ".Job")).setPersisted(true).setMinimumLatency(60000).build();
        try { new JobEnvelope(noBootOwner, null, 12348, notDeclared).service(engine); fail("Host boot permission leaked to a creator that did not request it"); }
        catch (SecurityException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("RECEIVE_BOOT_COMPLETED")); }
        try { JobEnvelope.read(physical.getExtras(), 12346, store); fail("Different OS job ID accepted"); } catch (SecurityException expected) { }
        android.os.PersistableBundle forged = new android.os.PersistableBundle(physical.getExtras());
        forged.putString(JobEnvelope.MAC, android.util.Base64.encodeToString(new byte[32], android.util.Base64.NO_WRAP));
        try { JobEnvelope.read(forged, 12345, store); fail("Forged job payload accepted"); } catch (SecurityException expected) { }
    }
    public void testAndroidOwnsSchedulingAndTwoActualWorkItems() throws Exception {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        String name = "io.github.xmy953538104.mybox.fixture";
        File apk = new File(host.getCacheDir(), "job-fixture.apk"); InstalledPackage installed;
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk"); FileOutputStream output = new FileOutputStream(apk)) {
                byte[] bytes = new byte[16384]; for (int size; (size = input.read(bytes)) != -1;) output.write(bytes, 0, size);
            }
            installed = engine.packages().get(name);
            if (installed == null) installed = engine.install(Collections.singletonList(apk));
            else assertTrue("Import current fixture before this runtime group", java.util.Arrays.equals(Files.readAllBytes(apk.toPath()), Files.readAllBytes(installed.baseApk.toPath())));
        } finally { apk.delete(); }
        for (String report : new String[]{"job-scheduled.json", "job-executed.json", "job-stopped.json"}) {
            File file = new File(installed.dataDir, "files/" + report);
            if (file.exists() && !file.delete()) throw new java.io.IOException("Cannot clear job fixture report");
        }
        FixtureRun.prepare(installed, "jobs"); engine.launch(name);
        JSONObject scheduled = report(installed, "job-scheduled.json", 15000);
        assertFalse(scheduled.toString(), scheduled.has("failure"));
        for (String key : new String[]{"singleUser", "systemLibrary", "virtualOwnerAbsent", "realRestrictionsRead", "foreignRestrictionsRejected", "disabledDirectBootQuery", "pendingIdentity", "pendingList", "foreignRejected", "persistedPending", "persistedCancel", "uriRejected"}) assertTrue(scheduled.toString(), scheduled.getBoolean(key));
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertTrue(scheduled.toString(), scheduled.getBoolean("namespaceIdentity")); assertTrue(scheduled.toString(), scheduled.getBoolean("namespaceCancel"));
        }
        JobScheduler scheduler = host.getSystemService(JobScheduler.class); JobInfo actual = null;
        for (JobInfo job : scheduler.getAllPendingJobs()) if (new ComponentName(host, OsJobService.class).equals(job.getService())) {
            JobEnvelope envelope = JobEnvelope.read(job.getExtras(), job.getId(), new OsPendingStore(engine));
            if (name.equals(envelope.packageName) && envelope.original.getId() == 7001) actual = job;
        }
        assertNotNull("No actual Android job record for the guest work", actual);
        assertTrue("Physical job ID leaked the virtual ID", actual.getId() != 7001);
        assertEquals("android.permission.BIND_JOB_SERVICE", host.getPackageManager().getServiceInfo(actual.getService(), 0).permission);
        JSONObject executed = report(installed, "job-executed.json", 60000);
        assertFalse(executed.toString(), executed.has("failure")); assertEquals(7001, executed.getInt("jobId"));
        for (String key : new String[]{"extras", "workValues", "workIds", "completed"}) assertTrue(executed.toString(), executed.getBoolean(key));
        assertTrue(executed.getInt("pid") != android.os.Process.myPid());
        long end = SystemClock.elapsedRealtime() + 5000;
        while (scheduler.getPendingJob(actual.getId()) != null && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
        assertNull("Actual OS job remained after real completion", scheduler.getPendingJob(actual.getId()));
    }
    public void testHostCannotAcquireGuestJobOrPhysicalDispatchPrivileges() {
        Bundle request = EngineProtocol.response(); request.putString("service", "jobs");
        try { EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.GET_SERVICE, request); fail("Host acquired guest scheduling endpoint"); }
        catch (EngineException denied) { assertTrue(denied.getMessage().contains("identity")); }
        try { EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.JOB_SERVICE, request); fail("Host impersonated physical job dispatcher"); }
        catch (EngineException denied) { assertTrue(denied.getMessage().contains("required engine process")); }
    }
    public void testExpeditedJobExecutesWithActualAndroidStatus() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        Engine engine = Engine.get();
        InstalledPackage fixture = engine.packages().get("io.github.xmy953538104.mybox.fixture"); assertNotNull(fixture);
        for (String name : new String[]{"job-expedited-scheduled.json", "job-expedited-executed.json"}) {
            File file = new File(fixture.dataDir, "files/" + name);
            if (file.exists() && !file.delete()) throw new java.io.IOException("Cannot clear expedited report");
        }
        JobInfo expedited = new JobInfo.Builder(7006, new ComponentName(fixture.packageName, fixture.packageName + ".jobs.JobProbe$Worker")).setExpedited(true).build();
        JobEnvelope.validate(expedited);
        JobInfo copy = JobEnvelope.copy(expedited); assertTrue(copy.isExpedited());
        RuntimeReflection.set(copy, "flags", 1 << 3);
        try { JobEnvelope.validate(copy); fail("Privileged standby exemption accepted"); }
        catch (UnsupportedOperationException expected) { }
        FixtureRun.prepare(fixture, "jobs-expedited"); engine.launch(fixture.packageName);
        JSONObject scheduled = report(fixture, "job-expedited-scheduled.json", 15000);
        assertFalse(scheduled.toString(), scheduled.has("failure")); assertEquals(JobScheduler.RESULT_SUCCESS, scheduled.getInt("accepted"));
        JSONObject executed = report(fixture, "job-expedited-executed.json", 20000);
        assertEquals(7006, executed.getInt("jobId")); assertTrue(executed.toString(), executed.getBoolean("expedited"));
        assertTrue(executed.getInt("pid") != android.os.Process.myPid());
        for (JobInfo job : getInstrumentation().getTargetContext().getSystemService(JobScheduler.class).getAllPendingJobs()) {
            if (!new ComponentName(getInstrumentation().getTargetContext(), OsJobService.class).equals(job.getService())) continue;
            JobEnvelope envelope = JobEnvelope.read(job.getExtras(), job.getId(), new OsPendingStore(engine));
            assertFalse("Actually completed expedited job stayed pending", fixture.packageName.equals(envelope.packageName) && envelope.original.getId() == 7006);
        }
    }
    private static JSONObject report(InstalledPackage installed, String name, long budget) throws Exception {
        File file = new File(installed.dataDir, "files/" + name); long end = SystemClock.elapsedRealtime() + budget;
        while (!file.isFile() && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
        assertTrue("Guest job produced no " + name, file.isFile());
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}
