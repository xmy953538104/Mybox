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
                    seen.append(info.service).append(" pid=").append(info.pid).append(" clients=").append(info.clientCount).append(';');
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
            for (String check : new String[]{"preparedAdmission", "passivePendingThenStart", "sharedFilterAndRebind", "existingCarrierPending", "actualNull", "earlyUnbind", "fiveBoundObjects", "newIncarnation", "originalIntent", "negativeBoundaries", "processDeath", "staleGroupSafe", "complete"})
                assertTrue(check + ": " + report, report.getBoolean(check));
        } finally {
            // Always release only this nonce's owned gates; failed assertions cannot strand guest main.
            for (String gate : new String[]{"application-release", "Slow-release", "capacity-ack", "before-death-ack", "after-death-ack"})
                Files.write(new File(files, "bound-" + run + "-" + gate).toPath(), new byte[]{1});
        }
    }
    public void testServerRejectsUnregisteredGroupAndLeaseBinders() throws Exception {
        Engine engine = Engine.get();
        for (String operation : new String[]{"claim_group", "claim_lease"}) {
            android.os.Bundle request = BoundServiceWire.operation(operation);
            request.putBinder(operation.equals("claim_group") ? "group" : "lease", new android.os.Binder());
            request.putBinder("endpoint", new android.os.Binder());
            try {
                BoundServiceWire.checked(EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.BOUND_SERVICE, request));
                fail("An app-owned Binder was mistaken for a registered server capability");
            } catch (SecurityException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("Unregistered")); }
        }
    }

    private static String read(File file) throws Exception { return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "no result"; }
}
