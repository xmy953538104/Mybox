package io.github.xmy953538104.mybox.engine;

import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** A separate process group: occupy every VM and exercise the last real Activity/Service stubs. */
public final class ProcessCapacityRuntimeTest extends InstrumentationTestCase {
    public void testEightActualGuestsAndLastSlotComponentsSurviveOverflowAndPeerDeath() throws Exception {
        Engine engine = Engine.get(); String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(name); assertNotNull("Core group must install the current fixture first", installed);
        ArrayList<Bundle> guests = new ArrayList<>(); HashSet<Integer> pids = new HashSet<>();
        try {
            for (int slot = 0; slot < EngineProtocol.SLOT_COUNT; slot++) {
                Bundle guest = acquire(engine, name, slot == EngineProtocol.SLOT_COUNT - 1 ? name : name + ":capacity_" + slot);
                guests.add(guest); assertEquals(slot, guest.getInt("slot"));
                assertTrue(pids.add(guest.getInt("pid"))); assertTrue(guest.getBinder("client").isBinderAlive());
                assertEquals("BOUND", guest.getBundle("runtime").getString("state"));
            }
            Bundle last = guests.get(guests.size() - 1);
            for (String kind : new String[]{"activity", "service"}) Files.deleteIfExists(new File(installed.dataDir, "files/capacity-" + kind + ".json").toPath());
            FixtureRun.prepare(installed, "capacity"); engine.launch(name);
            for (String kind : new String[]{"activity", "service"}) {
                File report = new File(installed.dataDir, "files/capacity-" + kind + ".json");
                long deadline = SystemClock.elapsedRealtime() + 15000;
                while (!report.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
                assertTrue("Last slot did not create its actual " + kind, report.isFile());
                JSONObject result = new JSONObject(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
                assertEquals(name, result.getString("package")); assertEquals(last.getInt("pid"), result.getInt("pid"));
            }
            try { acquire(engine, name, name + ":overflow"); fail("An occupied VM was reassigned"); }
            catch (EngineException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("All " + EngineProtocol.SLOT_COUNT + " guest slots are occupied")); }
            for (Bundle guest : guests) assertTrue("Overflow killed an existing VM", guest.getBinder("client").isBinderAlive());
            Bundle first = guests.get(0); IBinder old = first.getBinder("client"); CountDownLatch dead = new CountDownLatch(1);
            old.linkToDeath(dead::countDown, 0); android.os.Process.killProcess(first.getInt("pid"));
            assertTrue(dead.await(5, TimeUnit.SECONDS));
            Bundle replacement; long deadline = SystemClock.elapsedRealtime() + 5000;
            for (;;) {
                try { replacement = acquire(engine, name, name + ":replacement"); break; }
                catch (EngineException pendingDeath) {
                    if (!pendingDeath.getMessage().contains("guest slots are occupied") || SystemClock.elapsedRealtime() >= deadline) throw pendingDeath;
                    Thread.sleep(20);
                }
            }
            guests.add(replacement); assertEquals(0, replacement.getInt("slot"));
            assertTrue(replacement.getInt("pid") != first.getInt("pid")); assertTrue(replacement.getLong("generation") > first.getLong("generation"));
            for (int i = 1; i < EngineProtocol.SLOT_COUNT; i++) assertTrue("Reusing the dead slot retired a different guest", guests.get(i).getBinder("client").isBinderAlive());
        } finally {
            for (Bundle guest : guests) {
                IBinder client = guest.getBinder("client"); if (!client.isBinderAlive()) continue;
                CountDownLatch dead = new CountDownLatch(1);
                try { client.linkToDeath(dead::countDown, 0); android.os.Process.killProcess(guest.getInt("pid")); dead.await(5, TimeUnit.SECONDS); }
                catch (android.os.DeadObjectException alreadyDead) { }
            }
        }
    }
    private static Bundle acquire(Engine engine, String name, String process) {
        Bundle request = EngineProtocol.response(); request.putString("package", name); request.putString("process", process); request.putBoolean("include_runtime_state", true);
        return EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.ACQUIRE, request);
    }
}
