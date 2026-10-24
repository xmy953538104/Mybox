package io.github.xmy953538104.mybox.engine;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Predicate;

/** Public guest starts, actual Android task IDs and lifecycle callbacks across two guest processes. */
public final class ActivityTaskRuntimeTest extends InstrumentationTestCase {
    private static final String PKG = "io.github.xmy953538104.mybox.fixture";
    private static final String PREFIX = PKG + ".activities.TaskProbe$";
    private final ArrayList<Bundle> events = new ArrayList<>();
    private final IBinder payload = new Binder();
    private String run;
    private final Binder observer = new Binder() {
        @Override protected boolean onTransact(int code, Parcel input, Parcel output, int flags) {
            if (code != FIRST_CALL_TRANSACTION) return false;
            Bundle event = input.readBundle(getClass().getClassLoader());
            synchronized (events) { events.add(event); events.notifyAll(); }
            output.writeNoException(); return true;
        }
    };

    @Override protected void setUp() throws Exception {
        super.setUp(); run = UUID.randomUUID().toString();
        if (Engine.get().packages().get(PKG) == null) {
            File apk = new File(context().getCacheDir(), "activity-task-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                     FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[16384];
                    for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                }
                Engine.get().install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
    }

    @Override protected void tearDown() throws Exception {
        try {
            ActivityManager manager = (ActivityManager) context().getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                Intent base = task.getTaskInfo().baseIntent;
                if (base != null && base.getType() != null && base.getType().contains(PREFIX)) task.finishAndRemoveTask();
            }
            long end = SystemClock.elapsedRealtime() + 10000;
            while (hasTaskRecords() && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
            assertFalse("Task fixture records survived real task removal: " + inspect(), hasTaskRecords());
        } finally { super.tearDown(); }
    }

    public void testSingleTaskClearsExactGuestRecordsAcrossProcessesAndKeepsIntentSnapshot() throws Exception {
        Bundle root = launch("Root", 1);
        Bundle single = start(root, "Single", 10, 0);
        Bundle child = start(single, "Child", 20, 0);
        Bundle remote = start(child, "Remote", 30, 0);
        assertEquals(root.getInt("task"), remote.getInt("task"));
        assertEquals(root.getInt("pid"), single.getInt("pid"));
        assertTrue(single.getInt("pid") != remote.getInt("pid"));

        command(remote, "start", intent("Single", 11), "reuse");
        Bundle delivered = awaitEvent("intent", "Single", single.getString("instance"), 11);
        awaitEvent("destroy", "Child", child.getString("instance"), -1);
        awaitEvent("destroy", "Remote", remote.getString("instance"), -1);
        assertEquals(single.getInt("task"), delivered.getInt("task"));
        assertEquals(10, delivered.getInt("initial_sequence"));
        assertEquals(1, count("create", "Single"));
        assertEquals(0, count("destroy", "Root"));
        assertPayload(delivered, "Single", 11);
        awaitState(single, "RESUMED");

        int resumedBefore = countInstance("resume", root.getString("instance"));
        command(single, "finish", null, "return-to-root");
        awaitEvent("destroy", "Single", single.getString("instance"), -1);
        await(event -> is(event, "resume", "Root", root.getString("instance"), -1)
                && countInstance("resume", root.getString("instance")) > resumedBefore, "root resumed after singleTask finish");
        assertEquals(1, count("create", "Root"));
    }

    public void testSingleTopAndClearTopUseGuestComponentRatherThanSharedStub() throws Exception {
        Bundle root = launch("Root", 1);
        Bundle top = start(root, "Top", 10, 0);
        command(top, "start", intent("Top", 11), "top-reuse");
        Bundle reused = awaitEvent("intent", "Top", top.getString("instance"), 11);
        assertPayload(reused, "Top", 11);
        assertEquals(1, count("create", "Top"));
        awaitState(top, "RESUMED");

        Bundle child = start(top, "Child", 20, 0);
        Bundle secondTop = start(child, "Top", 12, 0);
        assertFalse(top.getString("instance").equals(secondTop.getString("instance")));
        assertEquals(top.getInt("task"), secondTop.getInt("task"));
        assertEquals(2, count("create", "Top"));
        assertEquals(0, count("destroy", "Child"));

        Bundle standard = start(secondTop, "Standard", 30, 0);
        Bundle above = start(standard, "Child", 40, 0);
        command(above, "start", intent("Standard", 31).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), "standard-clear");
        Bundle replacement = awaitEvent("create", "Standard", null, 31);
        awaitEvent("destroy", "Standard", standard.getString("instance"), -1);
        awaitEvent("destroy", "Child", above.getString("instance"), -1);
        assertFalse(standard.getString("instance").equals(replacement.getString("instance")));
        assertEquals(standard.getInt("task"), replacement.getInt("task"));
        assertEquals(0, count("intent", "Standard"));
        awaitState(replacement, "RESUMED");

        Bundle lastChild = start(replacement, "Child", 41, 0);
        command(lastChild, "start", intent("Standard", 32)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), "standard-single-clear");
        Bundle delivered = awaitEvent("intent", "Standard", replacement.getString("instance"), 32);
        awaitEvent("destroy", "Child", lastChild.getString("instance"), -1);
        assertPayload(delivered, "Standard", 32);
        assertEquals(31, delivered.getInt("initial_sequence"));
        assertEquals(2, count("create", "Standard"));

