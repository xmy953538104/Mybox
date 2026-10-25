package io.github.xmy953538104.mybox.engine.accounts;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.JobRuntimeTest;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class AuthSessionErrorRuntimeTest extends InstrumentationTestCase {
    public void testNormalAndConsumerDeathCloseReleaseResourcesDespiteUiAndUnlinkFailure() throws Exception {
        Semaphore limit = (Semaphore) field("LIMIT"); ThreadPoolExecutor work = (ThreadPoolExecutor) field("WORK");
        for (boolean byDeath : new boolean[]{false, true}) {
            int baseline = limit.availablePermits(); AtomicInteger unlinks = new AtomicInteger(), unbinds = new AtomicInteger(), uiCloses = new AtomicInteger();
            assertTrue("No capacity for the normal/death cleanup probe", baseline > 0);
            java.util.concurrent.atomic.AtomicReference<IBinder.DeathRecipient> registered = new java.util.concurrent.atomic.AtomicReference<>();
            android.os.Binder consumer = new android.os.Binder() {
                @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) { registered.set(recipient); }
                @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
                    unlinks.incrementAndGet(); throw new java.util.NoSuchElementException("Probe missing death registration");
                }
            };
            IVirtualAccountResponse response = new IVirtualAccountResponse() {
                @Override public IBinder asBinder() { return consumer; }
                @Override public void onResult(Bundle result) { fail("Close must not manufacture an account result"); }
                @Override public void onError(int code, String message) { fail("Normal/death cleanup must not manufacture an error"); }
            };
            AccountUiAuthorizer.Scope ui = new AccountUiAuthorizer.Scope() {
                @Override public Intent publish(io.github.xmy953538104.mybox.engine.CallerIdentity owner, Intent value) { throw new AssertionError(); }
                @Override public void close(boolean retain) { uiCloses.incrementAndGet(); throw new IllegalStateException("Probe UI cleanup failure"); }
            };
            AuthSession session = session(response, (component, user, connection) -> () -> unbinds.incrementAndGet(), ui);
            try {
                session.start(); awaitPriorWork(work); assertNotNull(registered.get()); assertEquals(baseline - 1, limit.availablePermits());
                if (byDeath) registered.get().binderDied(); else close(session, true);
                assertEquals(1, uiCloses.get()); assertEquals(1, unlinks.get()); assertEquals(1, unbinds.get());
                assertEquals("Cleanup exception leaked normal/death capacity", baseline, limit.availablePermits());
                close(session, false);
                assertEquals("Repeated close must still attempt UI revocation", 2, uiCloses.get());
                assertEquals(1, unlinks.get()); assertEquals(1, unbinds.get()); assertEquals(baseline, limit.availablePermits());
            } finally { close(session, false); }
        }
    }

    public void testRepeatedConsumerCloseRevokesDetachedUiWithoutDoubleRelease() throws Exception {
        Semaphore limit = (Semaphore) field("LIMIT"); int baseline = limit.availablePermits();
        assertTrue("No capacity for the detached UI cleanup probe", baseline > 0);
        AtomicBoolean detached = new AtomicBoolean(); AtomicInteger uiCloses = new AtomicInteger();
        AccountUiAuthorizer.Scope ui = new AccountUiAuthorizer.Scope() {
            @Override public Intent publish(io.github.xmy953538104.mybox.engine.CallerIdentity owner, Intent value) { return value; }
            @Override public void close(boolean retain) { uiCloses.incrementAndGet(); detached.set(retain); }
        };
        IVirtualAccountResponse response = new IVirtualAccountResponse.Stub() {
            @Override public void onResult(Bundle result) { fail("No account result expected"); }
            @Override public void onError(int code, String message) { fail("No account error expected"); }
        };
        AuthSession session = session(response, (component, user, connection) -> () -> { }, ui);
        try {
            session.start(); awaitPriorWork((ThreadPoolExecutor) field("WORK"));
            close(session, true); assertTrue(detached.get()); assertEquals(baseline, limit.availablePermits());
            java.lang.reflect.Field death = AuthSession.class.getDeclaredField("consumerDeath"); death.setAccessible(true);
            ((IBinder.DeathRecipient) death.get(session)).binderDied();
            assertFalse("Later consumer death left the returned UI detached", detached.get()); assertEquals(2, uiCloses.get());
            assertEquals("Repeated consumer close released another session's permit", baseline, limit.availablePermits());
        } finally { close(session, false); }
    }
    public void testBlockedRealConsumerErrorsDoNotBlockTheSameServerJobConnection() throws Exception {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        InstalledPackage fixture = engine.packages().get("io.github.xmy953538104.mybox.fixture"); assertNotNull(fixture);
        String run = java.util.UUID.randomUUID().toString(), prefix = "account-error-" + run + "-";
        File control = new File(fixture.dataDir, "files/account-error-null-run");
        File release = new File(fixture.dataDir, "files/" + prefix + "release.json"); Throwable original = null;
        boolean installedControl = false;
        try {
            assertFalse("An earlier null-binding control was left active", control.exists());
            Files.write(control.toPath(), run.getBytes(StandardCharsets.UTF_8));
            installedControl = true;
            FixtureRun.prepare(fixture, "account-error-gate:" + run); engine.launch(fixture.packageName);
            JSONObject first = report(fixture, prefix + "entered-0.json", 15000);
            JSONObject second = report(fixture, prefix + "entered-1.json", 15000);
            assertFalse(first.toString(), first.has("failure")); assertFalse(second.toString(), second.has("failure"));
            int server = serverPid(host); assertTrue(server > 0);
            assertEquals(server, first.getInt("serverPid")); assertEquals(server, second.getInt("serverPid"));
            assertTrue("Consumer must be in a real guest process", first.getInt("pid") != server && first.getInt("pid") != android.os.Process.myPid());
            JSONObject nullBound = report(fixture, prefix + "null-bound.json", 1000);
            assertTrue("Authenticator null binding must come from its separate guest process", nullBound.getInt("pid") != first.getInt("pid"));
            assertFalse("Consumer callbacks returned before the connection probe", new File(fixture.dataDir, "files/" + prefix + "returned-0.json").exists());
            assertFalse(new File(fixture.dataDir, "files/" + prefix + "returned-1.json").exists());
            // Reuse the actual OS JobService fixture, not a host-local copy of the server callback executor.
            JobRuntimeTest jobs = new JobRuntimeTest(); jobs.injectInstrumentation(getInstrumentation());
            if (android.os.Build.VERSION.SDK_INT >= 31) jobs.testExpeditedJobExecutesWithActualAndroidStatus();
            else jobs.testAndroidOwnsSchedulingAndTwoActualWorkItems();
            assertEquals("Actual engine server changed during the blocked-consumer probe", server, serverPid(host));
            assertFalse("Error gate timed out rather than staying blocked", new File(fixture.dataDir, "files/" + prefix + "returned-0.json").exists());
            assertFalse(new File(fixture.dataDir, "files/" + prefix + "returned-1.json").exists());
        } catch (Exception | AssertionError failure) { original = failure; throw failure; }
        finally {
            Throwable cleanup = null;
            try { Files.write(release.toPath(), new byte[]{1}); } catch (Exception failure) { cleanup = failure; }
            try {
                if (installedControl && control.exists()) {
                    if (!run.equals(new String(Files.readAllBytes(control.toPath()), StandardCharsets.UTF_8)) || !control.delete()) throw new java.io.IOException("Cannot restore this run's normal authenticator binding");
                }
            }
            catch (Exception failure) { if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure); }
            for (int index = 0; index < 2; index++) {
                if (!new File(fixture.dataDir, "files/" + prefix + "entered-" + index + ".json").isFile()) continue;
                try {
                    JSONObject returned = report(fixture, prefix + "returned-" + index + ".json", 3000);
                    assertFalse(returned.toString(), returned.has("failure")); assertEquals(1, returned.getInt("invocations"));
                } catch (Exception | AssertionError failure) { if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure); }
            }
            if (cleanup != null) { if (original != null) original.addSuppressed(cleanup); else if (cleanup instanceof Exception) throw (Exception) cleanup; else throw (AssertionError) cleanup; }
        }
    }

    public void testTerminalErrorKeepsCapacityAndIgnoresLateConnectionDespiteCleanupFailure() throws Exception {
        Semaphore limit = (Semaphore) field("LIMIT"); int baseline = limit.availablePermits(); assertTrue(baseline > 0);
        ThreadPoolExecutor work = (ThreadPoolExecutor) field("WORK");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(), endpointCalls = new AtomicInteger(), closes = new AtomicInteger();
        AtomicBoolean gateExpired = new AtomicBoolean();
        IVirtualAccountResponse response = new IVirtualAccountResponse.Stub() {
            @Override public void onResult(Bundle value) { fail("Terminal error became a success result"); }
            @Override public void onError(int code, String message) {
                assertEquals(android.accounts.AccountManager.ERROR_CODE_REMOTE_EXCEPTION, code);
                errors.incrementAndGet(); entered.countDown();
                try { if (!release.await(8, TimeUnit.SECONDS)) gateExpired.set(true); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); gateExpired.set(true); }
                finally { returned.countDown(); }
            }
        };
        AuthenticatorRouter router = (component, user, connection) -> () -> { closes.incrementAndGet(); throw new IllegalStateException("Probe cleanup failure"); };
        AccountUiAuthorizer.Scope ui = new AccountUiAuthorizer.Scope() {
            @Override public Intent publish(io.github.xmy953538104.mybox.engine.CallerIdentity owner, Intent value) { throw new AssertionError("No UI result expected"); }
            @Override public void close(boolean retained) { throw new IllegalStateException("Probe UI cleanup failure"); }
        };
        AuthenticatorRegistration registration = new AuthenticatorRegistration("probe.type", "probe.package", "probe.Authenticator",
                Collections.singletonList("probe-signature"), false, 0, 0, 0, 0);
        AuthSession session = new AuthSession("getAuthTokenLabel", new Bundle(), response, null, registration,
                null, null, router, null, new Handler(Looper.getMainLooper()), ui);
        try {
            session.start(); awaitPriorWork(work);
            assertEquals(baseline - 1, limit.availablePermits());
            session.onDisconnected(); assertTrue("Real terminal callback was never invoked", entered.await(3, TimeUnit.SECONDS));
            session.onDisconnected();
            close(session, false);
            assertEquals("Repeated normal close released a pending error notification permit", baseline - 1, limit.availablePermits());
            IBinder forbidden = (IBinder) java.lang.reflect.Proxy.newProxyInstance(IBinder.class.getClassLoader(), new Class<?>[]{IBinder.class},
                    (proxy, method, args) -> { endpointCalls.incrementAndGet(); throw new AssertionError("Closed account accessed a late authenticator endpoint"); });
            session.onConnected(forbidden); awaitPriorWork(work);
            assertEquals(0, endpointCalls.get()); assertEquals(1, errors.get()); assertEquals(1, closes.get());
            assertEquals("Capacity was released while synchronous error was still blocked", baseline - 1, limit.availablePermits());
            release.countDown(); assertTrue(returned.await(3, TimeUnit.SECONDS));
            long end = SystemClock.elapsedRealtime() + 3000;
            while (limit.availablePermits() != baseline && SystemClock.elapsedRealtime() < end) Thread.sleep(5);
            assertEquals(baseline, limit.availablePermits()); assertFalse("Error returned only after fixture timeout", gateExpired.get());
        } finally { release.countDown(); session.onDisconnected(); }
    }

    private static void awaitPriorWork(ThreadPoolExecutor work) throws Exception {
        CountDownLatch bothWorkers = new CountDownLatch(2), release = new CountDownLatch(1);
        java.util.ArrayList<java.util.concurrent.Future<?>> fences = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) fences.add(work.submit(() -> {
                bothWorkers.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Account work fence expired"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }));
            assertTrue("Earlier account work did not complete", bothWorkers.await(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        for (java.util.concurrent.Future<?> fence : fences) fence.get(3, TimeUnit.SECONDS);
    }

    private static Object field(String name) throws Exception {
        java.lang.reflect.Field field = AuthSession.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static AuthSession session(IVirtualAccountResponse response, AuthenticatorRouter router, AccountUiAuthorizer.Scope ui) {
        AuthenticatorRegistration registration = new AuthenticatorRegistration("probe.type", "probe.package", "probe.Authenticator",
                Collections.singletonList("probe-signature"), false, 0, 0, 0, 0);
        return new AuthSession("getAuthTokenLabel", new Bundle(), response, null, registration,
                null, null, router, null, new Handler(Looper.getMainLooper()), ui);
    }
    private static void close(AuthSession session, boolean retain) throws Exception {
        java.lang.reflect.Method close = AuthSession.class.getDeclaredMethod("close", boolean.class); close.setAccessible(true); close.invoke(session, retain);
    }
    private static int serverPid(Context host) {
        for (android.app.ActivityManager.RunningAppProcessInfo process : host.getSystemService(android.app.ActivityManager.class).getRunningAppProcesses())
            if (process.uid == android.os.Process.myUid() && (host.getPackageName() + ":engine_server").equals(process.processName)) return process.pid;
        return -1;
    }
    private static JSONObject report(InstalledPackage fixture, String name, long budget) throws Exception {
        File file = new File(fixture.dataDir, "files/" + name); long end = SystemClock.elapsedRealtime() + budget;
        while (!file.isFile() && SystemClock.elapsedRealtime() < end) Thread.sleep(10);
        assertTrue("Missing actual account error receipt " + name, file.isFile());
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}
