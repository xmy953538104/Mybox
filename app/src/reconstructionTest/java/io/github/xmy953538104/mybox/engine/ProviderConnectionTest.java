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
