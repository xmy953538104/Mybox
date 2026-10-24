/* SPDX-License-Identifier: Apache-2.0 */
package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.test.InstrumentationTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises an actual remote Binder and an anonymous oneway transaction on the CI emulator. */
public final class NativeBinderIdentityTest extends InstrumentationTestCase {
    private Context context;
    private IBinder endpoint;
    private boolean bound;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            endpoint = binder; connected.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { endpoint = null; }
    };

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        Intent intent = new Intent().setComponent(new ComponentName(context.getPackageName(),
                NativeIdentityProbeService.class.getName()));
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        assertTrue("Debug identity service did not bind", bound);
        assertTrue("Debug identity service did not connect", connected.await(15, TimeUnit.SECONDS));
        Bundle capability = request(NativeIdentityProbeService.DESCRIBE);
        android.util.Log.i("MyboxIdentityTest", "MYBOX_NATIVE_IDENTITY_CAPABILITY=" + capability);
        assertTrue(capability.toString(), capability.getBoolean("installed"));
        assertEquals(capability.toString(), 0, capability.getInt("errno"));
        assertTrue(capability.toString(), capability.getInt("entryOffset", -1) >= 0);
        assertEquals(capability.toString(), Process.myUid(), capability.getInt("selfUid"));
        assertEquals(capability.toString(), capability.getInt("processPid"), capability.getInt("selfPid"));
        assertTrue(capability.toString(), capability.getInt("processPid") != Process.myPid());
    }

    @Override protected void tearDown() throws Exception {
        try { if (bound) context.unbindService(connection); }
        finally { super.tearDown(); }
    }

    public void testSynchronousScopesPreserveRawIdentity() throws Exception {
        Bundle report = request(NativeIdentityProbeService.EXERCISE);
        android.util.Log.i("MyboxIdentityTest", "MYBOX_NATIVE_IDENTITY_SYNC=" + report);
        assertFalse(report.toString(), report.containsKey("failure"));
        assertEquals(report.toString(), Process.myUid(), report.getInt("rawUid"));
        assertEquals(report.toString(), Process.myPid(), report.getInt("rawPid"));
        assertEquals(report.toString(), Process.myUid(), report.getInt("afterUid"));
        for (String key : new String[]{"mappedWithRawPreserved", "systemBoundaryRaw", "nestedScopes",
                "clearRestore", "workSourceIgnored", "threadIsolation", "exceptionCleanup",
                "invalidScopesRejected", "misorderedCloseFailsClosed"}) {
            assertTrue(key + ": " + report, report.getBoolean(key));
        }
        assertEquals(Process.myUid(), Binder.getCallingUid());
    }

    public void testOnewayHasNoInventedCaller() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        Bundle[] reports = new Bundle[1];
        Binder callback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != NativeIdentityProbeService.REPORT) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(NativeIdentityProbeService.DESCRIPTOR);
                reports[0] = data.readBundle();
                reply.writeNoException(); received.countDown(); return true;
            }
        };
        Parcel request = Parcel.obtain();
        try {
            request.writeInterfaceToken(NativeIdentityProbeService.DESCRIPTOR);
            request.writeStrongBinder(callback);
            assertTrue(endpoint.transact(NativeIdentityProbeService.ONEWAY, request, null, IBinder.FLAG_ONEWAY));
        } finally { request.recycle(); }
        assertTrue("Oneway callback timed out", received.await(15, TimeUnit.SECONDS));
        Bundle report = reports[0];
        android.util.Log.i("MyboxIdentityTest", "MYBOX_NATIVE_IDENTITY_ONEWAY=" + report);
        assertNotNull(report);
        assertFalse(report.toString(), report.containsKey("failure"));
        assertEquals(report.toString(), Process.myUid(), report.getInt("rawUid"));
        assertEquals(report.toString(), Process.myUid(), report.getInt("visibleUid"));
        assertEquals(report.toString(), 0, report.getInt("rawPid"));
        assertTrue(report.toString(), report.getBoolean("anonymousScopeRejected"));
    }

    private Bundle request(int code) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(NativeIdentityProbeService.DESCRIPTOR);
            assertNotNull(endpoint);
            assertTrue(endpoint.transact(code, data, reply, 0));
            reply.readException();
            return reply.readBundle();
        } finally { reply.recycle(); data.recycle(); }
    }
}
