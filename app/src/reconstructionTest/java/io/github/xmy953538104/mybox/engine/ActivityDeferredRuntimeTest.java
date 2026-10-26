package io.github.xmy953538104.mybox.engine;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Actual Android deferral across both historical timeouts, followed by real button-driven creation. */
public final class ActivityDeferredRuntimeTest extends InstrumentationTestCase {
    private static final String PKG = "io.github.xmy953538104.mybox.fixture";
    private static final String PREFIX = PKG + ".activities.TaskProbe$";
    private final String run = UUID.randomUUID().toString();
    private final ArrayList<Bundle> events = new ArrayList<>();
    private final CountDownLatch admitted = new CountDownLatch(1);
    private volatile Runnable stopServer;
    private final Binder observer = new Binder() {
        @Override protected boolean onTransact(int code, Parcel input, Parcel output, int flags) {
            if (code != FIRST_CALL_TRANSACTION) return false;
            Bundle event = input.readBundle(getClass().getClassLoader());
            if (!run.equals(event.getString("run"))) throw new AssertionError("Stale fixture event");
            synchronized (events) { events.add(event); }
            if ("retire-server".equals(event.getString("event"))) {
                Runnable stop = stopServer;
                if (stop == null) throw new AssertionError("No verified server-death action");
                stop.run();
            }
            if ("deferred-submitted".equals(event.getString("event"))) {
                try {
                    if (!admitted.await(4, TimeUnit.SECONDS)) throw new AssertionError("No actual queued start admission");
                } catch (InterruptedException failure) { throw new AssertionError(failure); }
            }
            output.writeNoException(); return true;
        }
    };