        // A standard root is replaced; physical CLEAR_TOP could incorrectly select any shared stub.
        awaitState(replacement, "RESUMED");
        command(replacement, "start", intent("Root", 2).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), "replace-root");
        Bundle newRoot = awaitEvent("create", "Root", null, 2);
        awaitEvent("destroy", "Root", root.getString("instance"), -1);
        awaitEvent("destroy", "Standard", replacement.getString("instance"), -1);
        assertEquals(root.getInt("task"), newRoot.getInt("task"));
        assertFalse(root.getString("instance").equals(newRoot.getString("instance")));
        assertEquals(0, count("intent", "Root"));
    }

    public void testCanonicalTaskWinsAffinityAndQueuedIntentsSurviveBackgroundAndRecreation() throws Exception {
        Bundle reservation = dispatch(intent("Single", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bundle second = dispatch(intent("Single", 2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bundle third = dispatch(intent("Single", 3).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bundle single = awaitEvent("create", "Single", null, 1);
        Bundle two = awaitEvent("intent", "Single", single.getString("instance"), 2);
        Bundle three = awaitEvent("intent", "Single", single.getString("instance"), 3);
        assertTrue(indexOf(two) < indexOf(three));
        assertPayload(two, "Single", 2); assertPayload(three, "Single", 3);
        assertEquals(1, count("create", "Single"));
        awaitOperation(reservation); awaitOperation(second); awaitOperation(third);
        awaitState(single, "RESUMED");

        Bundle affinity = launch("Root", 10);
        Bundle other = launch("OtherRoot", 20);
        assertTrue(single.getInt("task") != affinity.getInt("task"));
        assertTrue(affinity.getInt("task") != other.getInt("task"));
        command(other, "start", intent("Single", 4), "canonical-task");
        Bundle four = awaitEvent("intent", "Single", single.getString("instance"), 4);
        assertEquals(single.getInt("task"), four.getInt("task"));
        assertEquals(0, count("destroy", "Root"));
        assertEquals(0, count("destroy", "OtherRoot"));
        awaitState(single, "RESUMED");

        command(single, "back", null, "background");
        awaitCommand("background", false);
        Bundle resumed = dispatch(intent("Single", 5).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitEvent("intent", "Single", single.getString("instance"), 5);
        awaitOperation(resumed); awaitState(single, "RESUMED");
        command(single, "recreate", null, "configuration");
        Bundle recreated = await(event -> is(event, "create", "Single", null, 1)
                && !single.getString("instance").equals(event.getString("instance")), "recreated singleTask");
        assertEquals(single.getInt("task"), recreated.getInt("task"));
        assertEquals(single.getInt("pid"), recreated.getInt("pid"));
        awaitState(recreated, "RESUMED");
        dispatch(intent("Single", 6).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitEvent("intent", "Single", recreated.getString("instance"), 6);
        assertEquals(2, count("create", "Single"));
    }

    public void testRejectedTaskOperationsHaveNoFinishOrDeliverySideEffects() throws Exception {
        Bundle root = launch("Root", 1);
        Bundle single = start(root, "Single", 2, 0);
        Bundle remote = start(single, "Remote", 3, 0);
        int operationCount = inspect().getParcelableArrayList("operations").size();
        command(remote, "result", intent("Single", 4), "result-reuse");
        awaitCommand("result-reuse", true);
        command(remote, "start", intent("Single", 5).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK), "clear-task");
        awaitCommand("clear-task", true);
        command(remote, "start", intent("Single", 6).setComponent(new ComponentName("com.google.android.gms", "invalid.Activity")), "cross-package");
        awaitCommand("cross-package", true);

        Bundle wrongTask = EngineProtocol.response();
        wrongTask.putString("operation", "prepare"); wrongTask.putParcelable("intent", intent("Single", 7));
        wrongTask.putBinder("source_token", remote.getBinder("token")); wrongTask.putInt("task_id", root.getInt("task"));
        privateCall(remote, wrongTask, "forged-task-id"); awaitCommand("forged-task-id", true);
        Bundle wrongOwner = new Bundle(wrongTask); wrongOwner.remove("task_id");
        wrongOwner.putBinder("source_token", single.getBinder("token"));
        privateCall(remote, wrongOwner, "wrong-process-source"); awaitCommand("wrong-process-source", true);
        assertEquals(operationCount, inspect().getParcelableArrayList("operations").size());
        assertEquals(0, count("destroy", "Single")); assertEquals(0, count("destroy", "Remote"));
        assertEquals(0, count("intent", "Single"));
        awaitState(remote, "RESUMED");

        Bundle accepted = dispatch(intent("Single", 8).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitEvent("intent", "Single", single.getString("instance"), 8);
        awaitOperation(accepted); awaitEvent("destroy", "Remote", remote.getString("instance"), -1);
        Bundle stale = ActivityEnvelope.operation(accepted, "new_intent", single.getBinder("token"));
        privateCall(single, stale, "completed-operation"); awaitCommand("completed-operation", true);
        assertEquals(1, count("intent", "Single"));
        assertEquals(1, count("create", "Single"));
    }

    private Intent intent(String activity, int sequence) {
        Bundle extras = new Bundle(); extras.putBinder("observer", observer); extras.putBinder("payload", payload);
        return new Intent("mylite.task." + sequence).setComponent(new ComponentName(PKG, PREFIX + activity))
                .setDataAndType(Uri.parse("mylite-fixture://task/" + run + "/" + sequence), "application/x-mylite-task")
                .addCategory("mylite.category.TASK").putExtras(extras).putExtra("run", run).putExtra("sequence", sequence);
    }

    private Bundle launch(String activity, int sequence) throws Exception {
        dispatch(intent(activity, sequence).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        Bundle created = awaitEvent("create", activity, null, sequence); awaitState(created, "RESUMED"); return created;
    }

    private Bundle start(Bundle source, String activity, int sequence, int flags) throws Exception {
        command(source, "start", intent(activity, sequence).addFlags(flags), "start-" + sequence);
        Bundle created = awaitEvent("create", activity, null, sequence); awaitState(created, "RESUMED"); return created;
    }

    private Bundle dispatch(Intent intent) {
        Bundle request = EngineProtocol.response(); request.putString("operation", "prepare"); request.putParcelable("intent", intent);
        Bundle result = host(request);
        if (!result.getBoolean("queued")) {
            boolean accepted = false;
            try { context().startActivity(result.getParcelable("outer_intent")); accepted = true; }
            finally {
                Bundle outcome = ActivityEnvelope.operation(result, "start_result", null);
                outcome.putBoolean("accepted", accepted); host(outcome);
            }
        }
        return result;
    }

    private void privateCall(Bundle source, Bundle request, String tag) throws Exception {
        Bundle command = new Bundle(); command.putString("action", "private"); command.putString("tag", tag);
        command.putString("host", context().getPackageName()); command.putBundle("request", request); send(source, command);
    }

    private void command(Bundle source, String action, Intent intent, String tag) throws Exception {
        Bundle command = new Bundle(); command.putString("action", action); command.putString("tag", tag);
        if (intent != null) command.putParcelable("intent", intent); send(source, command);
    }

    private void send(Bundle source, Bundle command) throws Exception {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeBundle(command);
            assertTrue(source.getBinder("control").transact(IBinder.FIRST_CALL_TRANSACTION, input, output, 0));
            output.readException();
        } finally { input.recycle(); output.recycle(); }
    }

    private void awaitCommand(String tag, boolean error) throws Exception {
        Bundle event = await(value -> ("command".equals(value.getString("event")) || "error".equals(value.getString("event")))
                && value.getString("detail", "").startsWith(tag), "command " + tag);
        assertEquals(event.toString(), error ? "error" : "command", event.getString("event"));
    }

    private Bundle awaitEvent(String event, String type, String instance, int sequence) throws Exception {
        return await(value -> is(value, event, type, instance, sequence), event + " " + type + " " + sequence);
    }

    private boolean is(Bundle value, String event, String type, String instance, int sequence) {
        return run.equals(value.getString("run")) && event.equals(value.getString("event"))
                && (PREFIX + type).equals(value.getString("class"))
                && (instance == null || instance.equals(value.getString("instance")))
                && (sequence < 0 || value.getInt("sequence", -1) == sequence);
    }

    private Bundle await(Predicate<Bundle> match, String description) throws Exception {
        long end = SystemClock.elapsedRealtime() + 15000;
        synchronized (events) {
            while (true) {
                for (Bundle event : events) if (match.test(event)) return event;
                long remaining = end - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                events.wait(remaining);
            }
        }
        fail("Missing " + description + "; events=" + events + "; server=" + inspect()); return null;
    }

    private int count(String event, String type) {
        synchronized (events) {
            int count = 0; for (Bundle value : events) if (is(value, event, type, null, -1)) count++; return count;
        }
    }

    private int countInstance(String event, String instance) {
        synchronized (events) {
            int count = 0; for (Bundle value : events) if (event.equals(value.getString("event"))
                    && instance.equals(value.getString("instance"))) count++; return count;
        }
    }

    private int indexOf(Bundle event) { synchronized (events) { return events.indexOf(event); } }

    private void assertPayload(Bundle event, String activity, int sequence) {
        Intent actual = event.getParcelable("intent"); Intent expected = intent(activity, sequence);
        assertTrue(actual.toString(), expected.filterEquals(actual));
        assertEquals(sequence, actual.getIntExtra("sequence", -1)); assertEquals(run, actual.getStringExtra("run"));
        assertEquals(payload, actual.getExtras().getBinder("payload"));
    }

    private void awaitState(Bundle activity, String state) throws Exception {
        long end = SystemClock.elapsedRealtime() + 10000;
        do {
            ArrayList<Bundle> records = inspect().getParcelableArrayList("records");
            for (Bundle record : records) if (activity.getString("class").equals(record.getString("activity"))
                    && activity.getInt("task") == record.getInt("task_id") && state.equals(record.getString("state"))) return;
            Thread.sleep(25);
        } while (SystemClock.elapsedRealtime() < end);
        fail("Activity did not acknowledge " + state + ": " + activity + "; " + inspect());
    }

    private void awaitOperation(Bundle launch) throws Exception {
        long end = SystemClock.elapsedRealtime() + 10000;
        do {
            ArrayList<Bundle> operations = inspect().getParcelableArrayList("operations");
            for (Bundle operation : operations) if (launch.getLong("operation_id") == operation.getLong("operation_id")) {
                assertFalse(operation.toString(), "FAILED".equals(operation.getString("phase")));
                if ("COMPLETE".equals(operation.getString("phase"))) return;
            }
            Thread.sleep(25);
        } while (SystemClock.elapsedRealtime() < end);
        fail("Activity operation did not complete: " + launch + "; " + inspect());
    }

    private boolean hasTaskRecords() {
        ArrayList<Bundle> records = inspect().getParcelableArrayList("records");
        for (Bundle record : records) if (record.getString("activity", "").startsWith(PREFIX)) return true;
        return false;
    }

    private Context context() { return getInstrumentation().getTargetContext(); }
    private static Bundle host(Bundle request) {
        return EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, request);
    }
    private static Bundle inspect() {
        Bundle request = EngineProtocol.response(); request.putString("operation", "inspect"); return host(request);
    }
}
