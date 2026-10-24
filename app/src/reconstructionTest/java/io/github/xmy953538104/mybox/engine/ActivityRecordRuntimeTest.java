package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONObject;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Real ActivityThread construction, result delivery and private Binder ownership boundaries. */
public final class ActivityRecordRuntimeTest extends InstrumentationTestCase {
    private static final String PKG = "io.github.xmy953538104.mybox.fixture";
    private static final String ROOT = PKG + ".FixtureActivity";
    private static final String LOCAL = PKG + ".activities.SameSlotActivity";
    private static final String REMOTE = PKG + ".activities.RecordProbeActivity";

    public void testStandardRecordsClaimsResultsAndFinishedCleanup() throws Exception {
        InstalledPackage installed = fixture();
        for (String name : new String[]{"activity-result.json", "activity-local.json", "activity-remote.json",
                "activity-local-destroyed.json", "activity-remote-destroyed.json", "activity-new-intent.json", "activity-finish"}) {
            Files.deleteIfExists(new File(installed.dataDir, "files/" + name).toPath());
        }
        Bundle reserved = prepare(newRoot(REMOTE));
        Bundle launch = null;
        try {
            FixtureRun.prepare(installed, "activities");
            Intent original = newRoot(ROOT).putExtra("activity_test_reserved", reserved)
                    .putExtra("activity_test_host", getInstrumentation().getTargetContext().getPackageName());
            launch = prepare(original);
            getInstrumentation().getTargetContext().startActivity(launch.getParcelable("outer_intent"));
            outcome(launch, true);

            File files = new File(installed.dataDir, "files");
            JSONObject local = await(new File(files, "activity-local.json"));
            JSONObject remote = await(new File(files, "activity-remote.json"));
            assertEquals(local.toString(), LOCAL, local.getString("class"));
            assertEquals(remote.toString(), REMOTE, remote.getString("class"));
            assertEquals(PKG, local.getString("package"));
            assertEquals(PKG, remote.getString("package"));
            assertEquals(ROOT, local.getString("callingActivity"));
            assertEquals(LOCAL, remote.getString("callingActivity"));
            assertEquals(PKG, local.getString("callingPackage"));
            assertEquals(PKG, remote.getString("callingPackage"));
            assertEquals(local.getInt("pid"), remote.getInt("echoPid"));
            assertEquals(local.getInt("pid"), local.getInt("echoPid"));
            assertTrue(remote.toString(), local.getInt("pid") != remote.getInt("pid"));
            assertEquals(local.getInt("task"), remote.getInt("task"));
            assertFalse(local.getString("instance").equals(remote.getString("instance")));

            Bundle rootRecord = find(inspect(), ROOT, launch.getLong("record_id"));
            Bundle localRecord = find(inspect(), LOCAL, -1);
            Bundle remoteRecord = awaitState(REMOTE, "RESUMED");
            assertNotNull("Root Activity has no claimed server record", rootRecord);
            assertNotNull("Same-slot Activity has no claimed server record", localRecord);
            assertEquals(rootRecord.getInt("slot"), localRecord.getInt("slot"));
            assertTrue(localRecord.getInt("slot") != remoteRecord.getInt("slot"));
            assertEquals(local.getInt("task"), rootRecord.getInt("task_id"));
            assertEquals(local.getInt("task"), localRecord.getInt("task_id"));
            assertEquals(remote.getInt("task"), remoteRecord.getInt("task_id"));
            assertTrue(remoteRecord.getLong("last_event") >= 3);
            assertTrue(localRecord.getLong("record_id") != rootRecord.getLong("record_id"));
            assertTrue(remoteRecord.getLong("record_id") != localRecord.getLong("record_id"));

            // This A regression also exercises a NewIntentItem originating at the physical task boundary.
            android.app.ActivityManager manager = (android.app.ActivityManager) getInstrumentation().getTargetContext()
                    .getSystemService(android.content.Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.AppTask selected = null;
            for (android.app.ActivityManager.AppTask task : manager.getAppTasks()) {
                if (task.getTaskInfo().id == remoteRecord.getInt("task_id")) selected = task;
            }
            assertNotNull("Android did not expose the host-owned fixture task", selected);
            Intent redelivery = ActivityEnvelope.create(getInstrumentation().getTargetContext(),
                    new ComponentName(PKG, REMOTE), Intent.FLAG_ACTIVITY_SINGLE_TOP, remoteRecord);
            selected.startActivity(getInstrumentation().getTargetContext(), redelivery, null);
            JSONObject delivered = await(new File(files, "activity-new-intent.json"));
            assertEquals(remote.getString("instance"), delivered.getString("instance"));
            assertEquals(1, delivered.getInt("count"));
            assertEquals(remote.getInt("task"), delivered.getInt("task"));
            long acknowledgementDeadline = SystemClock.elapsedRealtime() + 10000;
            do {
                remoteRecord = find(inspect(), REMOTE, remoteRecord.getLong("record_id"));
                if (remoteRecord.getInt("new_intents") == 1) break;
                Thread.sleep(50);
            } while (SystemClock.elapsedRealtime() < acknowledgementDeadline);
            assertEquals("Framework new Intent did not receive a completion ACK", 1, remoteRecord.getInt("new_intents"));

            long destroyed = inspect().getLong("destroyed_count");
            Files.write(new File(files, "activity-finish").toPath(), new byte[]{1});
            JSONObject result = await(new File(files, "activity-result.json"));
            assertEquals(result.toString(), -1, result.getInt("resultCode"));
            assertEquals(428, result.getInt("resultRequest"));
            assertEquals(local.getInt("pid"), result.getInt("resultRootPid"));
            assertTrue(result.toString(), result.getBoolean("wrongOwnerRejected"));
            assertTrue(result.toString(), result.getBoolean("wrongGenerationRejected"));
            assertTrue(result.toString(), result.getBoolean("forgedRecordRejected"));
            assertTrue(remote.toString(), remote.getBoolean("fakePhysicalTokenRejected"));
            assertTrue(remote.toString(), remote.getBoolean("claimedTokenReuseRejected"));
            assertEquals(local.getString("instance"), await(new File(files, "activity-local-destroyed.json")).getString("instance"));
            assertEquals(remote.getString("instance"), await(new File(files, "activity-remote-destroyed.json")).getString("instance"));
            Bundle after = inspect();
            long cleanupDeadline = SystemClock.elapsedRealtime() + 10000;
            while ((find(after, LOCAL, localRecord.getLong("record_id")) != null
                    || find(after, REMOTE, remoteRecord.getLong("record_id")) != null)
                    && SystemClock.elapsedRealtime() < cleanupDeadline) {
                Thread.sleep(50); after = inspect();
            }
            assertNull(find(after, LOCAL, localRecord.getLong("record_id")));
            assertNull(find(after, REMOTE, remoteRecord.getLong("record_id")));
            assertTrue(after.getLong("destroyed_count") >= destroyed + 2);
            android.util.Log.i("MyliteActivities", "ACTIVITY_RECORD_REPORT=" + result);
        } finally {
            outcome(reserved, false);
            Files.write(new File(installed.dataDir, "files/activity-finish").toPath(), new byte[]{1});
        }
    }

    public void testGenerationCancellationForgedRecordsAndUnsupportedTaskGate() throws Exception {
        fixture();
        Bundle first = prepare(newRoot(ROOT));
        Bundle second = prepare(newRoot(LOCAL));
        try {
            Intent a = first.getParcelable("outer_intent"), b = second.getParcelable("outer_intent");
            assertEquals(a.getComponent(), b.getComponent());
            assertFalse("Different guest components collided at the physical filter", a.filterEquals(b));
            assertNotNull(first.getBinder(ActivityEnvelope.RECORD));
            assertFalse(first.getBinder(ActivityEnvelope.RECORD).equals(second.getBinder(ActivityEnvelope.RECORD)));
            Bundle wrong = new Bundle(first);
            wrong.putLong("generation", first.getLong("generation") + 1);
            rejected(ActivityEnvelope.operation(wrong, "start_result", null), "generation");
            wrong = new Bundle(first);
            wrong.putString("package_generation", "obsolete");
            rejected(ActivityEnvelope.operation(wrong, "start_result", null), "generation");
            wrong = new Bundle(first);
            wrong.putBinder(ActivityEnvelope.RECORD, new Binder());
            rejected(ActivityEnvelope.operation(wrong, "start_result", null), "unknown");
            rejected(ActivityEnvelope.operation(first, "claim", new Binder()), "cannot claim");
        } finally { outcome(first, false); outcome(second, false); }
        rejected(ActivityEnvelope.operation(first, "start_result", null), "unknown");
        for (Intent invalid : new Intent[]{newRoot(PKG + ".activities.UnsupportedTaskActivity"),
                newRoot(ROOT).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                newRoot(ROOT).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK),
                newRoot(ROOT).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)}) {
            Bundle request = EngineProtocol.response();
            request.putString("operation", "prepare"); request.putParcelable("intent", invalid);
            rejected(request, "not implemented");
        }
    }

    private InstalledPackage fixture() throws Exception {
        Engine engine = Engine.get();
        InstalledPackage installed = engine.packages().get(PKG);
        if (installed != null) return installed;
        File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "activity-record-fixture.apk");
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                 FileOutputStream output = new FileOutputStream(apk)) {
                byte[] buffer = new byte[16384];
                for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            }
            return engine.install(Collections.singletonList(apk));
        } finally { apk.delete(); }
    }

    private static Intent newRoot(String activity) {
        return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(PKG, activity))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    private static Bundle host(Bundle input) {
        return EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, input);
    }

    private static Bundle prepare(Intent original) {
        Bundle input = EngineProtocol.response();
        input.putString("operation", "prepare"); input.putParcelable("intent", original);
        return host(input);
    }

    private static void outcome(Bundle record, boolean accepted) {
        Bundle input = ActivityEnvelope.operation(record, "start_result", null);
        input.putBoolean("accepted", accepted); host(input);
    }

    private static Bundle inspect() {
        Bundle input = EngineProtocol.response(); input.putString("operation", "inspect"); return host(input);
    }

    private static Bundle find(Bundle snapshot, String activity, long id) {
        ArrayList<Bundle> records = snapshot.getParcelableArrayList("records");
        for (Bundle record : records) if (activity.equals(record.getString("activity"))
                && record.getInt("task_id", -1) >= 0 && (id < 0 || record.getLong("record_id") == id)) return record;
        return null;
    }

    private Bundle awaitState(String activity, String state) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        Bundle record;
        do {
            record = find(inspect(), activity, -1);
            if (record != null && state.equals(record.getString("state"))) return record;
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Activity did not acknowledge " + state + ": " + activity + ", " + record);
        return null;
    }

    private JSONObject await(File report) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        File failure = new File(report.getParentFile(), "activity-result.json");
        while (!report.isFile() && SystemClock.elapsedRealtime() < deadline) {
            if (failure.isFile()) {
                JSONObject error = new JSONObject(new String(Files.readAllBytes(failure.toPath()), StandardCharsets.UTF_8));
                assertFalse(error.toString(), error.has("failure"));
            }
            Thread.sleep(50);
        }
        assertTrue("Activity did not write " + report.getName(), report.isFile());
        JSONObject result = new JSONObject(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
        assertFalse(result.toString(), result.has("failure")); return result;
    }

    private void rejected(Bundle request, String reason) {
        try { host(request); fail("Invalid Activity request was accepted"); }
        catch (EngineException expected) { assertTrue(expected.toString(), expected.getMessage().contains(reason)); }
    }
}
