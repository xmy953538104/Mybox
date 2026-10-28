package io.github.xmy953538104.mybox.engine;

import android.content.ContentProviderClient;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.test.InstrumentationTestCase;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Checks provider ownership/death races and the actual retained framework reference. */
public final class ProviderConnectionTest extends InstrumentationTestCase {
    public void testRecoveryWaitsForActualProviderCloseAfterTheDeathCallback() throws Exception {
        java.util.concurrent.CountDownLatch closing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger deaths = new AtomicInteger(), closes = new AtomicInteger();
        TestBinder binder = new TestBinder();
        ProviderConnection connection = ProviderConnection.connect(binder, () -> {
            closing.countDown();
            if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Test close was not released");
            closes.incrementAndGet();
        }, ignored -> deaths.incrementAndGet());
        Thread dying = new Thread(binder::die, "MyliteProviderReleaseTest"); dying.start();
        try {
            assertTrue(closing.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, deaths.get()); assertFalse(connection.isAlive());
            assertFalse("Death callback completion falsely proved the framework lease was closed", connection.awaitReleased(20));
        } finally { release.countDown(); dying.join(2000); }
        assertTrue(connection.awaitReleased(1000)); assertEquals(1, closes.get());
        connection.close(); assertEquals(1, closes.get());
        ProviderConnection failed = ProviderConnection.connect(new TestBinder(),
                () -> { throw new java.io.IOException("Intentional provider close failure"); }, ignored -> { });
        failed.close();
        assertFalse("Failed provider close authorized recovery", failed.awaitReleased(20));
    }

