package io.github.xmy953538104.mybox.engine;

import android.app.ActivityManager;
import android.content.Context;
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
import java.util.UUID;
import org.json.JSONObject;

/** Exercises actual AMS admission and actual guest publication, including passive pending bindings. */
public final class BoundServiceRuntimeTest extends InstrumentationTestCase {
    public void testUnusedGroupRetirementHandlesConfirmedDeathAndPreservesLiveErrors() throws Exception {
        for (String mode : new String[]{"dead", "transport-dead", "security", "protocol", "transport", "guest", "active", "inactive", "empty"}) {
            android.os.IBinder group = new android.os.Binder() {
                @Override public boolean pingBinder() { return !mode.equals("transport-dead"); }
                @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                        throws android.os.RemoteException {
                    assertEquals(BoundServiceWire.CALL, code); data.enforceInterface(BoundServiceWire.GROUP);
                    assertEquals("state", data.readBundle(getClass().getClassLoader()).getString("operation"));
                    if (mode.equals("dead")) throw new android.os.DeadObjectException();
                    if (mode.startsWith("transport")) throw new android.os.RemoteException("controlled transport failure");
                    if (mode.equals("security")) throw new SecurityException("controlled wrong caller");
                    android.os.Bundle result = mode.equals("protocol") ? new android.os.Bundle() : EngineProtocol.response();
                    if (mode.equals("guest")) result = BoundServiceWire.failure(new EngineException("controlled lifecycle failure"));
                    result.putBoolean("active", !mode.equals("inactive")); result.putInt("count", mode.equals("empty") ? 0 : 1);
                    reply.writeNoException(); reply.writeBundle(result); return true;
                }
            };
            assertTrue("Exercise stale cached liveness", group.isBinderAlive());
            BoundServiceStub carrier = new BoundServiceStub.P0();
            Class<?> type = Class.forName(BoundServiceStub.class.getName() + "$Endpoint");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(BoundServiceStub.class, android.os.IBinder.class);
            constructor.setAccessible(true); Object endpoint = constructor.newInstance(carrier, group);
            java.util.Map endpoints = (java.util.Map) RuntimeReflection.get(carrier, "endpoints"); endpoints.put(group, endpoint);
            java.lang.reflect.Method retire = type.getDeclaredMethod("retireIfUnused"); retire.setAccessible(true);
            boolean expectedError = mode.equals("security") || mode.equals("protocol") || mode.equals("transport") || mode.equals("guest");
            try {
                retire.invoke(endpoint); assertFalse("Live endpoint error was discarded: " + mode, expectedError);
                boolean retired = !mode.equals("active"); assertEquals(retired, RuntimeReflection.get(endpoint, "retired"));
                assertEquals(!retired, endpoints.containsKey(group));
                if (retired) {
                    Object replacement = constructor.newInstance(carrier, group); endpoints.put(group, replacement);
                    retire.invoke(endpoint); assertSame("Old cleanup removed its replacement", replacement, endpoints.get(group));
                }
            } catch (java.lang.reflect.InvocationTargetException failure) {
                assertTrue(mode, expectedError);
                Throwable cause = failure.getCause();
                if (mode.equals("security")) assertTrue(cause instanceof SecurityException);
                else {
                    assertTrue(cause instanceof EngineException);
                    if (mode.equals("transport")) assertTrue(cause.getCause() instanceof android.os.RemoteException);
                    else assertNull("A protocol/lifecycle error became transport death", cause.getCause());
                }
                assertEquals(false, RuntimeReflection.get(endpoint, "retired")); assertSame(endpoint, endpoints.get(group));
            }
        }
    }
    public void testLeaseClosePreservesPhysicalUnbindAndRejectsLiveEndpointErrors() throws Exception {
        for (String mode : new String[]{"dead", "security", "protocol", "transport", "physical"}) {
            java.util.concurrent.atomic.AtomicInteger unbinds = new java.util.concurrent.atomic.AtomicInteger();
            java.io.IOException physical = new java.io.IOException("controlled unbind failure");
            android.os.IBinder lease = new android.os.Binder() {
                @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                        throws android.os.RemoteException {
                    data.enforceInterface(BoundServiceWire.LEASE);
                    android.os.Bundle request = data.readBundle(getClass().getClassLoader());
                    if ("close".equals(request.getString("operation"))) {
                        if (mode.equals("security")) throw new SecurityException("wrong lease owner");
                        if (mode.equals("transport")) throw new android.os.RemoteException("live transport error");
                        if (!mode.equals("protocol")) throw new android.os.DeadObjectException();
                    }
                    reply.writeNoException();
                    reply.writeBundle(mode.equals("protocol") && "close".equals(request.getString("operation"))
                            ? new android.os.Bundle() : EngineProtocol.response());
                    return true;
                }
            };
            BoundServiceConnection connection = new BoundServiceConnection(Engine.get(), null, null, Runnable::run);
            android.content.ComponentName component = new android.content.ComponentName(
                    getInstrumentation().getTargetContext(), "LeaseCleanupFixture");
            android.os.Bundle prepared = EngineProtocol.response(); prepared.putBinder("lease", lease);
            prepared.putInt("pid", android.os.Process.myPid()); prepared.putParcelable("component", component);
            prepared.putParcelable("outer", new android.content.Intent().setComponent(component));
            connection.prepare(prepared, null);
            connection.unbinder(() -> { unbinds.incrementAndGet(); if (mode.equals("physical")) throw physical; });
            connection.accepted(true);
            try {
                connection.close();
                assertEquals("Only confirmed lease death may complete cleanup", "dead", mode);
                assertTrue("Exercise stale cached liveness", lease.isBinderAlive());
                connection.close();
            } catch (SecurityException expected) {
                assertEquals("security", mode);
            } catch (EngineException expected) {
                assertTrue(mode, mode.equals("protocol") || mode.equals("transport"));
                if (mode.equals("transport")) assertTrue(expected.getCause() instanceof android.os.RemoteException);
                else assertTrue(expected.getMessage(), expected.getMessage().contains("incompatible"));
            } catch (java.io.IOException expected) {
                assertEquals("physical", mode); assertSame(physical, expected);
            }
            assertTrue(connection.isClosed()); assertEquals("Registered unbinder must run once", 1, unbinds.get());
        }
    }

