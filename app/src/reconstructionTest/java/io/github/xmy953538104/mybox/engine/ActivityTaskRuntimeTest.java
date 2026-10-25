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

    public void testAppTasksUseRealTaskAndAllFivePublicOperationsAcrossProcesses() throws Exception {
        Bundle root = launch("Root", 1); Bundle other = launch("OtherRoot", 2);
        Bundle listed = appTask(root, "list", null, null, "list-real", null);
        ArrayList<ActivityManager.RecentTaskInfo> infos = listed.getParcelableArrayList("infos");
        assertNotNull(infos); assertTrue("A constant empty list is not an implementation", infos.size() >= 2);
        ActivityManager.RecentTaskInfo logical = listed.getParcelable("info");
        ActivityManager.RecentTaskInfo actual = physicalTask(root.getInt("task")).getTaskInfo();
        assertEquals(actual.persistentId, logical.persistentId); assertEquals(actual.id, logical.id);
        assertEquals(actual.numActivities, logical.numActivities);
        assertEquals(new ComponentName(PKG, PREFIX + "Root"), logical.baseActivity);
        assertEquals(logical.baseActivity, logical.topActivity); assertEquals(logical.baseActivity, RuntimeReflection.get(logical, "realActivity"));
        assertTrue(intent("Root", 1).filterEquals(logical.baseIntent));
        assertNull("Physical launch envelope escaped in task metadata", logical.baseIntent.getExtras());
        for (ActivityManager.RecentTaskInfo info : infos) {
            assertEquals(PKG, info.baseActivity.getPackageName()); assertEquals(PKG, info.topActivity.getPackageName());
            assertFalse(ActivityEnvelope.isStub(info.baseIntent));
        }
        IBinder capability = listed.getBinder("app_task"); assertNotNull(capability);
        Bundle exclude = new Bundle(); exclude.putBoolean("exclude", true);
        Bundle changed = appTask(root, "exclude", capability, null, "exclude-real", exclude);
        assertTrue((((ActivityManager.RecentTaskInfo) changed.getParcelable("info")).baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0);
        assertTrue((physicalTask(root.getInt("task")).getTaskInfo().baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0);
        exclude.putBoolean("exclude", false); appTask(root, "exclude", capability, null, "include-real", exclude);
        assertEquals(0, physicalTask(root.getInt("task")).getTaskInfo().baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        int before = countInstance("resume", root.getString("instance"));
        appTask(root, "move", capability, null, "move-real", null);
        await(value -> is(value, "resume", "Root", root.getString("instance"), -1)
                && countInstance("resume", root.getString("instance")) > before, "actual AppTask moveToFront resume");
        awaitState(root, "RESUMED");
        appTask(root, "start", capability, intent("Remote", 3), "start-remote-real", null);
        Bundle remote = awaitEvent("create", "Remote", null, 3); awaitState(remote, "RESUMED");
        assertEquals(root.getInt("task"), remote.getInt("task")); assertTrue(root.getInt("pid") != remote.getInt("pid"));
        assertPayload(remote, "Remote", 3);
        // The same application in another process may use its task without borrowing the root's Activity token.
        appTask(remote, "start", capability, intent("Child", 4), "start-child-from-peer-process", null);
        Bundle child = awaitEvent("create", "Child", null, 4); awaitState(child, "RESUMED");
        assertEquals(root.getInt("task"), child.getInt("task")); assertPayload(child, "Child", 4);
        ActivityManager.RecentTaskInfo top = appTask(remote, "info", capability, null, "top-real", null).getParcelable("info");
        assertEquals(new ComponentName(PKG, PREFIX + "Child"), top.topActivity);
        Bundle afterFinish = new Bundle(); afterFinish.putParcelable("after_finish_intent", intent("Child", 5));
        assertTrue(appTask(remote, "finish", capability, null, "finish-real", afterFinish).getBoolean("after_finish_denied"));
        awaitEvent("destroy", "Root", root.getString("instance"), -1);
        awaitEvent("destroy", "Remote", remote.getString("instance"), -1);
        awaitEvent("destroy", "Child", child.getString("instance"), -1);
        assertNull("OS task survived finishAndRemoveTask", findPhysicalTask(root.getInt("task")));
        assertEquals("Removed task created an untracked replacement child", 1, count("create", "Child"));
        Bundle stale = appTaskCommand("info", capability, null, "stale-task", null); send(other, stale); awaitCommand("stale-task", true);
        assertNotNull("Another owned task was accidentally removed", findPhysicalTask(other.getInt("task")));
    }

    public void testAppTaskCapabilityRejectsHostAndOtherGuestWithSamePhysicalUid() throws Exception {
        Bundle root = launch("Root", 1);
        IBinder capability = appTask(root, "list", null, null, "owner-capability", null).getBinder("app_task");
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken("android.app.IAppTask");
            int query = RuntimeReflection.field(Class.forName("android.app.IAppTask$Stub"), "TRANSACTION_getTaskInfo").getInt(null);
            assertTrue(capability.transact(query, input, output, 0));
            try { output.readException(); fail("Unassigned host process used a guest task capability"); }
            catch (SecurityException expected) { }
        } finally { input.recycle(); output.recycle(); }
        String peer = "io.github.xmy953538104.mybox.uiconsumer";
        if (Engine.get().packages().get(peer) == null) {
            File apk = new File(context().getCacheDir(), "app-task-peer.apk");
            try {
                try (InputStream source = getInstrumentation().getContext().getAssets().open("account-ui-consumer.apk");
                     FileOutputStream sink = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[16384]; for (int count; (count = source.read(buffer)) != -1;) sink.write(buffer, 0, count);
                }
                Engine.get().install(Collections.singletonList(apk));
            } finally { assertTrue("Cannot remove peer installation staging", !apk.exists() || apk.delete()); }
        }
        Bundle extras = new Bundle(); extras.putBinder("observer", observer);
        dispatch(new Intent().setComponent(new ComponentName(peer, peer + ".ConsumerActivity")).putExtras(extras)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        Bundle ready = await(value -> "consumer".equals(value.getString("event")) && peer.equals(value.getString("package")), "peer guest ready");
        try {
            Bundle command = new Bundle(); command.putString("action", "app-task-denied"); command.putString("tag", run);
            command.putBinder("app_task", capability); send(ready, command);
            Bundle denied = await(value -> ("app-task-denied".equals(value.getString("event")) || "error".equals(value.getString("event")))
                    && run.equals(value.getString("tag")), "same UID foreign guest rejection");
            assertEquals(denied.toString(), "app-task-denied", denied.getString("event"));
            for (String method : new String[]{"getTaskInfo", "finishAndRemoveTask", "setExcludeFromRecents", "moveToFront", "startActivity"})
                assertTrue(method, denied.getBoolean(method));
            ArrayList<ActivityManager.RecentTaskInfo> visible = denied.getParcelableArrayList("infos"); assertNotNull(visible); assertFalse(visible.isEmpty());
            for (ActivityManager.RecentTaskInfo info : visible) assertEquals(peer, info.baseActivity.getPackageName());
            assertNotNull(findPhysicalTask(root.getInt("task")));
            assertEquals(0, physicalTask(root.getInt("task")).getTaskInfo().baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            assertEquals(0, count("destroy", "Root"));
        } finally {
            Bundle finish = new Bundle(); finish.putString("action", "finish"); finish.putString("tag", run); send(ready, finish);
            await(value -> "consumer-destroyed".equals(value.getString("event")) && peer.equals(value.getString("package")), "peer guest finished");
        }
    }

    public void testAppTaskStartRejectsUnsupportedReuseAndOptionsWithoutMutation() throws Exception {
        Bundle root = launch("Root", 1); IBinder cap = appTask(root, "list", null, null, "reject-cap", null).getBinder("app_task");
        assertTrue(appTask(root, "forged-thread", cap, null, "forged-thread", null).getBoolean("denied"));
        assertTrue(appTask(root, "forged-package", cap, null, "forged-package", null).getBoolean("denied"));
        Bundle request = EngineProtocol.response(); request.putString("operation", "prepare"); request.putParcelable("intent", intent("Child", 9));
        request.putBinder("in_task", cap); request.putBinder("task_invocation", new Binder());
        privateCall(root, request, "forged-task-invocation"); awaitCommand("forged-task-invocation", true);
        send(root, appTaskCommand("start", cap, intent("Root", 2).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), "app-task-reuse", null));
        awaitCommand("app-task-reuse", true);
        Bundle options = new Bundle(); Bundle display = new Bundle(); display.putInt("android.activity.launchDisplayId", 99); options.putBundle("options", display);
        send(root, appTaskCommand("start", cap, intent("Child", 3), "app-task-options", options)); awaitCommand("app-task-options", true);
        send(root, appTaskCommand("start", cap, intent("Child", 4).setComponent(new ComponentName("com.google.android.gms", "invalid.Activity")), "app-task-foreign", null));
        awaitCommand("app-task-foreign", true);
        assertEquals(1, count("create", "Root")); assertEquals(0, count("destroy", "Root")); assertEquals(0, count("create", "Child"));
        assertEquals(1, physicalTask(root.getInt("task")).getTaskInfo().numActivities);
    }

    public void testAppTasksDoNotGuessOldPhysicalTaskOwnershipAfterGuestProcessDeath() throws Exception {
        Bundle root = launch("Root", 1); IBinder cap = appTask(root, "list", null, null, "old-root-cap", null).getBinder("app_task");
        Bundle survivor = launch("Remote", 2); assertTrue(root.getInt("pid") != survivor.getInt("pid"));
        android.os.Process.killProcess(root.getInt("pid"));
        long end = SystemClock.elapsedRealtime() + 15000;
        boolean retired;
        do {
            retired = true;
            ArrayList<Bundle> entries = inspect().getParcelableArrayList("records");
            for (Bundle record : entries) if (record.getInt("pid") == root.getInt("pid")) retired = false;
            if (!retired) Thread.sleep(25);
        } while (!retired && SystemClock.elapsedRealtime() < end);
        assertTrue("Killed guest records were not retired", retired);
        Bundle surviving = appTask(survivor, "list", null, null, "surviving-task-list", null);
        ArrayList<ActivityManager.RecentTaskInfo> infos = surviving.getParcelableArrayList("infos"); assertNotNull(infos); assertFalse(infos.isEmpty());
        boolean hasSurvivor = false;
        for (ActivityManager.RecentTaskInfo info : infos) {
            assertTrue("Old physical task was adopted by package name", info.persistentId != root.getInt("task"));
            if (info.persistentId == survivor.getInt("task")) hasSurvivor = true;
        }
        assertTrue(hasSurvivor);
        send(survivor, appTaskCommand("info", cap, null, "dead-root-cap", null)); awaitCommand("dead-root-cap", true);
    }

    public void testAppTaskOnCreateReadsRealInfoWhileMutatorRejectsPendingLifecycle() throws Exception {
        Bundle launch = dispatch(intent("Single", 1).putExtra("app_task_busy_probe", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bundle single = awaitEvent("create", "Single", null, 1);
        Bundle read = await(value -> "app-task".equals(value.getString("event"))
                && "info-on-create".equals(value.getString("detail")), "actual onCreate getAppTasks/getTaskInfo");
        ActivityManager.RecentTaskInfo info = read.getParcelable("info"); assertNotNull(info);
        assertEquals(single.getInt("task"), info.persistentId);
        assertEquals(new ComponentName(PKG, PREFIX + "Single"), info.baseActivity);
        assertEquals(info.baseActivity, info.topActivity); assertFalse(ActivityEnvelope.isStub(info.baseIntent));
        Bundle error = await(value -> "error".equals(value.getString("event")) && value.getString("detail", "").startsWith("busy-on-create"), "explicit busy task conflict");
        assertTrue(error.toString(), error.getString("detail").contains("pending Android transition"));
        Bundle changed = await(value -> "app-task".equals(value.getString("event"))
                && "exclude-after-created".equals(value.getString("detail")), "real mutation after the CREATED acknowledgement");
        ActivityManager.RecentTaskInfo updated = changed.getParcelable("info"); assertNotNull(updated);
        assertTrue((updated.baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0);
        assertTrue((physicalTask(single.getInt("task")).getTaskInfo().baseIntent.getFlags()
                & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0);
        awaitOperation(launch); awaitState(single, "RESUMED");
        assertEquals(1, count("create", "Single")); assertEquals(0, count("destroy", "Single"));
    }

    public void testSingleInstanceKeepsItsTaskExclusiveAndReusesOnlyItsComponent() throws Exception {
        Bundle root = launch("Root", 1);
        Bundle isolated = start(root, "Isolated", 10, 0);
        assertTrue("singleInstance shared its caller's task", root.getInt("task") != isolated.getInt("task"));
        Bundle child = start(isolated, "Child", 20, 0);
        assertEquals("Ordinary child did not return to the regular affinity task", root.getInt("task"), child.getInt("task"));
        assertTrue(child.getInt("task") != isolated.getInt("task"));
        command(child, "start", intent("Isolated", 11), "single-instance-reuse");
        Bundle delivered = awaitEvent("intent", "Isolated", isolated.getString("instance"), 11);
        assertEquals(isolated.getInt("task"), delivered.getInt("task"));
        assertEquals(1, count("create", "Isolated"));
        assertEquals(0, count("destroy", "Child"));
        assertPayload(delivered, "Isolated", 11); awaitState(isolated, "RESUMED");
        Bundle remote = start(isolated, "Remote", 30, 0);
        assertEquals(root.getInt("task"), remote.getInt("task"));
        assertTrue(remote.getInt("pid") != isolated.getInt("pid"));
        command(remote, "start", intent("Isolated", 12), "remote-single-instance-reuse");
        awaitEvent("intent", "Isolated", isolated.getString("instance"), 12);
        awaitState(isolated, "RESUMED");
        command(isolated, "finish", null, "finish-isolated");
        awaitEvent("destroy", "Isolated", isolated.getString("instance"), -1);
        awaitState(remote, "RESUMED");
        assertEquals(0, count("destroy", "Root")); assertEquals(0, count("destroy", "Remote"));
    }

    public void testSingleInstanceResultChildKeepsTheActualCallingTaskAndResult() throws Exception {
        Bundle isolated = launch("Isolated", 1);
        command(isolated, "result", intent("Remote", 2), "isolated-result"); awaitCommand("isolated-result", false);
        Bundle remote = awaitEvent("create", "Remote", null, 2);
        assertEquals(isolated.getInt("task"), remote.getInt("task"));
        assertTrue(isolated.getInt("pid") != remote.getInt("pid")); assertEquals(PKG, remote.getString("calling_package"));
        assertTrue((((Intent) remote.getParcelable("intent")).getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals(0, count("result", "Isolated"));
        Bundle child = start(remote, "Child", 3, 0);
        assertEquals(isolated.getInt("task"), child.getInt("task"));
        command(child, "finish", null, "finish-result-grandchild"); awaitEvent("destroy", "Child", child.getString("instance"), -1);
        command(remote, "finish_ok", null, "finish-result-child"); awaitEvent("destroy", "Remote", remote.getString("instance"), -1);
        Bundle result = awaitEvent("result", "Isolated", isolated.getString("instance"), -1);
        Intent delivered = result.getParcelable("intent"); assertNotNull(delivered);
        assertEquals(73, delivered.getIntExtra("result_request", -1));
        assertEquals(android.app.Activity.RESULT_OK, delivered.getIntExtra("result_code", 0));
        assertEquals(42, delivered.getIntExtra("probe_result", -1));
        awaitState(isolated, "RESUMED"); assertEquals(1, count("result", "Isolated"));
        command(isolated, "result", intent("Single", 4), "fresh-single-result"); awaitCommand("fresh-single-result", false);
        Bundle single = awaitEvent("create", "Single", null, 4);
        assertEquals(isolated.getInt("task"), single.getInt("task")); assertEquals(PKG, single.getString("calling_package"));
        assertTrue((((Intent) single.getParcelable("intent")).getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals(1, count("result", "Isolated"));
        command(single, "finish_ok", null, "finish-single-result"); awaitEvent("destroy", "Single", single.getString("instance"), -1);
        await(event -> "result".equals(event.getString("event")) && count("result", "Isolated") == 2, "second actual result returned");
        awaitState(isolated, "RESUMED");
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

    public void testSingleTaskAliasesKeepOriginalIntentsAndReuseTheRealActivity() throws Exception {
        Bundle first = dispatch(intent("SingleAliasA", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        Bundle single = awaitEvent("create", "Single", null, 1);
        awaitOperation(first); awaitState(single, "RESUMED", PREFIX + "SingleAliasA");
        assertPayload(single, "SingleAliasA", 1);
        Bundle child = start(single, "Child", 2, 0);
        Bundle second = dispatch(intent("SingleAliasB", 3).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bundle reused = awaitEvent("intent", "Single", single.getString("instance"), 3);
        awaitEvent("destroy", "Child", child.getString("instance"), -1);
        awaitOperation(second); awaitState(single, "RESUMED", PREFIX + "SingleAliasA");
        assertPayload(reused, "SingleAliasB", 3);
        assertEquals(single.getInt("task"), reused.getInt("task"));
        assertEquals(single.getBinder("token"), reused.getBinder("token"));
        assertEquals(1, reused.getInt("initial_sequence"));
        assertEquals(1, count("create", "Single"));

        command(single, "back", null, "alias-background"); awaitCommand("alias-background", false);
        Bundle direct = dispatch(intent("Single", 4).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bundle delivered = awaitEvent("intent", "Single", single.getString("instance"), 4);
        awaitOperation(direct); awaitState(single, "RESUMED", PREFIX + "SingleAliasA");
        assertPayload(delivered, "Single", 4);
        assertEquals(single.getInt("task"), delivered.getInt("task"));
        assertEquals(1, delivered.getInt("initial_sequence"));

        command(single, "recreate", null, "alias-configuration");
        Bundle recreated = await(event -> is(event, "create", "Single", null, 1)
                && !single.getString("instance").equals(event.getString("instance")), "recreated alias target");
        awaitState(recreated, "RESUMED", PREFIX + "SingleAliasA");
        Bundle third = dispatch(intent("SingleAliasB", 5).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bundle after = awaitEvent("intent", "Single", recreated.getString("instance"), 5);
        awaitOperation(third); assertPayload(after, "SingleAliasB", 5);
        assertEquals(single.getInt("task"), after.getInt("task"));
        assertEquals(1, after.getInt("initial_sequence"));
        assertEquals(2, count("create", "Single"));

        command(recreated, "start", intent("SingleAliasB", 6)
                .setComponent(new ComponentName("other.package", PREFIX + "SingleAliasB")), "foreign-alias");
        awaitCommand("foreign-alias", true);
        assertEquals(3, count("intent", "Single"));
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

    private Bundle appTask(Bundle source, String operation, IBinder cap, Intent intent, String tag, Bundle extra) throws Exception {
        send(source, appTaskCommand(operation, cap, intent, tag, extra));
        Bundle event = await(value -> ("app-task".equals(value.getString("event")) || "error".equals(value.getString("event")))
                && value.getString("detail", "").startsWith(tag), "AppTask " + tag);
        assertEquals(event.toString(), "app-task", event.getString("event")); return event;
    }

    private static Bundle appTaskCommand(String operation, IBinder cap, Intent intent, String tag, Bundle extra) {
        Bundle command = extra == null ? new Bundle() : new Bundle(extra); command.putString("action", "app-task");
        command.putString("task_operation", operation); command.putString("tag", tag);
        if (cap != null) command.putBinder("app_task", cap); if (intent != null) command.putParcelable("intent", intent); return command;
    }

    private ActivityManager.AppTask findPhysicalTask(int id) {
        ActivityManager manager = (ActivityManager) context().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.AppTask task : manager.getAppTasks()) if (task.getTaskInfo().persistentId == id) return task;
        return null;
    }

    private ActivityManager.AppTask physicalTask(int id) {
        ActivityManager.AppTask task = findPhysicalTask(id); assertNotNull("Missing actual Android task " + id, task); return task;
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
        awaitState(activity, state, activity.getString("class"));
    }

    private void awaitState(Bundle activity, String state, String requestedActivity) throws Exception {
        long end = SystemClock.elapsedRealtime() + 10000;
        do {
            ArrayList<Bundle> records = inspect().getParcelableArrayList("records");
            for (Bundle record : records) if (requestedActivity.equals(record.getString("activity"))
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
