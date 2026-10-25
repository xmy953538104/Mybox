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
    public void testExpiredOriginalJobDeadlinePreventsStartBeforeServiceIpc() throws Exception {
        ServerJobs jobs = new ServerJobs(Engine.get(), new OsPendingStore(Engine.get()), null, null);
        java.util.concurrent.ExecutorService cleanup = (java.util.concurrent.ExecutorService) RuntimeReflection.get(jobs, "cleanup");
        try {
            Class<?> type = Class.forName(ServerJobs.class.getName() + "$Session");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(ServerJobs.class, JobEnvelope.class, android.os.IBinder.class, android.os.IBinder.class);
            constructor.setAccessible(true); Object session = constructor.newInstance(jobs, null, new android.os.Binder(), new android.os.Binder());
            RuntimeReflection.set(session, "startDeadline", SystemClock.elapsedRealtime() - 1);
            java.lang.reflect.Method send = type.getDeclaredMethod("send", String.class, android.app.job.JobParameters.class, android.app.job.JobWorkItem.class); send.setAccessible(true);
            try { send.invoke(session, "startJob", null, null); fail("Expired original deadline reached parameter/service dispatch"); }
            catch (java.lang.reflect.InvocationTargetException expected) {
                assertTrue(expected.getCause().toString(), expected.getCause() instanceof EngineException);
                assertTrue(expected.getCause().getMessage().contains("original dispatch deadline"));
            }
        } finally { cleanup.shutdownNow(); }
    }
    public void testPhysicalJobGuardRetirementHasOneWinnerAndHonorsReleasedState() throws Exception {
        OsJobService proxy = new OsJobService();
        java.util.concurrent.ExecutorService dispatch = (java.util.concurrent.ExecutorService) RuntimeReflection.get(proxy, "dispatch");
        java.util.concurrent.ExecutorService racers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            Class<?> type = Class.forName(OsJobService.class.getName() + "$Guard");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(OsJobService.class); constructor.setAccessible(true);
            Object live = constructor.newInstance(proxy), released = constructor.newInstance(proxy);
            java.lang.reflect.Method claim = type.getDeclaredMethod("claimRetirement"); claim.setAccessible(true);
            RuntimeReflection.set(released, "closed", true);
            assertEquals("A released guard admitted late failure/death", Boolean.FALSE, claim.invoke(released));
            java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
            java.util.ArrayList<java.util.concurrent.Future<?>> attempts = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) attempts.add(racers.submit(() -> {
                try { gate.await(); if ((Boolean) claim.invoke(live)) winners.incrementAndGet(); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }));
            gate.countDown(); for (java.util.concurrent.Future<?> attempt : attempts) attempt.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("Concurrent executor/server death or FAIL got multiple retirement winners", 1, winners.get());
            assertEquals(Boolean.FALSE, claim.invoke(live));
        } finally { racers.shutdownNow(); dispatch.shutdownNow(); }
    }
    public void testAuthenticatedNonstartWithoutExecutionIsExplicitNonDelivery() throws Exception {
        Engine engine = Engine.get(); InstalledPackage fixture = engine.packages().get("io.github.xmy953538104.mybox.fixture"); assertNotNull(fixture);
        OsPendingStore store = new OsPendingStore(engine); ServerJobs jobs = new ServerJobs(engine, store, null, null);
        java.util.concurrent.ExecutorService cleanup = (java.util.concurrent.ExecutorService) RuntimeReflection.get(jobs, "cleanup");
        java.util.concurrent.atomic.AtomicInteger callbackCalls = new java.util.concurrent.atomic.AtomicInteger();
        android.os.Binder callback = new android.os.Binder() {
            @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                callbackCalls.incrementAndGet(); return false;
            }
        };
        callback.attachInterface(null, ServerJobs.CALLBACK);
        CallerIdentity caller = new CallerIdentity(fixture.packageName, fixture.appId, 1, fixture.certificateSha256);
        JobInfo original = new JobInfo.Builder(7007, new ComponentName(fixture.packageName, fixture.packageName + ".jobs.JobProbe$Worker")).setMinimumLatency(1000).build();
        JobEnvelope envelope = new JobEnvelope(caller, null, 12347, original);
        android.app.job.JobParameters parameters = parameters(envelope.physical(engine, store), callback);
        Bundle input = EngineProtocol.response(); input.putParcelable("parameters", parameters);
        try {
            for (String operation : new String[]{"stopJob", "onNetworkChanged", "getTransferredDownloadBytes", "getTransferredUploadBytes"}) {
                input.putString("operation", operation); assertNotDelivered(jobs.dispatch(input));
            }
            input.putString("operation", "unknown");
            try { jobs.dispatch(input); fail("Unknown executor operation was treated as stale"); } catch (UnsupportedOperationException expected) { }
            input.putString("operation", "startJob");
            try { jobs.dispatch(input); fail("Start without guard was treated as stale"); } catch (SecurityException expected) { }
            input.putString("operation", "stopJob");
            RuntimeReflection.set(parameters, "callback", new android.os.Binder());
            try { jobs.dispatch(input); fail("Unidentified callback was treated as stale"); } catch (SecurityException expected) { }
            RuntimeReflection.set(parameters, "callback", callback);
            android.os.Binder guard = new android.os.Binder() {
                @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                    assertEquals(OsJobService.RELEASE, code); data.enforceInterface(OsJobService.GUARD); data.readBundle();
                    reply.writeNoException(); reply.writeBundle(EngineProtocol.response()); return true;
                }
            };
            Class<?> type = Class.forName(ServerJobs.class.getName() + "$Session");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(ServerJobs.class, JobEnvelope.class, android.os.IBinder.class, android.os.IBinder.class);
            constructor.setAccessible(true); Object session = constructor.newInstance(jobs, envelope, callback, guard);
            @SuppressWarnings("unchecked") java.util.Map<android.os.IBinder, Object> sessions = (java.util.Map<android.os.IBinder, Object>) RuntimeReflection.get(jobs, "sessions");
            sessions.put(callback, session);
            JobEnvelope replacement = new JobEnvelope(caller, null, 12347, original);
            input.putParcelable("parameters", parameters(replacement.physical(engine, store), callback));
            try { jobs.dispatch(input); fail("Live execution accepted a different signed capability"); } catch (SecurityException expected) { }
            input.putParcelable("parameters", parameters);
            java.lang.reflect.Method close = type.getDeclaredMethod("close"); close.setAccessible(true); close.invoke(session);
            // Emulate a dispatcher that already retained this exact Session before close removed its map entry.
            java.lang.reflect.Method send = type.getDeclaredMethod("send", String.class, android.app.job.JobParameters.class, android.app.job.JobWorkItem.class);
            send.setAccessible(true); assertEquals(Boolean.FALSE, send.invoke(session, "stopJob", parameters, null));
            sessions.put(callback, session); assertNotDelivered(jobs.dispatch(input)); sessions.clear();
            assertEquals("Non-delivery fabricated an OS callback", 0, callbackCalls.get());
        } finally { cleanup.shutdown(); assertTrue(cleanup.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)); }
    }
    private static void assertNotDelivered(Bundle result) {
        assertTrue(result.containsKey("delivered")); assertFalse(result.getBoolean("delivered"));
        assertEquals(ServerJobs.NO_ACTIVE_EXECUTION, result.getString("rejection")); assertFalse(result.containsKey("error"));
    }
    private static android.app.job.JobParameters parameters(JobInfo physical, android.os.IBinder callback) throws Exception {
        java.lang.reflect.Constructor<?> constructor = null;
        for (java.lang.reflect.Constructor<?> candidate : android.app.job.JobParameters.class.getDeclaredConstructors()) {
            if (java.util.Arrays.asList(candidate.getParameterTypes()).contains(android.os.IBinder.class)) constructor = candidate;
        }
        assertNotNull("No platform JobParameters callback constructor", constructor); constructor.setAccessible(true);
        Class<?>[] types = constructor.getParameterTypes(); Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == boolean.class) values[i] = false;
            else if (types[i] == int.class) values[i] = 0;
            else if (types[i] == long.class) values[i] = 0L;
            else if (types[i].isPrimitive()) throw new AssertionError("Unknown JobParameters constructor primitive");
        }
        android.app.job.JobParameters result = (android.app.job.JobParameters) constructor.newInstance(values);
        RuntimeReflection.set(result, "jobId", physical.getId()); RuntimeReflection.set(result, "extras", physical.getExtras());
        RuntimeReflection.set(result, "callback", callback); return result;
    }
    public void testRealStopQueuedBehindAnotherBindDoesNotKillSharedProxy() throws Exception {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        InstalledPackage fixture = engine.packages().get("io.github.xmy953538104.mybox.fixture"); assertNotNull(fixture);
        String run = java.util.UUID.randomUUID().toString(), prefix = "job-race-" + run + "-";
        JobScheduler scheduler = host.getSystemService(JobScheduler.class); Throwable original = null;
        File receiptRequest = new File(host.getFilesDir(), OsJobService.DEBUG_STOP_REQUEST);
        File receiptFile = new File(host.getFilesDir(), OsJobService.DEBUG_STOP_RECEIPT);
        JobInfo a = null, b = null;
        try {
            FixtureRun.prepare(fixture, "jobs-stop-finish:" + run); engine.launch(fixture.packageName);
            JSONObject scheduled = report(fixture, prefix + "scheduled.json", 10000); assertFalse(scheduled.toString(), scheduled.has("failure"));
            JSONObject started = report(fixture, prefix + "a-started.json", 20000);
            JSONObject bound = report(fixture, prefix + "b-bound.json", 20000);
            assertEquals(run, started.getString("run")); assertEquals(run, bound.getString("run"));
            assertTrue("B bind must block a different actual guest main thread", started.getInt("pid") != bound.getInt("pid"));
            int proxy = ownedPid(host, ":engine_jobs"); assertTrue(proxy > 0);
            a = physicalJob(fixture, 7007, run); b = physicalJob(fixture, 7008, run); assertNotNull(a); assertNotNull(b);
            if (receiptFile.exists() && !receiptFile.delete()) throw new java.io.IOException("Cannot clear previous actual stop receipt");
            JSONObject wanted = new JSONObject(); wanted.put("token", run); wanted.put("physicalId", a.getId()); wanted.put("proxyPid", proxy);
            File temporary = new File(host.getFilesDir(), OsJobService.DEBUG_STOP_REQUEST + ".tmp");
            Files.write(temporary.toPath(), wanted.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), receiptRequest.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            long cancelledAt = SystemClock.elapsedRealtime(); scheduler.cancel(a.getId());
            long receiptDeadline = bound.getLong("elapsed") + 3000;
            while (!receiptFile.isFile() && SystemClock.elapsedRealtime() < receiptDeadline) Thread.sleep(10);
            assertTrue("OS stop never entered the actual proxy queue within the bind barrier", receiptFile.isFile());
            JSONObject queued = new JSONObject(new String(Files.readAllBytes(receiptFile.toPath()), StandardCharsets.UTF_8));
            assertTrue("Stop receipt correlation differs", run.equals(queued.getString("token")));
            assertEquals(a.getId(), queued.getInt("physicalId")); assertEquals(proxy, queued.getInt("proxyPid"));
            assertTrue("Stop receipt is outside this cancellation/barrier interval", queued.getLong("elapsed") >= cancelledAt && queued.getLong("elapsed") <= receiptDeadline);
            // Only this genuine enqueue receipt, not cancel's return, authorizes the test's finish signal.
            signal(fixture, prefix + "finish-a.json");
            long finishBudget = bound.getLong("elapsed") + 3000 - SystemClock.elapsedRealtime();
            assertTrue("No remaining real bind-barrier budget", finishBudget > 0);
            JSONObject finished = report(fixture, prefix + "a-finished.json", finishBudget); assertEquals(run, finished.getString("run"));
            assertNull(scheduler.getPendingJob(a.getId()));
            signal(fixture, prefix + "release-b.json");
            JSONObject completed = report(fixture, prefix + "b-finished.json", 8000); assertEquals(run, completed.getString("run"));
            assertEquals("Late stop killed the proxy shared by B", proxy, ownedPid(host, ":engine_jobs"));
            assertFalse("Barrier expired instead of being released", new File(fixture.dataDir, "files/" + prefix + "b-bind-failed.json").exists());
            assertFalse("Closed A unexpectedly ran onStopJob", new File(fixture.dataDir, "files/" + prefix + "a-stopped.json").exists());
            long end = SystemClock.elapsedRealtime() + 5000;
            while (scheduler.getPendingJob(b.getId()) != null && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
            assertNull("B's actual OS record did not complete", scheduler.getPendingJob(b.getId()));
        } catch (Exception | AssertionError failure) { original = failure; throw failure; }
        finally {
            Throwable cleanupFailure = null;
            // Release the blocking main thread even if a different cleanup operation fails.
            try { signal(fixture, prefix + "release-b.json"); }
            catch (Exception failure) { cleanupFailure = failure; }
            try { signal(fixture, prefix + "finish-a.json"); }
            catch (Exception failure) { if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure); }
            for (File control : new File[]{receiptRequest, new File(host.getFilesDir(), OsJobService.DEBUG_STOP_REQUEST + ".tmp")}) {
                try { if (control.exists() && !control.delete()) throw new java.io.IOException("Cannot remove debug stop request"); }
                catch (Exception failure) { if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure); }
            }
            try {
                if (a == null) a = physicalJob(fixture, 7007, run); if (b == null) b = physicalJob(fixture, 7008, run);
                if (a != null) scheduler.cancel(a.getId()); if (b != null) scheduler.cancel(b.getId());
            } catch (Exception | AssertionError failure) { if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure); }
            if (cleanupFailure != null) {
                if (original != null) original.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof Exception) throw (Exception) cleanupFailure;
                else throw (AssertionError) cleanupFailure;
            }
        }
    }
    private JobInfo physicalJob(InstalledPackage fixture, int id, String run) throws Exception {
        Context host = getInstrumentation().getTargetContext(); Engine engine = Engine.get();
        for (JobInfo job : host.getSystemService(JobScheduler.class).getAllPendingJobs()) {
            if (!new ComponentName(host, OsJobService.class).equals(job.getService())) continue;
            JobEnvelope envelope = JobEnvelope.read(job.getExtras(), job.getId(), new OsPendingStore(engine));
            if (fixture.packageName.equals(envelope.packageName) && envelope.original.getId() == id
                    && run.equals(envelope.original.getExtras().getString("run"))) return job;
        }
        return null;
    }
    public void testProxyDeathBeforeConnectionReleasesTheOriginalBindingWithoutStartingJob() throws Exception {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        InstalledPackage fixture = engine.packages().get("io.github.xmy953538104.mybox.fixture"); assertNotNull(fixture);
        String run = java.util.UUID.randomUUID().toString(), prefix = "job-race-" + run + "-";
        JobScheduler scheduler = host.getSystemService(JobScheduler.class); Throwable original = null;
        try {
            FixtureRun.prepare(fixture, "jobs-stop-finish:" + run); engine.launch(fixture.packageName);
            JSONObject scheduled = report(fixture, prefix + "scheduled.json", 10000); assertFalse(scheduled.toString(), scheduled.has("failure"));
            report(fixture, prefix + "a-started.json", 20000);
            JSONObject bound = report(fixture, prefix + "b-bound.json", 20000);
            JobInfo a = physicalJob(fixture, 7007, run), b = physicalJob(fixture, 7008, run); assertNotNull(a); assertNotNull(b);
            int proxy = ownedPid(host, ":engine_jobs"); assertTrue(proxy > 0);
            // Remove real pending jobs so genuine proxy death cannot legitimately reschedule them.
            scheduler.cancel(a.getId()); scheduler.cancel(b.getId()); android.os.Process.killProcess(proxy);
            long end = bound.getLong("elapsed") + 3000;
            while (ownedPid(host, ":engine_jobs") == proxy && SystemClock.elapsedRealtime() < end) Thread.sleep(10);
            assertTrue("The original physical proxy did not actually die", ownedPid(host, ":engine_jobs") != proxy);
            signal(fixture, prefix + "release-b.json");
            JSONObject unbound = report(fixture, prefix + "b-unbound.json", 5000);
            assertEquals(bound.getInt("pid"), unbound.getInt("pid"));
            assertFalse("Closed pending job started after its old connection arrived", new File(fixture.dataDir, "files/" + prefix + "b-started.json").exists());
            assertFalse("Fixture gate expired", new File(fixture.dataDir, "files/" + prefix + "b-bind-failed.json").exists());
            assertNull(scheduler.getPendingJob(a.getId())); assertNull(scheduler.getPendingJob(b.getId()));
        } catch (Exception | AssertionError failure) { original = failure; throw failure; }
        finally {
            Throwable cleanupFailure = null;
            for (String phase : new String[]{"release-b", "finish-a"}) {
                try { signal(fixture, prefix + phase + ".json"); }
                catch (Exception failure) { if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure); }
            }
            for (int id : new int[]{7007, 7008}) {
                try { JobInfo job = physicalJob(fixture, id, run); if (job != null) scheduler.cancel(job.getId()); }
                catch (Exception failure) { if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure); }
            }
            if (cleanupFailure != null) { if (original != null) original.addSuppressed(cleanupFailure); else throw (Exception) cleanupFailure; }
        }
    }
    private static void signal(InstalledPackage fixture, String name) throws Exception {
        Files.write(new File(fixture.dataDir, "files/" + name).toPath(), new byte[]{1});
    }
    public void testEarlyBindFailureStillReleasesThePhysicalGuardExactlyOnce() throws Exception {
        ServerJobs jobs = new ServerJobs(Engine.get(), new OsPendingStore(Engine.get()), null, null);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        android.os.Binder guard = new android.os.Binder() {
            @Override public boolean unlinkToDeath(android.os.IBinder.DeathRecipient recipient, int flags) {
                throw new java.util.NoSuchElementException("Binding failed before the death link");
            }
            @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                assertEquals(OsJobService.RELEASE, code); data.enforceInterface(OsJobService.GUARD);
                assertEquals(EngineProtocol.VERSION, data.readBundle().getInt("protocol"));
                releases.incrementAndGet(); reply.writeNoException(); reply.writeBundle(EngineProtocol.response()); released.countDown(); return true;
            }
        };
        java.util.concurrent.ExecutorService cleanup = (java.util.concurrent.ExecutorService) RuntimeReflection.get(jobs, "cleanup");
        try {
            Class<?> type = Class.forName(ServerJobs.class.getName() + "$Session");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(ServerJobs.class, JobEnvelope.class, android.os.IBinder.class, android.os.IBinder.class);
            constructor.setAccessible(true);
            Object session = constructor.newInstance(jobs, null, new android.os.Binder(), guard);
            java.lang.reflect.Method close = type.getDeclaredMethod("close"); close.setAccessible(true);
            close.invoke(session); close.invoke(session);
            RuntimeReflection.set(session, "startSent", true);
            java.lang.reflect.Method failure = type.getDeclaredMethod("connectionFailed", String.class, Exception.class); failure.setAccessible(true);
            failure.invoke(session, "DISCONNECTED", new EngineException("Late old connection callback"));
            assertTrue("Early failure prevented physical guard release", released.await(3, java.util.concurrent.TimeUnit.SECONDS));
            cleanup.shutdown(); assertTrue(cleanup.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, releases.get());
        } finally { cleanup.shutdownNow(); }
    }
    public void testActualConnectionFailureWinsOnceAgainstLaterNormalClose() throws Exception {
        ServerJobs jobs = new ServerJobs(Engine.get(), new OsPendingStore(Engine.get()), null, null);
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(), releases = new java.util.concurrent.atomic.AtomicInteger();
        android.os.Binder guard = new android.os.Binder() {
            @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                data.enforceInterface(OsJobService.GUARD); Bundle input = data.readBundle();
                if (code == OsJobService.FAIL) { assertEquals("DISCONNECTED", input.getString("reason")); failures.incrementAndGet(); }
                else if (code == OsJobService.RELEASE) releases.incrementAndGet();
                else throw new AssertionError("Unexpected guard operation");
                reply.writeNoException(); reply.writeBundle(EngineProtocol.response()); return true;
            }
        };
        java.util.concurrent.ExecutorService cleanup = (java.util.concurrent.ExecutorService) RuntimeReflection.get(jobs, "cleanup");
        try {
            Class<?> type = Class.forName(ServerJobs.class.getName() + "$Session");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(ServerJobs.class, JobEnvelope.class, android.os.IBinder.class, android.os.IBinder.class);
            constructor.setAccessible(true); Object session = constructor.newInstance(jobs, null, new android.os.Binder(), guard);
            RuntimeReflection.set(session, "startSent", true);
            java.lang.reflect.Method failure = type.getDeclaredMethod("connectionFailed", String.class, Exception.class); failure.setAccessible(true);
            java.lang.reflect.Method close = type.getDeclaredMethod("close"); close.setAccessible(true);
            failure.invoke(session, "DISCONNECTED", new EngineException("Real connection failure"));
            close.invoke(session); failure.invoke(session, "BINDING_DIED", new EngineException("Later duplicate callback"));
            cleanup.shutdown(); assertTrue(cleanup.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, failures.get()); assertEquals("Failure winner was incorrectly replaced by successful release", 0, releases.get());
        } finally { cleanup.shutdownNow(); }
    }
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
        JSONObject first;
        try { first = report(fixture, "job-recovery-first.json", 20000); }
        catch (Exception | AssertionError failure) {
            // The next test schedules the same ID; capture this run before its pending record is replaced.
            try { captureSchedulerFailure(serverDeath, "first", scheduled.getString("nonce")); }
            catch (Exception diagnostic) { failure.addSuppressed(diagnostic); }
            throw failure;
        }
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
            try { captureSchedulerFailure(serverDeath, "second", scheduled.getString("nonce")); }
            catch (Exception diagnostic) { failure.addSuppressed(diagnostic); }
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
    private void captureSchedulerFailure(boolean serverDeath, String phase, String expectedNonce) throws Exception {
        File destination = new File(getInstrumentation().getTargetContext().getFilesDir(), "job-recovery-failure-" + (serverDeath ? "server" : "executor") + ".txt");
        Exception stateFailure = null; String state;
        try { state = schedulerState(phase, expectedNonce).toString(); }
        catch (Exception failure) {
            stateFailure = failure;
            state = "{\"pendingStateFailureClass\":\"" + failure.getClass().getName() + "\"}";
        }
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write((state + "\n").getBytes(StandardCharsets.UTF_8));
            android.os.ParcelFileDescriptor descriptor = getInstrumentation().getUiAutomation().executeShellCommand("dumpsys jobscheduler");
            try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                byte[] bytes = new byte[16384]; int total = 0;
                for (int size; (size = input.read(bytes)) != -1;) {
                    total += size; if (total > 2 * 1024 * 1024) throw new java.io.IOException("Scheduler diagnostic exceeded its bound");
                    output.write(bytes, 0, size);
                }
            }
        } catch (Exception failure) {
            if (stateFailure != null) failure.addSuppressed(stateFailure);
            throw failure;
        }
        if (stateFailure != null) throw stateFailure;
    }
    /** Read-only current OS state; never serializes job extras or the original correlation nonce. */
    private JSONObject schedulerState(String phase, String expectedNonce) throws Exception {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        JobScheduler scheduler = host.getSystemService(JobScheduler.class);
        JSONObject state = new JSONObject(); state.put("phase", phase);
        state.put("capturedElapsedRealtime", SystemClock.elapsedRealtime());
        state.put("proxyPid", ownedPid(host, ":engine_jobs")); state.put("serverPid", ownedPid(host, ":engine_server"));
        org.json.JSONArray matching = new org.json.JSONArray(); int count = 0;
        OsPendingStore store = new OsPendingStore(engine);
        for (JobInfo job : scheduler.getAllPendingJobs()) {
            if (!new ComponentName(host, OsJobService.class).equals(job.getService())) continue;
            JobEnvelope envelope = JobEnvelope.read(job.getExtras(), job.getId(), store);
            if (envelope.original.getId() != 7005 || !"io.github.xmy953538104.mybox.fixture".equals(envelope.packageName)) continue;
            count++; if (matching.length() >= 16) continue;
            JSONObject actual = new JSONObject(); actual.put("physicalId", job.getId()); actual.put("virtualId", envelope.original.getId());
            actual.put("nonceMatchesScheduled", expectedNonce.equals(envelope.original.getExtras().getString("nonce")));
            actual.put("minimumLatencyMillis", job.getMinLatencyMillis()); actual.put("deadlineMillis", job.getMaxExecutionDelayMillis());
            actual.put("requiresCharging", job.isRequireCharging()); actual.put("requiresIdle", job.isRequireDeviceIdle());
            actual.put("requiresBatteryNotLow", job.isRequireBatteryNotLow()); actual.put("requiresStorageNotLow", job.isRequireStorageNotLow());
            actual.put("networkType", job.getNetworkType());
            if (android.os.Build.VERSION.SDK_INT >= 34) actual.put("pendingReason", scheduler.getPendingJobReason(job.getId()));
            matching.put(actual);
        }
        state.put("matchingCount", count); state.put("matching", matching); return state;
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