    public void testAcceptedQueuedAndDirectLaunchesSurviveForegroundGate() throws Exception {
        installFixture();
        try {
            Bundle request = EngineProtocol.response(); request.putString("operation", "prepare");
            request.putParcelable("intent", intent("SingleAliasA", false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            Bundle launch = host(request);
            assertTrue("Single-task entry must use the actual task planner", launch.getBoolean("queued"));
            Bundle root = awaitEvent("resume", "Single");
            Bundle command = new Bundle(); command.putString("action", "deferred");
            command.putParcelable("intent", intent("Isolated", false));
            command.putParcelable("direct", intent("Child", true));
            command.putParcelable("gate", intent("Standard", true));
            command(root, command);
            awaitEvent("deferred-submitted", "Single");
            long deadline = SystemClock.elapsedRealtime() + 3000;
            Bundle queued;
            int admittedTask;
            do {
                queued = record("Isolated");
                admittedTask = physicalTask("Isolated");
                if (queued != null && queued.getBoolean("dispatched") && admittedTask >= 0) break;
                Thread.sleep(10);
            } while (SystemClock.elapsedRealtime() < deadline);
            assertNotNull("Queued launch was not reserved", queued);
            assertTrue("Actual Android did not admit the pending task", admittedTask >= 0);
            admitted.countDown();
            Bundle gate = awaitEvent("resume", "Standard");
            awaitEvent("destroy", "Single");
            long heldAt = SystemClock.elapsedRealtime();
            long until = heldAt + 65000;
            long queuedId = queued.getLong("record_id");
            Bundle direct = record("Child"); assertNotNull(direct);
            long directId = direct.getLong("record_id");
            do {
                assertPending("Isolated", queuedId, true);
                assertPending("Child", directId, false);
                assertNull("Android created the queued page before dismissing the gate", event("create", "Isolated"));
                assertNull("Android created the direct page before dismissing the gate", event("create", "Child"));
                Thread.sleep(500);
            } while (SystemClock.elapsedRealtime() < until);
            clickContinue("Standard");
            Bundle child = awaitEvent("resume", "Child");
            assertEquals(gate.getInt("task"), child.getInt("task"));
            assertEquals(root.getInt("pid"), child.getInt("pid"));
            clickContinue("Child");
            Bundle isolated = awaitEvent("resume", "Isolated");
            assertEquals(root.getInt("pid"), isolated.getInt("pid"));
            assertEquals("Created page changed its already admitted OS task", admittedTask, isolated.getInt("task"));
            assertTrue("singleInstance merged into the gate task", gate.getInt("task") != isolated.getInt("task"));
            Bundle current = record("Isolated"); assertNotNull(current);
            assertEquals(queuedId, current.getLong("record_id"));
            ArrayList<Bundle> operations = inspect().getParcelableArrayList("operations");
            boolean complete = false;
            for (Bundle operation : operations) if (operation.getLong("record_id") == queuedId)
                complete |= "COMPLETE".equals(operation.getString("phase"));
            assertTrue("Real CREATED did not complete the original operation", complete);
            android.util.Log.i("MyliteActivities", "DEFERRED_LAUNCH_REPORT run=" + run
                    + " actualElapsedMs=" + (SystemClock.elapsedRealtime() - heldAt)
                    + " queuedRecord=" + queuedId + " directRecord=" + directId
                    + " pid=" + isolated.getInt("pid") + " task=" + isolated.getInt("task"));
        } finally {
            admitted.countDown();
            removeTasks();
            long end = SystemClock.elapsedRealtime() + 10000;
            boolean pending;
            do {
                pending = false;
                ArrayList<Bundle> records = inspect().getParcelableArrayList("records");
                for (Bundle record : records) pending |= record.getString("activity", "").startsWith(PREFIX);
                if (pending) Thread.sleep(25);
            } while (pending && SystemClock.elapsedRealtime() < end);
            assertFalse("Real task destruction was not acknowledged before instrumentation ended", pending);
        }
    }

    public void testActualServerDeathRetiresStoppingGuestWithoutJavaCrash() throws Exception {
        installFixture();
        try {
            Bundle request = EngineProtocol.response(); request.putString("operation", "prepare");
            request.putParcelable("intent", intent("SingleAliasA", false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            assertTrue(host(request).getBoolean("queued"));
            Bundle root = awaitEvent("resume", "Single");
            ActivityManager manager = context().getSystemService(ActivityManager.class);
            int serverPid = -1; boolean ownedGuest = false;
            for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
                if (process.uid != android.os.Process.myUid()) continue;
                if ((context().getPackageName() + ":engine_server").equals(process.processName)) serverPid = process.pid;
                if (process.pid == root.getInt("pid")) for (int slot = 0; slot < EngineProtocol.SLOT_COUNT; slot++)
                    ownedGuest |= (context().getPackageName() + ":engine_p" + slot).equals(process.processName);
            }
            assertTrue("Guest PID is not an actual owned VM", ownedGuest);
            assertTrue(serverPid > 0 && serverPid != android.os.Process.myPid() && serverPid != root.getInt("pid"));
            IBinder server = Engine.get().server();
            final int ownedServerPid = serverPid;
            stopServer = () -> {
                assertTrue("The exact server died before this controlled stop", server.isBinderAlive());
                android.os.Process.killProcess(ownedServerPid);
            };
            Bundle command = new Bundle(); command.putString("action", "retire-server");
            command(root, command);
            long end = SystemClock.elapsedRealtime() + 10000;
            android.app.ApplicationExitInfo exit = null;
            do {
                for (android.app.ApplicationExitInfo row : manager.getHistoricalProcessExitReasons(
                        context().getPackageName(), root.getInt("pid"), 4)) if (row.getPid() == root.getInt("pid")) exit = row;
                if (!server.isBinderAlive() && exit != null) break;
                Thread.sleep(25);
            } while (SystemClock.elapsedRealtime() < end);
            assertFalse("The actual server is still alive", server.isBinderAlive());
            assertNotNull("Stopping guest has no actual process-exit record", exit);
            assertTrue("Guest exited through a Java/native crash: " + exit,
                    exit.getReason() != android.app.ApplicationExitInfo.REASON_CRASH
                            && exit.getReason() != android.app.ApplicationExitInfo.REASON_CRASH_NATIVE);
            android.util.Log.i("MyliteProcesses", "ACTIVITY_SERVER_DEATH_REPORT run=" + run
                    + " guestPid=" + root.getInt("pid") + " serverPid=" + serverPid
                    + " reason=" + exit.getReason() + " status=" + exit.getStatus());
        } finally { removeTasks(); }
    }

    private void removeTasks() {
        ActivityManager manager = context().getSystemService(ActivityManager.class);
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            ActivityManager.RecentTaskInfo info = ServerActivities.taskInfoForEnumeration(task);
            if (info != null && info.baseIntent != null && info.baseIntent.getType() != null
                    && info.baseIntent.getType().contains(PREFIX)) task.finishAndRemoveTask();
        }
    }

    private void assertPending(String name, long id, boolean planned) {
        Bundle current = record(name); assertNotNull("Accepted Activity was removed: " + name, current);
        assertEquals(id, current.getLong("record_id"));
        assertEquals(current.toString(), "DISPATCHED", current.getString("state"));
        assertTrue(current.getBoolean("dispatched"));
        assertEquals(-1, current.getInt("task_id"));
        if (planned) {
            boolean waiting = false;
            ArrayList<Bundle> operations = inspect().getParcelableArrayList("operations");
            for (Bundle operation : operations) if (operation.getLong("record_id") == id)
                waiting |= "WAIT_CREATED".equals(operation.getString("phase")) && !operation.getBoolean("payload_claimed");
            assertTrue("Queued operation did not retain its unclaimed payload", waiting);
        }
    }

    private void clickContinue(String name) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null) for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("Continue deferred launch " + name))
                if (node.isVisibleToUser() && node.isEnabled() && node.isClickable()
                        && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Actual foreground gate button was unavailable");
    }