    public void testDeadCarrierCleanupRequiresTransportFailureAndConfirmedOldOwnerDeath() {
        android.os.IBinder alive = new android.os.Binder();
        android.os.IBinder dead = new android.os.Binder() { @Override public boolean pingBinder() { return false; } };
        EngineException transport = new EngineException("transport", new android.os.RemoteException("actual Binder transport failure"));
        assertFalse(ServerBoundServices.confirmedDeadCarrierCleanup(alive, transport));
        assertTrue(ServerBoundServices.confirmedDeadCarrierCleanup(dead, transport));
        assertTrue(ServerBoundServices.confirmedDeadCarrierCleanup(alive, new EngineException("dead endpoint", new android.os.DeadObjectException())));
        assertFalse(ServerBoundServices.confirmedDeadCarrierCleanup(dead, new EngineException("guest lifecycle failure")));
        assertFalse(ServerBoundServices.confirmedDeadCarrierCleanup(dead, new SecurityException("wrong caller")));
    }

    public void testPreparedAdmissionActualBinderAndLifetime() throws Exception {
        Context context = getInstrumentation().getTargetContext(); Engine engine = Engine.get();
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            File apk = new File(context.getCacheDir(), "bound-service-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk"); FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] bytes = new byte[16384]; for (int count; (count = input.read(bytes)) != -1;) output.write(bytes, 0, count);
                }
                installed = engine.install(Collections.singletonList(apk));
            } finally { if (apk.exists() && !apk.delete()) throw new java.io.IOException("Cannot remove owned fixture staging APK"); }
        }
        File files = new File(installed.dataDir, "files"); String run = UUID.randomUUID().toString();
        File result = new File(files, "bound-" + run + "-result.json");
        File ready = new File(files, "bound-" + run + "-capacity-ready");
        File ack = new File(files, "bound-" + run + "-capacity-ack");
        FixtureRun.prepare(installed, "bound-services:" + run); engine.launch(name);
        try {
            long deadline = SystemClock.elapsedRealtime() + 90_000;
            while (!ready.isFile() && !result.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            assertTrue("Bound probe failed before actual OS service check: " + read(result), ready.isFile());
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            boolean actual = false; String snapshot = "";
            do {
                int carriers = 0; StringBuilder seen = new StringBuilder();
                for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(100)) {
                    if (!info.service.getClassName().contains(".BoundServiceStub$")) continue;
                    seen.append(info.service).append(" pid=").append(info.pid).append(" clients=").append(info.clientCount).append(" started=").append(info.started).append(';');
                    assertFalse("A real bound carrier must not acquire started-service lifetime: " + seen, info.started);
                    if (info.clientCount >= 5 && info.pid > 0) carriers++;
                }
                snapshot = seen.toString(); actual = carriers == 1;
                if (!actual) Thread.sleep(25);
            } while (!actual && SystemClock.elapsedRealtime() < deadline);
            assertTrue("Five actual guest objects must share one bound carrier: " + snapshot, actual);
            Files.write(ack.toPath(), new byte[]{1});
            File beforeDeath = new File(files, "bound-" + run + "-before-death");
            while (!beforeDeath.isFile() && !result.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            assertTrue("No real group before process death: " + read(result), beforeDeath.isFile());
            BoundCarrierTokenRuntimeTest.Connection observer = new BoundCarrierTokenRuntimeTest.Connection();
            boolean observing = context.bindService(BoundCarrierTokenRuntimeTest.intent(context, run), observer, Context.BIND_AUTO_CREATE);
            assertTrue(observing);
            try {
                android.os.Bundle select = EngineProtocol.response(); select.putParcelable("component", new android.content.ComponentName(name, name + ".BoundServiceProbe$One"));
                android.os.Bundle oldGroup = EngineProtocol.request(observer.await(), BoundCarrierTokenProbeService.DESCRIPTOR, BoundCarrierTokenProbeService.GROUP, select);
                Files.write(new File(files, "bound-" + run + "-before-death-ack").toPath(), new byte[]{1});
                File afterDeath = new File(files, "bound-" + run + "-after-death");
                while (!afterDeath.isFile() && !result.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
                assertTrue("No new owner after process death: " + read(result), afterDeath.isFile());
                android.os.Bundle newGroup = EngineProtocol.request(observer.binder, BoundCarrierTokenProbeService.DESCRIPTOR, BoundCarrierTokenProbeService.GROUP, select);
                assertEquals("Regression must actually reuse the same physical slot", oldGroup.getInt("slot"), newGroup.getInt("slot"));
                assertTrue("Regression must use a new process reservation", oldGroup.getInt("pid") != newGroup.getInt("pid"));
                BoundCarrierTokenRuntimeTest.Connection stale = new BoundCarrierTokenRuntimeTest.Connection();
                android.content.Intent replay = oldGroup.getParcelable("outer");
                replay.setData(android.net.Uri.parse("mybox-bound://stale-replay/" + run)); // Force a fresh physical onBind, not AMS's cached old control.
                boolean rebound = context.bindService(replay, stale, Context.BIND_AUTO_CREATE);
                assertTrue("AMS did not accept real old-group redelivery", rebound);
                try {
                    android.os.IBinder inactive = stale.await();
                    assertEquals(BoundServiceWire.CARRIER, inactive.getInterfaceDescriptor());
                    android.os.Bundle add = BoundServiceWire.operation("add"); add.putBinder("lease", new android.os.Binder());
                    try { BoundServiceWire.call(inactive, BoundServiceWire.CARRIER, add); fail("Retired old group published an active control"); }
                    catch (SecurityException expected) { assertTrue(expected.getMessage().contains("retired")); }
                } finally { context.unbindService(stale); }
                Files.write(new File(files, "bound-" + run + "-after-death-ack").toPath(), new byte[]{1});
            } finally { context.unbindService(observer); }
            while (!result.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            assertTrue("Bound Service probe did not finish", result.isFile());
            JSONObject report = new JSONObject(read(result));
            assertEquals(run, report.getString("run")); assertFalse(report.toString(), report.has("failure")); assertFalse(report.toString(), report.has("cleanupFailure"));
            for (String check : new String[]{"preparedAdmission", "passivePendingThenStart", "sharedFilterAndRebind", "canonicalLifecycleIntent", "existingCarrierPending", "actualNull", "earlyUnbind", "fiveBoundObjects", "newIncarnation", "originalIntent", "negativeBoundaries", "processDeath", "staleGroupSafe", "complete"})
                assertTrue(check + ": " + report, report.getBoolean(check));
        } finally {
            // Always release only this nonce's owned gates; failed assertions cannot strand guest main.
            for (String gate : new String[]{"application-release", "Slow-release", "capacity-ack", "before-death-ack", "after-death-ack"})
                Files.write(new File(files, "bound-" + run + "-" + gate).toPath(), new byte[]{1});
        }
    }
    public void testServerRejectsUnregisteredGroupAndLeaseBinders() throws Exception {
        Engine engine = Engine.get();
        for (String operation : new String[]{"claim_group", "claim_lease", "claim_keeper"}) {
            android.os.Bundle request = BoundServiceWire.operation(operation);
            request.putBinder(operation.equals("claim_group") ? "group" : operation.equals("claim_keeper") ? "keeper" : "lease", new android.os.Binder());
            request.putBinder("endpoint", new android.os.Binder());
            try {
                BoundServiceWire.checked(EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.BOUND_SERVICE, request));
                fail("An app-owned Binder was mistaken for a registered server capability");
            } catch (SecurityException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("Unregistered")); }
        }
    }

    private static String read(File file) throws Exception { return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "no result"; }
}
