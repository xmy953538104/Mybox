package io.github.xmy953538104.mybox;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.test.AndroidTestCase;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.service.context.providers.ContentProviderStub;

public final class ProviderAttributionTest extends AndroidTestCase {
    public interface TestProvider extends IInterface {
        AttributionSource call(AttributionSource source);
    }

    public interface FailingProvider extends IInterface {
        void read() throws RemoteException;
    }

    public void testDeadProviderKeepsRealBinderStateAndException() throws Exception {
        boolean[] alive = {true};
        Binder binder = new Binder() {
            @Override public boolean isBinderAlive() { return alive[0]; }
        };
        FailingProvider target = new FailingProvider() {
            @Override public IBinder asBinder() { return binder; }
            @Override public void read() throws RemoteException { throw new DeadObjectException(); }
        };
        FailingProvider wrapped = (FailingProvider) new ContentProviderStub().wrapper(target, "fixture.test");
        assertSame(binder, wrapped.asBinder());
        assertTrue(wrapped.asBinder().isBinderAlive());
        try { wrapped.read(); fail("Dead provider exception was swallowed"); }
        catch (DeadObjectException expected) { }
        alive[0] = false;
        assertFalse(wrapped.asBinder().isBinderAlive());
    }

    public void testVirtualProviderUsesKernelAttributionAndPreservesBinder() {
        if (Build.VERSION.SDK_INT < 31) return;
        Binder binder = new Binder();
        TestProvider target = new TestProvider() {
            @Override public IBinder asBinder() { return binder; }
            @Override public AttributionSource call(AttributionSource source) {
                if (source == null) return null;
                assertEquals(BlackBoxCore.getHostUid(), source.getUid());
                assertEquals(BlackBoxCore.getHostPkg(), source.getPackageName());
                return source;
            }
        };
        TestProvider wrapped = (TestProvider) new ContentProviderStub().wrapper(target, BlackBoxCore.getHostPkg());
        assertSame(binder, wrapped.asBinder());
        AttributionSource guest = new AttributionSource.Builder(10002).setPackageName("test.guest").build();
        wrapped.call(guest);
        assertNull(wrapped.call(null));
    }
}
