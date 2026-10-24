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
        boolean alive = true, dieDuringLink;
        int links, unlinks;
        DeathRecipient death;
        @Override public boolean pingBinder() { return false; }
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