    private Bundle awaitEvent(String type, String name) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do { Bundle found = event(type, name); if (found != null) return found; Thread.sleep(25); }
        while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Missing real " + name + ":" + type + "; " + inspect());
    }

    private Bundle event(String type, String name) {
        synchronized (events) {
            for (Bundle event : events) {
                if ("error".equals(event.getString("event"))) throw new AssertionError(event.toString());
                if (type.equals(event.getString("event")) && (PREFIX + name).equals(event.getString("class"))) return event;
            }
        }
        return null;
    }

    private Intent intent(String name, boolean gate) {
        Bundle extras = new Bundle(); extras.putBinder("observer", observer); extras.putString("run", run);
        return new Intent().setComponent(new ComponentName(PKG, PREFIX + name)).putExtras(extras)
                .putExtra("deferred_gate", gate);
    }

    private static void command(Bundle activity, Bundle command) throws Exception {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeBundle(command);
            assertTrue(activity.getBinder("control").transact(IBinder.FIRST_CALL_TRANSACTION, input, output, 0));
            output.readException();
        } finally { input.recycle(); output.recycle(); }
    }

    private Bundle record(String name) {
        ArrayList<Bundle> records = inspect().getParcelableArrayList("records");
        for (Bundle record : records) if ((PREFIX + name).equals(record.getString("activity"))) return record;
        return null;
    }

    private int physicalTask(String name) {
        ActivityManager manager = (ActivityManager) context().getSystemService(Context.ACTIVITY_SERVICE);
        String component = new ComponentName(PKG, PREFIX + name).flattenToString();
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            ActivityManager.RecentTaskInfo info = ServerActivities.taskInfoForEnumeration(task);
            if (info != null && info.baseIntent != null && component.equals(info.baseIntent.getType())) return info.id;
        }
        return -1;
    }

    private void installFixture() throws Exception {
        if (Engine.get().packages().get(PKG) != null) return;
        File apk = new File(context().getCacheDir(), "activity-deferred-fixture.apk");
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                 FileOutputStream output = new FileOutputStream(apk)) {
                byte[] buffer = new byte[16384];
                for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            }
            Engine.get().install(Collections.singletonList(apk));
        } finally { apk.delete(); }
    }

    private Context context() { return getInstrumentation().getTargetContext(); }
    private static Bundle host(Bundle request) {
        return EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, request);
    }
    private static Bundle inspect() {
        Bundle request = EngineProtocol.response(); request.putString("operation", "inspect"); return host(request);
    }
}