    public void testColdOsServiceRefusesAnUnpreparedGuestWithoutCrashingItsProcess() throws Exception {
        android.content.Context context = getInstrumentation().getTargetContext();
        android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
        java.util.Set<String> running = new java.util.HashSet<>();
        for (android.app.ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses())
            if (process.uid == android.os.Process.myUid()) running.add(process.processName);
        int slot = -1;
        for (int candidate = EngineProtocol.SLOT_COUNT - 1; candidate >= 0; candidate--)
            if (!running.contains(context.getPackageName() + ":engine_p" + candidate)) { slot = candidate; break; }
        assertTrue("No cold physical slot exists for this safety check", slot >= 0);
        String name = GuestServiceStub.class.getName() + "$P" + slot + "S3";
        long wall = System.currentTimeMillis();
        android.content.ComponentName component = new android.content.ComponentName(context, name);
        assertEquals(component, context.startService(new android.content.Intent().setComponent(component)));
        String since = String.format(java.util.Locale.ROOT, "%d.%03d", wall / 1000, wall % 1000);
        String marker = "Cold Service refused: " + name;
        String command = "logcat -b main -d -v brief -T " + since + " MyliteService:W *:S";
        long until = android.os.SystemClock.elapsedRealtime() + 10_000;
        boolean refused = false;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    getInstrumentation().getUiAutomation().executeShellCommand(command));
                 java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    assertTrue("Cold Service observation exceeded its bound", bytes.size() + count <= 1024 * 1024);
                    bytes.write(buffer, 0, count);
                }
                refused = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).contains(marker);
            }
            if (refused) break;
            Thread.sleep(50);
        }
        assertTrue("Actual cold Service did not reject missing guest authority", refused);
        boolean started;
        do {
            started = false;
            for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(100))
                started |= component.equals(service.service);
            if (!started) break;
            Thread.sleep(50);
        } while (android.os.SystemClock.elapsedRealtime() < until);
        assertFalse("Refused cold Service remained started", started);
        boolean alive = false;
        for (android.app.ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses())
            alive |= process.uid == android.os.Process.myUid()
                    && (context.getPackageName() + ":engine_p" + slot).equals(process.processName);
        assertTrue("Cold Service refusal crashed the actual process", alive);
    }

    public void testFailedApplicationIsRetiredBeforeItsSlotCanBindAgain() throws Exception {
        Engine engine = Engine.get(); String name = "io.github.xmy953538104.mybox.fixture";
        io.github.xmy953538104.mybox.engine.packages.InstalledPackage installed = engine.packages().get(name);
        assertNotNull("The installed fixture is required", installed);
        java.io.File files = new java.io.File(installed.dataDir, "files");
        java.io.File failedPid = new java.io.File(files, "failed-bootstrap-pid");
        java.nio.file.Files.deleteIfExists(failedPid.toPath());
        java.nio.file.Files.write(new java.io.File(files, "fail-next-bootstrap").toPath(), new byte[]{1});
        android.os.Bundle request = EngineProtocol.response(); request.putString("package", name); request.putString("process", name + ":bootstrap_failure");
        try { EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.ACQUIRE, request); fail("Intentional Application failure bound successfully"); }
        catch (EngineException expected) { assertTrue(expected.toString(), expected.getMessage().contains("CI intentional guest Application bootstrap failure")); }
        assertTrue("Failure did not come from the real guest Application", failedPid.isFile());
        int pid = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(failedPid.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (new java.io.File("/proc/" + pid).exists() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
        assertFalse("Failed guest remained alive", new java.io.File("/proc/" + pid).exists());
        // After actual death, the same virtual process must acquire a fresh VM without restarting the host.
        android.os.Bundle replacement;
        deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        for (;;) {
            try { replacement = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.ACQUIRE, request); break; }
            catch (EngineException lateDeath) {
                if (!lateDeath.getMessage().contains("CI intentional guest Application bootstrap failure")
                        || android.os.SystemClock.elapsedRealtime() >= deadline) throw lateDeath;
                Thread.sleep(20); // Only the already-dead reservation's queued death notification may be pending.
            }
        }
        IBinder client = replacement.getBinder("client"); int replacementPid = replacement.getInt("pid");
        assertNotNull(client); assertTrue(client.isBinderAlive()); assertTrue(replacementPid > 0 && replacementPid != pid);
        java.util.concurrent.CountDownLatch died = new java.util.concurrent.CountDownLatch(1); client.linkToDeath(died::countDown, 0);
        android.os.Process.killProcess(replacementPid);
        assertTrue("Replacement fixture did not retire", died.await(5, java.util.concurrent.TimeUnit.SECONDS));
    }
    public void testFailedPingDoesNotConfirmDeathOfAnUnregisteredLiveBinder() throws Exception {
        ProcessRegistry registry = new ProcessRegistry(Engine.get(), new Binder());
        TestBinder live = new TestBinder();
        assertFalse(live.pingBinder()); assertTrue(live.isBinderAlive());
        assertFalse("Failed ping falsely authorized process reassignment", registry.awaitDeath(live));
        assertFalse("A failed ping alone retired a live VM", ProcessRegistry.confirmedDeadAfterProbe(live));
        TestBinder cached = new TestBinder(); cached.dieDuringPing = true;
        assertTrue(cached.isBinderAlive());
        assertTrue("Fresh IPC did not observe cached Binder death", ProcessRegistry.confirmedDeadAfterProbe(cached));
        assertFalse(cached.isBinderAlive());
        assertFalse(ProcessRegistry.confirmedDeadAfterProbe(null));
    }

    public void testConfirmedDeathWaitsForRetirementAndKeepsTheOriginalDeadline() throws Exception {
        TestBinder live = new TestBinder();
        java.util.concurrent.CountDownLatch complete = new java.util.concurrent.CountDownLatch(0);
        try {
            ProcessRegistry.awaitConfirmedRetirement(live, complete, android.os.SystemClock.elapsedRealtime() + 1000);
            fail("A failed ping authorized retirement of a live VM");
        } catch (EngineException expected) { assertTrue(expected.getMessage().contains("live")); }
        TestBinder dead = new TestBinder(); dead.alive = false;
        java.util.concurrent.CountDownLatch pending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), returned = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            entered.countDown();
            try { ProcessRegistry.awaitConfirmedRetirement(dead, pending, android.os.SystemClock.elapsedRealtime() + 2000); }
            catch (Throwable error) { failure.set(error); }
            finally { returned.countDown(); }
        }, "MyliteRetirementWaitTest");
        waiter.start();
        try {
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse("Actual Binder death skipped ordered retirement", returned.await(30, java.util.concurrent.TimeUnit.MILLISECONDS));
        } finally { pending.countDown(); }
        assertTrue(returned.await(2, java.util.concurrent.TimeUnit.SECONDS)); waiter.join(1000); assertNull(failure.get());
        try {
            ProcessRegistry.awaitConfirmedRetirement(dead, complete, android.os.SystemClock.elapsedRealtime() - 1);
            fail("A new wait budget was created after the original deadline expired");
        } catch (EngineException expected) { assertTrue(expected.getMessage().contains("timed out")); }
        java.util.concurrent.CountDownLatch unfinished = new java.util.concurrent.CountDownLatch(1);
        try {
            ProcessRegistry.awaitConfirmedRetirement(dead, unfinished, android.os.SystemClock.elapsedRealtime() + 25);
            fail("A dead VM was reused before its retirement completed");
        } catch (EngineException expected) { assertEquals(1L, unfinished.getCount()); }
    }

    public void testUnstableLeaseIsRetainedByActualServerConnection() throws Exception {
        Engine engine = Engine.get();
        IBinder first = engine.server();
        ProviderConnection connection = (ProviderConnection) RuntimeReflection.get(engine, "server");
        ContentProviderClient provider = (ContentProviderClient) RuntimeReflection.get(connection, "provider");
        assertFalse("Server lease must not make Android kill the host on server death",
                (Boolean) RuntimeReflection.get(provider, "mStable"));
        assertSame(first, engine.server());
        assertSame(connection, RuntimeReflection.get(engine, "server"));
        assertTrue(connection.isAlive());
        // A retained client must still be usable, not merely an unclosed-looking Java field.
        assertEquals(EngineProtocol.VERSION, provider.call("connect", null,
                EngineProtocol.response()).getInt("protocol"));
    }

    public void testAliveBinderDoesNotRelinkWhenPingIsFalse() throws Exception {
        TestBinder binder = new TestBinder();
        AtomicInteger closes = new AtomicInteger();
        ProviderConnection connection = ProviderConnection.connect(binder, closes::incrementAndGet, ignored -> { });
        for (int i = 0; i < 100; i++) assertTrue(connection.isAlive());
        assertEquals(1, binder.links);
        assertEquals(0, closes.get());
        connection.close();
        connection.close();
        assertEquals(1, binder.unlinks);
        assertEquals(1, closes.get());
        assertFalse(connection.isAlive());
    }

    public void testDeathDuringLinkReleasesLeaseOnceAndCannotPublish() throws Exception {
        TestBinder binder = new TestBinder();
        binder.dieDuringLink = true;
        AtomicInteger closes = new AtomicInteger(), deaths = new AtomicInteger();
        try {
            ProviderConnection.connect(binder, closes::incrementAndGet, ignored -> deaths.incrementAndGet());
            fail("A service that died during linking was published");
        } catch (DeadObjectException expected) { }
        assertEquals(1, deaths.get());
        assertEquals(1, closes.get());
        assertEquals(1, binder.unlinks);
    }

    public void testOldDeathOnlyClosesItsOwnLease() throws Exception {
        AtomicReference<ProviderConnection> current = new AtomicReference<>();
        TestBinder oldBinder = new TestBinder(), newBinder = new TestBinder();
        AtomicInteger oldCloses = new AtomicInteger(), newCloses = new AtomicInteger();
        ProviderConnection old = ProviderConnection.connect(oldBinder, oldCloses::incrementAndGet,
                dead -> current.compareAndSet(dead, null));
        current.set(old);
        ProviderConnection replacement = ProviderConnection.connect(newBinder, newCloses::incrementAndGet,
                dead -> current.compareAndSet(dead, null));
        current.set(replacement);
        oldBinder.die();
        assertSame(replacement, current.get());
        assertEquals(1, oldCloses.get());
        assertEquals(0, newCloses.get());
        oldBinder.death.binderDied();
        old.close();
        assertEquals(1, oldCloses.get());
        replacement.close();
        assertEquals(1, newCloses.get());
    }

    private static final class TestBinder extends Binder {
        boolean alive = true, dieDuringLink, dieDuringPing;
        int links, unlinks;
        DeathRecipient death;
        @Override public boolean pingBinder() { if (dieDuringPing) alive = false; return false; }
        @Override public boolean isBinderAlive() { return alive; }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) {
            links++;
            death = recipient;
            if (dieDuringLink) die();
        }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            assertSame(death, recipient);
            unlinks++;
            return true;
        }
        void die() { alive = false; death.binderDied(); }
    }
}
