package io.github.xmy953538104.mybox;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.IBinder;
import android.test.AndroidTestCase;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.am.ActivityRecord;
import top.niunaijun.blackbox.core.system.am.ActivityStack;
import top.niunaijun.blackbox.core.system.am.TaskRecord;

public final class ActivityLifecycleTest extends AndroidTestCase {
    @SuppressWarnings("unchecked")
    public void testClearingTaskFinishesItsActivitiesWithoutStoppingSiblingTask() throws Exception {
        ActivityStack stack = new ActivityStack();
        ProcessRecord process = process();
        Set<IBinder> finished = new HashSet<>();
        process.bActivityThread = (IBActivityThread) Proxy.newProxyInstance(
                IBActivityThread.class.getClassLoader(), new Class<?>[]{IBActivityThread.class},
                (proxy, method, args) -> {
                    if ("finishActivity".equals(method.getName())) {
                        finished.add((IBinder) args[0]);
                        return null;
                    }
                    throw new AssertionError("Unexpected guest call: " + method.getName());
                });
        TaskRecord cleared = new TaskRecord(-103, 0, "fixture");
        ActivityRecord first = activity(process);
        first.token = new Binder();
        ActivityRecord top = activity(process);
        top.token = new Binder();
        cleared.addTopActivity(first);
        cleared.addTopActivity(top);
        TaskRecord sibling = new TaskRecord(-104, 0, "fixture.other");
        ActivityRecord survivor = activity(process);
        survivor.token = new Binder();
        sibling.addTopActivity(survivor);
        Field field = ActivityStack.class.getDeclaredField("mTasks");
        field.setAccessible(true);
        Map<Integer, TaskRecord> tasks = (Map<Integer, TaskRecord>) field.get(stack);
        tasks.put(cleared.id, cleared);
        tasks.put(sibling.id, sibling);
        Method clear = ActivityStack.class.getDeclaredMethod("clearTask", TaskRecord.class);
        clear.setAccessible(true);
        clear.invoke(stack, cleared);
        assertTrue(cleared.needNewTask());
        assertNull(cleared.getTopActivityRecord());
        assertEquals(2, finished.size());
        assertTrue(finished.contains(first.token));
        assertTrue(finished.contains(top.token));
        assertFalse(finished.contains(survivor.token));
        assertSame(survivor, sibling.getTopActivityRecord());
        assertFalse(process.dead);
    }

    public void testDeadProcessCannotSupplyAnOldTaskOrTopActivity() {
        ProcessRecord old = process();
        TaskRecord task = new TaskRecord(-101, 0, "fixture");
        task.addTopActivity(activity(old));
        assertFalse(task.needNewTask());
        old.dead = true;
        assertTrue(task.needNewTask());
        assertNull(task.getTopActivityRecord());
        ActivityRecord current = activity(process());
        current.intent.putExtra("runSessionProbe", true);
        task.addTopActivity(current);
        assertSame(current, task.getTopActivityRecord());
        assertTrue(task.getTopActivityRecord().intent.getBooleanExtra("runSessionProbe", false));
    }

    @SuppressWarnings("unchecked")
    public void testLateDeathKeepsTheReplacementProcessRegistered() throws Exception {
        BProcessManagerService manager = new BProcessManagerService();
        ProcessRecord old = process();
        ProcessRecord current = process();
        Field field = BProcessManagerService.class.getDeclaredField("mProcessMap");
        field.setAccessible(true);
        Map<Integer, Map<String, ProcessRecord>> processes = (Map<Integer, Map<String, ProcessRecord>>) field.get(manager);
        Map<String, ProcessRecord> byName = new HashMap<>();
        byName.put(current.processName, current);
        processes.put(current.buid, byName);
        manager.onProcessDie(old);
        assertSame(current, processes.get(current.buid).get(current.processName));
        assertFalse(current.dead);
        assertTrue(old.dead);
    }

    @SuppressWarnings("unchecked")
    public void testStoppingProcessKeepsItsPhysicalSlotReserved() throws Exception {
        BProcessManagerService manager = new BProcessManagerService();
        Method availableSlot = BProcessManagerService.class.getDeclaredMethod("getUsingBPidL");
        availableSlot.setAccessible(true);
        int slot = (Integer) availableSlot.invoke(manager);
        assertTrue("No free slot for reservation test", slot >= 0);
        ProcessRecord stopping = process();
        stopping.bpid = slot;
        stopping.dead = true;
        Field pidsField = BProcessManagerService.class.getDeclaredField("mPidsSelfLocked");
        pidsField.setAccessible(true);
        ((List<ProcessRecord>) pidsField.get(manager)).add(stopping);
        assertFalse("Stopping process slot was reused", slot == (Integer) availableSlot.invoke(manager));
    }

    @SuppressWarnings("unchecked")
    public void testStoppingOneProcessKeepsOtherActivityAndClearsItsPendingLaunch() throws Exception {
        ActivityStack stack = new ActivityStack();
        ProcessRecord stopped = process();
        ActivityRecord old = activity(stopped);
        ActivityRecord survivor = activity(process());
        TaskRecord task = new TaskRecord(-102, 0, "fixture");
        task.addTopActivity(old);
        task.addTopActivity(survivor);
        Field tasksField = ActivityStack.class.getDeclaredField("mTasks");
        tasksField.setAccessible(true);
        ((Map<Integer, TaskRecord>) tasksField.get(stack)).put(task.id, task);
        Field launchingField = ActivityStack.class.getDeclaredField("mLaunchingActivities");
        launchingField.setAccessible(true);
        Set<ActivityRecord> launching = (Set<ActivityRecord>) launchingField.get(stack);
        ActivityRecord pending = activity(stopped);
        launching.add(pending);
        stack.onProcessStopped(stopped);
        assertTrue(old.finished);
        assertTrue(pending.finished);
        assertFalse(launching.contains(pending));
        assertFalse(survivor.finished);
        assertFalse(task.needNewTask());
        assertSame(survivor, task.getTopActivityRecord());
    }

    private static ProcessRecord process() {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = "fixture.test";
        ProcessRecord record = new ProcessRecord(info, info.packageName);
        record.buid = 65432;
        return record;
    }

    private static ActivityRecord activity(ProcessRecord process) {
        ActivityInfo info = new ActivityInfo();
        info.packageName = process.getPackageName();
        info.name = "fixture.test.Activity";
        ActivityRecord record = ActivityRecord.create(new Intent(), info, null, 0);
        record.processRecord = process;
        return record;
    }
}
