package io.github.xmy953538104.mybox;

import android.os.Binder;
import android.os.IBinder;
import android.test.AndroidTestCase;

import top.niunaijun.blackbox.core.system.user.IBUserManagerService;
import top.niunaijun.blackbox.fake.frameworks.BlackManager;

public final class BlackManagerTest extends AndroidTestCase {
    public void testAliveBinderRejectingPingIsLinkedOnlyOnce() {
        TestManager manager = new TestManager();
        FakeBinder binder = new FakeBinder();
        manager.next = binder;
        IBUserManagerService service = manager.getService();
        for (int i = 0; i < 1000; i++) assertSame(service, manager.getService());
        assertSame(binder, service.asBinder());
        assertEquals(1, manager.lookups);
        assertEquals(1, binder.links);
        assertEquals(0, binder.pings);
    }

    public void testDeadBinderIsReplacedAndLateDeathCannotClearReplacement() {
        TestManager manager = new TestManager();
        FakeBinder first = new FakeBinder();
        manager.next = first;
        manager.getService();
        IBinder.DeathRecipient lateDeath = first.recipient;
        first.alive = false;
        FakeBinder second = new FakeBinder();
        manager.next = second;
        IBUserManagerService replacement = manager.getService();
        assertSame(second, replacement.asBinder());
        assertEquals(1, first.unlinks);
        lateDeath.binderDied();
        assertSame(replacement, manager.getService());
        assertEquals(2, manager.lookups);
        assertEquals(1, second.links);
        assertEquals(0, second.unlinks);
        second.alive = false;
        second.recipient.binderDied();
        FakeBinder third = new FakeBinder();
        manager.next = third;
        assertSame(third, manager.getService().asBinder());
        assertEquals(3, manager.lookups);
    }

    public void testMissingDeadOrWrongInterfaceFailsExplicitly() {
        TestManager manager = new TestManager();
        assertUnavailable(manager);
        assertEquals(1, manager.lookups);
        FakeBinder dead = new FakeBinder();
        dead.alive = false;
        manager.next = dead;
        assertUnavailable(manager);
        assertEquals(0, dead.links);
        FakeBinder wrong = new FakeBinder();
        wrong.attachInterface(null, "wrong.interface");
        manager.next = wrong;
        assertUnavailable(manager);
        assertEquals(0, wrong.links);
    }

    private static void assertUnavailable(TestManager manager) {
        try { manager.getService(); fail("Unavailable service returned an interface"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("test_user_manager")); }
    }

    private static final class TestManager extends BlackManager<IBUserManagerService> {
        IBinder next;
        int lookups;
        @Override protected String getServiceName() { return "test_user_manager"; }
        @Override protected IBinder lookupService() { lookups++; return next; }
    }

    private static final class FakeBinder extends Binder {
        boolean alive = true;
        int links, unlinks, pings;
        DeathRecipient recipient;
        FakeBinder() { attachInterface(null, IBUserManagerService.class.getName()); }
        @Override public boolean isBinderAlive() { return alive; }
        @Override public boolean pingBinder() { pings++; return false; }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) {
            this.recipient = recipient;
            links++;
        }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            unlinks++;
            if (this.recipient != recipient) return false;
            this.recipient = null;
            return true;
        }
    }
}
