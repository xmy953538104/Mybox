package io.github.xmy953538104.mybox.engine;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Same component, genuinely different AMS Service tokens; no mocked ActivityManager. */
public final class BoundCarrierTokenRuntimeTest extends InstrumentationTestCase {
    static final class Connection implements ServiceConnection {
        final CountDownLatch connected = new CountDownLatch(1);
        volatile IBinder binder;
        @Override public void onServiceConnected(ComponentName name, IBinder value) { binder = value; connected.countDown(); }
        @Override public void onServiceDisconnected(ComponentName name) { }
        IBinder await() throws Exception { if (!connected.await(10, TimeUnit.SECONDS)) throw new AssertionError("No physical Service connection"); return binder; }
    }
    static Intent intent(Context context, String run) {
        return new Intent(context, BoundCarrierTokenProbeService.class).putExtra("run", run).putExtra("server_pid", Process.myPid());
    }
    static Bundle describe(IBinder binder) { return EngineProtocol.request(binder, BoundCarrierTokenProbeService.DESCRIPTOR, BoundCarrierTokenProbeService.DESCRIBE, EngineProtocol.response()); }
    private static Bundle starts(IBinder binder, int valid, int stale) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000; Bundle state;
        do { state = describe(binder); if (state.getInt("valid_starts") == valid && state.getInt("stale_starts") == stale) return state; Thread.sleep(10); }
        while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Unexpected physical start state: " + state);
    }
    public void testOldInstanceStopAndOldOwnerStartCannotStopReusedComponent() throws Exception {
        Context context = getInstrumentation().getTargetContext(); String run = UUID.randomUUID().toString(); Intent component = intent(context, run);
        Connection first = new Connection(), second = new Connection(); boolean firstBound = false, secondBound = false;
        try {
            firstBound = context.bindService(component, first, Context.BIND_AUTO_CREATE); assertTrue(firstBound);
            Bundle old = describe(first.await()); Bundle oldOwner = old.getBundle("owner"); IBinder oldLifecycle = old.getBinder("lifecycle");
            context.startService(new Intent(component).putExtra(BoundServiceWire.EXTRA_OWNER, oldOwner)); starts(first.binder, 1, 0);
            context.unbindService(first); firstBound = false; assertTrue(context.stopService(component));
            File destroyed = new File(context.getFilesDir(), "carrier-token-" + run + "-destroy-" + old.getString("instance"));
            long end = SystemClock.elapsedRealtime() + 10_000;
            while (!destroyed.isFile() && SystemClock.elapsedRealtime() < end) Thread.sleep(10);
            assertTrue("Old real Service instance did not finish destruction", destroyed.isFile());
            secondBound = context.bindService(component, second, Context.BIND_AUTO_CREATE); assertTrue(secondBound);
            Bundle current = describe(second.await()); assertFalse(old.getString("instance").equals(current.getString("instance")));
            assertFalse(BoundServiceWire.sameOwner(oldOwner, current.getBundle("owner")));
            context.startService(new Intent(component).putExtra(BoundServiceWire.EXTRA_OWNER, current.getBundle("owner"))); starts(second.binder, 1, 0);

            // Correct old owner, correct old Binder, actual old mToken: the component name now belongs to another instance.
            Bundle stop = BoundServiceWire.operation("stop"); stop.putBundle("owner", oldOwner);
            BoundServiceWire.call(oldLifecycle, BoundServiceWire.LIFECYCLE, stop);
            EngineProtocol.request(second.binder, BoundCarrierTokenProbeService.DESCRIPTOR, BoundCarrierTokenProbeService.BARRIER, EngineProtocol.response());
            assertEquals(current.getString("instance"), describe(second.binder).getString("instance"));
            try { BoundServiceWire.call(current.getBinder("lifecycle"), BoundServiceWire.LIFECYCLE, stop); fail("New owner accepted old stop authority"); }
            catch (SecurityException expected) { assertTrue(expected.getMessage().contains("another owner")); }

            // AMS really assigns a newer startId to this stale request. It cannot clear the already valid start.
            context.startService(new Intent(component).putExtra(BoundServiceWire.EXTRA_OWNER, oldOwner)); starts(second.binder, 1, 1);
            boolean running = false;
            for (ActivityManager.RunningServiceInfo info : context.getSystemService(ActivityManager.class).getRunningServices(100))
                if (info.service.equals(component.getComponent())) { running = info.started; break; }
            assertTrue("Late old stop/start cleared the new ServiceRecord's started state", running);
            File newDestroyed = new File(context.getFilesDir(), "carrier-token-" + run + "-destroy-" + current.getString("instance"));
            assertFalse("Late old request destroyed the new physical instance", newDestroyed.exists());
            context.startService(new Intent(component).putExtra(BoundServiceWire.EXTRA_OWNER, current.getBundle("owner"))); starts(second.binder, 2, 1);
        } finally {
            try { if (firstBound) context.unbindService(first); }
            finally {
                try { if (secondBound) context.unbindService(second); }
                finally { context.stopService(component); }
            }
        }
    }
}
