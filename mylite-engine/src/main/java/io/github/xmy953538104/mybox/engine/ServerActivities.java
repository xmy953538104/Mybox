package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Guest task decisions and real framework lifecycle, owned by the server process. */
final class ServerActivities implements ProcessRegistry.DeathListener {
    interface TaskLookup { int taskId(IBinder token) throws Exception; }
    private static final int MAX_RECORDS = 256;
    private static final long UNCLAIMED_TIMEOUT_MS = 60000;
    private final Engine engine;
    private final ProcessRegistry processes;
    private final TaskLookup tasks;
    private final ServerAccountUiTickets accountUi;
    private final Map<IBinder, Record> records = new LinkedHashMap<>();
    private final Map<IBinder, Record> byToken = new HashMap<>();
    private final ArrayList<Task> taskRecords = new ArrayList<>();
    private final Map<IBinder, Operation> operations = new LinkedHashMap<>();
    private final Handler worker;
    private long nextId = 1, destroyedCount;
    private long nextOperation = 1, taskOrder;

    private static final class Task {
        final String packageName, packageGeneration, affinity;
        final ComponentName root, taskRoot;
        final boolean singleInstance;
        Task returnTo;
        final ArrayList<Record> stack = new ArrayList<>();
        final ArrayDeque<Operation> queue = new ArrayDeque<>();
        int physicalId = -1;
        long order;
        Task(ActivityInfo info, ComponentName root, String generation, long order) {
            packageName = info.packageName; packageGeneration = generation;
            affinity = info.taskAffinity; this.root = root; this.order = order;
            taskRoot = taskComponent(info);
            singleInstance = info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE;
        }
    }

    private static final class Operation {
        final Binder capability = new Binder();
        final long id;
        final Task task;
        final Task sourceTask;
        final Record target;
        final Intent original;
        final String referrer;
        final ArrayList<Record> victims;
        final boolean create, replaceRoot;
        final boolean clearAbove;
        final ProcessRegistry.Identity requester;
        final ServerAccountUiTickets.Launch accountUi;
        boolean payloadClaimed;
        String phase = "QUEUED", failure;
        Operation(long id, Task task, Record target, Intent original, String referrer,
                  ArrayList<Record> victims, boolean create, boolean replaceRoot, boolean clearAbove,
                  ProcessRegistry.Identity requester, ServerAccountUiTickets.Launch accountUi, Task sourceTask) {
            this.id = id; this.task = task; this.target = target; this.original = new Intent(original);
            this.referrer = referrer; this.victims = victims; this.create = create; this.replaceRoot = replaceRoot;
            this.clearAbove = clearAbove;
            this.requester = requester; this.accountUi = accountUi;
            this.sourceTask = sourceTask;
        }
    }

    private static final class Record {
        final Binder capability = new Binder();
        final long id, reservedAt = SystemClock.elapsedRealtime();
        final ProcessRegistry.Identity target, source;
        final int hostPid, expectedTask, requestCode, launchMode;
        final ComponentName component, taskComponent, sourceComponent;
        final Intent original;
        final Bundle identity;
        final String resultWho;
        boolean finishRequested;
        Task task;
        IBinder token;
        int taskId = -1;
        long lastEvent;
        int newIntents;
        String state = "RESERVED";
        boolean dispatched;
        boolean clearing;
        ServerAccountUiTickets.Launch accountUi;
        Operation creationOperation;
        boolean accountUiDelivered;
        Record(long id, ProcessRegistry.Identity target, ProcessRegistry.Identity source, int hostPid,
               ComponentName component, ActivityInfo info, Intent original, Record sourceRecord, String resultWho, int requestCode) {
            this.id = id; this.target = target; this.source = source; this.hostPid = hostPid;
            this.launchMode = info.launchMode;
            this.component = component; this.original = new Intent(original);
            this.taskComponent = taskComponent(info);
            this.expectedTask = sourceRecord == null ? -1 : sourceRecord.taskId;
            this.sourceComponent = sourceRecord == null ? null : sourceRecord.component;
            this.resultWho = resultWho; this.requestCode = requestCode;
            identity = EngineProtocol.response();
            identity.putBinder(ActivityEnvelope.RECORD, capability);
            identity.putBinder(ActivityEnvelope.OWNER, target.client);
            identity.putString("package", target.packageName);
            identity.putString("activity", component.getClassName());
            identity.putString("package_generation", target.packageGeneration);
            identity.putLong("generation", target.generation);
            identity.putInt("slot", target.slot);
            identity.putLong("record_id", id);
        }
    }

    ServerActivities(Engine engine, ProcessRegistry processes, ServerAccountUiTickets accountUi) {
        this(engine, processes, GuestActivities::physicalTaskId, accountUi);
    }

    ServerActivities(Engine engine, ProcessRegistry processes, TaskLookup tasks, ServerAccountUiTickets accountUi) {
        this.engine = engine; this.processes = processes; this.tasks = tasks; this.accountUi = accountUi;
        accountUi.setTaskPolicy((component, generation) -> {
            synchronized (ServerActivities.this) {
                for (Record record : records.values()) if (record.component.equals(component)
                        && record.target.packageGeneration.equals(generation)) {
                    throw new SecurityException("microG login UI is still open; finish it before another account request");
                }
            }
        });
        HandlerThread thread = new HandlerThread("MyliteActivityTasks");
        thread.start(); worker = new Handler(thread.getLooper());
    }

    Bundle host(Bundle input, int hostPid) throws Exception {
        requireProtocol(input);
        prune();
        String operation = input.getString("operation");
        if ("prepare".equals(operation)) return prepare(input, null, hostPid);
        if ("start_result".equals(operation)) return startResult(input, null, hostPid);
        if ("inspect".equals(operation)) return inspect();
        throw new SecurityException("Host cannot claim or acknowledge a guest Activity");
    }

    Bundle guest(Bundle input, ProcessRegistry.Identity caller) throws Exception {
        requireProtocol(input);
        prune();
        String operation = input.getString("operation");
        if ("prepare".equals(operation)) return prepare(input, caller, -1);
        if ("start_result".equals(operation)) return startResult(input, caller, -1);
        if ("claim".equals(operation)) return claim(input, caller);
        if ("event".equals(operation)) return event(input, caller);
        if ("new_intent".equals(operation)) return newIntent(input, caller);
        if ("command_failure".equals(operation)) {
            synchronized (this) {
                Record record = owned(input, caller);
                Operation pending = operation(input, null);
                if (pending.target != record && !pending.victims.contains(record)) {
                    throw new SecurityException("Activity command belongs to another record");
                }
                fail(pending, input.getString("failure", "Guest Activity command failed"));
                return EngineProtocol.response();
            }
        }
        if ("source".equals(operation)) {
            synchronized (this) {
                Record record = owned(input, caller);
                requireToken(record, input.getBinder("token"));
                Bundle result = EngineProtocol.response();
                if (record.requestCode >= 0 && record.sourceComponent != null) {
                    result.putParcelable("component", record.sourceComponent);
                    result.putString("package", record.sourceComponent.getPackageName());
                }
                return result;
            }
        }
        throw new EngineException("Unknown Activity operation");
    }

    private Bundle prepare(Bundle input, ProcessRegistry.Identity caller, int hostPid) throws Exception {
        Intent original = input.getParcelable("intent");
        ServerAccountUiTickets.Launch ui = null;
        if (ServerAccountUiTickets.hasTicket(original)) {
            ui = accountUi.authorize(original, caller);
            original = ui.intent;
        }
        if (input.containsKey("task_id")) throw new SecurityException("Guests cannot select a physical Activity task ID");
        if (original == null || original.getComponent() == null || original.getSelector() != null
                || original.getClipData() != null) throw new SecurityException("An explicit, ungranted guest Activity is required");
        ComponentName component = original.getComponent();
        int physicalFlags = original.getFlags();
        if (caller != null && ui == null && !caller.packageName.equals(component.getPackageName())) {
            throw new SecurityException("Cross-package Activity authorization is not implemented");
        }
        GoogleLaunchPolicy.require(engine, component.getPackageName());
        ActivityInfo activity = findActivity(component);
        if (ui != null && activity.targetActivity != null) throw new EngineException("Account UI aliases are not implemented");
        boolean planned = requireSupported(activity, original, caller == null);
        if (ui != null && planned && activity.launchMode != ActivityInfo.LAUNCH_SINGLE_TASK) {
            throw new EngineException("Account UI only supports standard or singleTask delivery");
        }
        Record source = null;
        if (caller != null) {
            synchronized (this) {
                source = byToken.get(input.getBinder("source_token"));
                if (source == null || !source.target.same(caller) || source.clearing || source.state.equals("RECREATING")) {
                    throw new SecurityException("Activity source token is not owned by this guest process");
                }
                if (source.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE && input.getInt("request_code", -1) < 0) planned = true;
                Operation current = source.task.queue.peek();
                if (!planned && current != null && !(source.task.queue.size() == 1
                        && current.target == source && current.phase.equals("DELIVERING"))) {
                    throw new EngineException("Activity task is completing a pending transition");
                }
            }
        }
        boolean freshTaskResult = planned && input.getInt("request_code", -1) >= 0
                && activity.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK && activity.targetActivity == null
                && (original.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)) == 0;
        if (planned && input.getInt("request_code", -1) >= 0) {
            if (!freshTaskResult) throw new EngineException("Task-mode startActivityForResult is not implemented: mode=" + activity.launchMode + " flags=0x" + Integer.toHexString(original.getFlags()));
            synchronized (this) { requireFreshResultTarget(activity); }
            planned = false;
        }
        Bundle acquire = EngineProtocol.response();
        acquire.putString("package", component.getPackageName());
        acquire.putString("process", activity.processName == null ? activity.packageName : activity.processName);
        Bundle target = processes.acquire(acquire);
        ProcessRegistry.Identity assigned = processes.identity(Process.myUid(), target.getInt("pid"));
        if (!assigned.packageGeneration.equals(activity.applicationInfo.sourceDir)
                || (ui != null && !ui.targetGeneration.equals(assigned.packageGeneration))
                || (caller != null && ((ui == null && !caller.packageGeneration.equals(assigned.packageGeneration))
                || !caller.same(processes.identity(Process.myUid(), caller.pid))))) {
            throw new SecurityException("Activity package/process changed while preparing its launch");
        }
        Record record;
        synchronized (this) {
            if (!assigned.client.isBinderAlive() || (source != null && (byToken.get(source.token) != source
                    || source.clearing || source.state.equals("RECREATING")))) {
                throw new SecurityException("Activity source or target died during preparation");
            }
            if (source != null && !planned) {
                Operation current = source.task.queue.peek();
                if (current != null && !(source.task.queue.size() == 1 && current.target == source
                        && current.phase.equals("DELIVERING"))) {
                    throw new EngineException("Activity task changed while preparing its launch");
                }
            }
            if (records.size() >= MAX_RECORDS) throw new EngineException("Too many outstanding Activity records");
            if (freshTaskResult) requireFreshResultTarget(activity);
            if (planned) return plan(activity, component, original, source, caller, hostPid, assigned, ui);
            if (input.getInt("request_code", -1) >= 0 && (freshTaskResult || source != null && source.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE)) {
                // Android adds this after deciding result linkage; setting it on the OS launch would cancel the result.
                original = new Intent(original).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            record = new Record(nextId++, assigned, caller, hostPid, component, activity, original, source,
                    input.getString("result_who"), input.getInt("request_code", -1));
            record.accountUi = ui;
            if (ui != null) ui.reserve(record.capability);
            record.task = source == null ? newTask(activity, component, assigned.packageGeneration) : source.task;
            record.task.stack.add(record);
            records.put(record.capability, record);
        }
        Bundle result = new Bundle(record.identity);
        result.putParcelable("outer_intent", ActivityEnvelope.create(engine.hostContext(), component,
                physicalFlags, record.identity));
        return result;
    }

    private void requireFreshResultTarget(ActivityInfo info) {
        for (Record record : records.values()) {
            if (record.target.packageGeneration.equals(info.applicationInfo.sourceDir)
                    && record.taskComponent.equals(ActivityEnvelope.realComponent(info))) {
                throw new EngineException("Result delivery to an existing task-mode Activity is not implemented");
            }
        }
    }

    private ActivityInfo findActivity(ComponentName component) throws Exception {
        PackageInfo pkg = engine.packages().getPackageInfo(component.getPackageName(), PackageManager.GET_ACTIVITIES);
        if (pkg != null && pkg.activities != null) for (ActivityInfo activity : pkg.activities) {
            if (component.getClassName().equals(activity.name) && activity.enabled
                    && activity.applicationInfo != null && activity.applicationInfo.enabled) return activity;
        }
        throw new EngineException("Guest Activity is absent or disabled: " + component);
    }

    private static boolean requireSupported(ActivityInfo info, Intent intent, boolean host) {
        int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        int reuse = Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_FORWARD_RESULT
                | Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP | Intent.FLAG_ACTIVITY_TASK_ON_HOME;
        boolean planned = info.launchMode != ActivityInfo.LAUNCH_MULTIPLE
                || (intent.getFlags() & (Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)) != 0;
        if (info.launchMode < ActivityInfo.LAUNCH_MULTIPLE || info.launchMode > ActivityInfo.LAUNCH_SINGLE_INSTANCE
                || info.documentLaunchMode != ActivityInfo.DOCUMENT_LAUNCH_NONE
                || (intent.getFlags() & (reuse | grants)) != 0
                || (planned && (android.os.Build.VERSION.SDK_INT < 28
                || info.targetActivity != null && info.launchMode != ActivityInfo.LAUNCH_SINGLE_TASK))
                || (!host && info.launchMode < ActivityInfo.LAUNCH_SINGLE_TASK
                && (intent.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) != 0)
                || (host && ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0
                || (!planned && (intent.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0)))) {
            throw new EngineException("Requested Activity task mode, alias, URI grant or platform is not implemented: "
                    + info.packageName + "/" + info.name + ", mode=" + info.launchMode + ", alias=" + info.targetActivity
                    + ", flags=0x" + Integer.toHexString(intent.getFlags()) + ", host=" + host);
        }
        return planned;
    }

    private static ComponentName taskComponent(ActivityInfo info) {
        // Keep physical root/envelope labels separate from singleTask's real Activity identity.
        return info.launchMode >= ActivityInfo.LAUNCH_SINGLE_TASK ? ActivityEnvelope.realComponent(info)
                : new ComponentName(info.packageName, info.name);
    }

    private Task newTask(ActivityInfo info, ComponentName root, String generation) {
        Task task = new Task(info, root, generation, ++taskOrder);
        taskRecords.add(task); return task;
    }

    /** Called with the record lock; all platform/guest work is deferred to the worker. */
    private Bundle plan(ActivityInfo info, ComponentName component, Intent original, Record source,
                        ProcessRegistry.Identity caller, int hostPid, ProcessRegistry.Identity assigned,
                        ServerAccountUiTickets.Launch ui) {
        Task selected = null;
        ComponentName taskComponent = taskComponent(info);
        if (source != null && source.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE && info.launchMode < ActivityInfo.LAUNCH_SINGLE_TASK
                && (original.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) selected = source.task;
        else if (info.launchMode >= ActivityInfo.LAUNCH_SINGLE_TASK
                || (original.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0) {
            Task affinity = null;
            for (Task candidate : taskRecords) {
                if (!candidate.packageName.equals(info.packageName)
                        || !candidate.packageGeneration.equals(assigned.packageGeneration) || candidate.stack.isEmpty()
                        || candidate.singleInstance != (info.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE)) continue;
                if (candidate.taskRoot.equals(taskComponent) && (selected == null || selected.order < candidate.order)) selected = candidate;
                if (info.taskAffinity != null && info.taskAffinity.equals(candidate.affinity)
                        && (affinity == null || affinity.order < candidate.order)) affinity = candidate;
            }
            if (selected == null && info.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE) selected = affinity;
        }
        if (selected == null) selected = newTask(info, component, assigned.packageGeneration);
        if (selected.queue.size() >= 16) throw new EngineException("Too many pending Activity task operations");
        boolean clear = info.launchMode >= ActivityInfo.LAUNCH_SINGLE_TASK
                || (original.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0;
        boolean single = info.launchMode != ActivityInfo.LAUNCH_MULTIPLE
                || (original.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0;
        Record existing = null;
        int position = -1;
        for (int i = selected.stack.size() - 1; i >= 0; i--) {
            Record candidate = selected.stack.get(i);
            if (candidate.taskComponent.equals(taskComponent) && !candidate.clearing && !candidate.state.equals("FINISH_REQUESTED")) {
                if (clear || (single && i == selected.stack.size() - 1)) { existing = candidate; position = i; }
                break;
            }
        }
        ArrayList<Record> victims = new ArrayList<>();
        if (existing != null && clear) {
            for (int i = selected.stack.size() - 1; i > position; i--) victims.add(selected.stack.get(i));
            if (!single) victims.add(existing);
        }
        if (ui != null) {
            requireOwnerVictims(component, assigned.packageGeneration, victims);
            if (existing != null && !existing.target.same(assigned)) {
                throw new SecurityException("Account UI task belongs to a retired process");
            }
            // The pinned microG UI reads its response only in onCreate. Core reuse cannot replace that business state.
            if (existing != null && ServerAccountUiTickets.requiresFreshInstance(component)) {
                throw new EngineException("microG login UI is already open; finish it before starting another account flow");
            }
        }
        boolean create = existing == null || !single;
        if (create && !selected.queue.isEmpty()) {
            throw new EngineException("Activity task is completing a pending transition");
        }
        boolean replaceRoot = create && existing != null && position == 0;
        Record target = existing;
        if (create) {
            target = new Record(nextId++, assigned, caller, hostPid, component, info, original, null, null, -1);
            target.task = selected;
        }
        Operation operation = new Operation(nextOperation++, selected, target, original,
                caller == null ? engine.hostContext().getPackageName() : caller.packageName, victims, create, replaceRoot,
                !create && clear, caller, ui, source == null ? null : source.task);
        try {
            if (ui != null) ui.reserve(target.capability, operation.capability, operation.id);
        } catch (RuntimeException failure) {
            if (selected.stack.isEmpty() && selected.queue.isEmpty()) taskRecords.remove(selected);
            throw failure;
        }
        if (create) {
            target.creationOperation = operation;
            selected.stack.add(target); records.put(target.capability, target);
        }
        selected.queue.add(operation); operations.put(operation.capability, operation);
        Bundle result = new Bundle(target.identity);
        result.putBoolean("queued", true);
        result.putBinder("launch_operation", operation.capability);
        result.putLong("operation_id", operation.id);
        schedule(selected);
        worker.postDelayed(() -> {
            synchronized (ServerActivities.this) {
                if (!isTerminal(operation)) fail(operation, "Activity task operation exceeded 20 seconds in " + operation.phase);
            }
        }, 20000);
        return result;
    }

    private static void requireOwnerVictims(ComponentName component, String generation, ArrayList<Record> victims) {
        for (Record victim : victims) if (!component.getPackageName().equals(victim.target.packageName)
                || !generation.equals(victim.target.packageGeneration)) {
            throw new SecurityException("Account UI cannot clear another package or installation from its task");
        }
    }

    private void requireAccountUi(Operation operation) throws Exception {
        if (operation.accountUi == null) return;
        operation.accountUi.pending(operation.target.capability, operation.capability, operation.id);
        if (operation.requester == null || !operation.requester.same(processes.identity(Process.myUid(), operation.requester.pid))
                || !operation.target.target.same(processes.identity(Process.myUid(), operation.target.target.pid))) {
            throw new SecurityException("Account UI consumer or target process changed before delivery");
        }
    }

    private void schedule(Task task) { worker.post(() -> advance(task)); }

    private void advance(Task task) {
        Operation pending;
        synchronized (this) {
            pending = task.queue.peek();
            if (pending == null || isTerminal(pending)) return;
            if (!pending.phase.equals("QUEUED") && !pending.phase.equals("CLEARING")) return;
        }
        try {
            if (!pending.target.target.same(processes.identity(Process.myUid(), pending.target.target.pid))) {
                throw new SecurityException("Activity operation target generation changed");
            }
            requireAccountUi(pending);
            if (pending.accountUi != null && task.physicalId >= 0) {
                requirePhysicalTask(task);
                ArrayList<Record> beforeClear;
                synchronized (this) {
                    beforeClear = pending.phase.equals("QUEUED") ? new ArrayList<>(pending.victims) : new ArrayList<>();
                    if (pending.phase.equals("QUEUED") && pending.clearAbove) {
                        int index = task.stack.indexOf(pending.target);
                        if (index < 0) throw new SecurityException("Account UI clear boundary disappeared");
                        beforeClear.clear();
                        for (int i = task.stack.size() - 1; i > index; i--) beforeClear.add(task.stack.get(i));
                    }
                    beforeClear.removeIf(victim -> records.get(victim.capability) != victim);
                }
                requireOwnerVictims(pending.target.component, pending.target.target.packageGeneration, beforeClear);
                for (Record victim : beforeClear) if (victim.token != null && tasks.taskId(victim.token) != task.physicalId) {
                    throw new SecurityException("Account UI clear victim moved to a different physical task");
                }
            }
            String phase;
            synchronized (this) {
                if (task.queue.peek() != pending) return;
                if (records.get(pending.target.capability) != pending.target) {
                    throw new EngineException("Activity operation target was destroyed");
                }
                phase = pending.phase;
                if (phase.equals("QUEUED")) {
                    if (pending.clearAbove) {
                        int index = task.stack.indexOf(pending.target);
                        if (index < 0) throw new EngineException("Activity clear boundary is missing");
                        pending.victims.clear();
                        for (int i = task.stack.size() - 1; i > index; i--) pending.victims.add(task.stack.get(i));
                    }
                    // A launch already accepted by Android must finish construction before we can finish its token.
                    for (Record victim : pending.victims) if (records.get(victim.capability) == victim
                            && (victim.token == null || victim.state.equals("CREATING") || victim.state.equals("RECREATING"))) return;
                    if (pending.accountUi != null) {
                        requireOwnerVictims(pending.target.component, pending.target.target.packageGeneration, pending.victims);
                        // Once clear/move/start may have an effect, uncertainty must burn this exact grant.
                        pending.accountUi.dispatched(pending.target.capability, pending.capability, pending.id);
                    }
                    pending.phase = "CLEARING";
                    for (Record victim : pending.victims) victim.clearing = true;
                }
            }
            if (phase.equals("QUEUED")) {
                if (pending.replaceRoot) {
                    // Only a whole, explicitly selected owned task can use physical CLEAR_TASK.
                    // CLEAR_TOP is never sent to a shared stub to choose a guest component.
                    startPhysical(pending, true);
                    return;
                }
                for (Record victim : pending.victims) {
                    requireAccountUi(pending);
                    synchronized (this) {
                        if (records.get(victim.capability) != victim) continue;
                        if (victim.token == null) throw new EngineException("Cannot clear an unclaimed Activity");
                    }
                    command(pending, victim, "finish");
                }
            }
            synchronized (this) {
                if (task.queue.peek() != pending || !pending.phase.equals("CLEARING")) return;
                for (Record victim : pending.victims) if (records.get(victim.capability) == victim) return;
                if (!pending.create && (pending.target.token == null || pending.target.state.equals("CREATING")
                        || pending.target.state.equals("RECREATING"))) return;
                pending.phase = pending.create ? "STARTING" : "DELIVERING";
            }
            requireAccountUi(pending);
            if (pending.create) startPhysical(pending, false);
            else {
                requirePhysicalTask(task).moveToFront();
                command(pending, pending.target, "new_intent");
            }
        } catch (Exception failure) {
            synchronized (this) { fail(pending, failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
        }
    }

    private void startPhysical(Operation operation, boolean replaceRoot) {
        Record target = operation.target;
        int taskId;
        synchronized (this) {
            if (isTerminal(operation)) return;
            operation.phase = "WAIT_CREATED";
            target.dispatched = true;
            target.state = "DISPATCHED";
            taskId = operation.task.physicalId;
        }
        int flags = operation.original.getFlags() & ~(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        if (taskId < 0) flags |= Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
        else if (replaceRoot) flags |= Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
        Bundle envelope = new Bundle(target.identity);
        envelope.putBinder("launch_operation", operation.capability); envelope.putLong("operation_id", operation.id);
        Intent outer = ActivityEnvelope.create(engine.hostContext(), target.component, flags, envelope);
        if (taskId < 0) engine.hostContext().startActivity(outer);
        else requirePhysicalTask(operation.task).startActivity(engine.hostContext(), outer, null);
    }

    private ActivityManager.AppTask requirePhysicalTask(Task task) {
        int expected;
        synchronized (this) { expected = task.physicalId; }
        ActivityManager manager = (ActivityManager) engine.hostContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.AppTask physical : manager.getAppTasks()) {
            ActivityManager.RecentTaskInfo info = physical.getTaskInfo();
            if (info.id != expected) continue;
            Intent base = info.baseIntent;
            if (base == null || !ActivityEnvelope.isStub(base)
                    || !engine.hostContext().getPackageName().equals(base.getComponent().getPackageName())
                    || !task.root.flattenToString().equals(base.getType())) {
                throw new SecurityException("Physical task does not match its recorded guest root");
            }
            return physical;
        }
        throw new EngineException("Recorded Android Activity task no longer exists");
    }

    private void command(Operation operation, Record record, String action) {
        Bundle request = ActivityEnvelope.operation(record.identity, action, record.token);
        request.putBinder("launch_operation", operation.capability);
        request.putLong("operation_id", operation.id);
        request.putInt("task_id", record.task.physicalId);
        EngineProtocol.request(record.target.client, EngineProtocol.GUEST, EngineProtocol.ACTIVITY_COMMAND, request);
    }

    private Operation operation(Bundle input, Record target) {
        Operation operation = operations.get(input.getBinder("launch_operation"));
        if (operation == null || operation.id != input.getLong("operation_id") || isTerminal(operation)
                || operation.task.queue.peek() != operation || (target != null && target != operation.target)) {
            throw new SecurityException("Activity operation is unknown, stale or belongs to another target");
        }
        return operation;
    }

    private static boolean isTerminal(Operation operation) {
        return operation.phase.equals("COMPLETE") || operation.phase.equals("FAILED");
    }

    private void complete(Operation operation) {
        if (isTerminal(operation)) return;
        operation.phase = "COMPLETE";
        if (operation.task.singleInstance && operation.sourceTask != operation.task) operation.task.returnTo = operation.sourceTask;
        operation.task.queue.remove(operation);
        if (operation.task.stack.isEmpty() && operation.task.queue.isEmpty()) taskRecords.remove(operation.task);
        trimOperations(); schedule(operation.task);
    }

    private void fail(Operation operation, String failure) {
        if (isTerminal(operation)) return;
        operation.failure = failure; operation.phase = "FAILED";
        if (operation.accountUi != null) operation.accountUi.cancel(operation.target.capability, operation.capability, operation.id, false);
        for (Record victim : operation.victims) victim.clearing = false;
        operation.task.queue.remove(operation);
        android.util.Log.e("MyliteActivities", "Activity operation " + operation.id + " failed: " + failure);
        if (operation.create && operation.target.token == null) remove(operation.target);
        if (operation.task.stack.isEmpty() && operation.task.queue.isEmpty()) taskRecords.remove(operation.task);
        trimOperations(); schedule(operation.task);
    }

    private void trimOperations() {
        if (operations.size() <= 128) return;
        java.util.Iterator<Operation> iterator = operations.values().iterator();
        while (operations.size() > 128 && iterator.hasNext()) if (isTerminal(iterator.next())) iterator.remove();
    }

    private Bundle startResult(Bundle input, ProcessRegistry.Identity caller, int hostPid) {
        synchronized (this) {
            Record record = record(input);
            if (record.creationOperation != null) throw new SecurityException("Queued Activity starts are acknowledged by the server worker");
            if (caller == null ? record.source != null || record.hostPid != hostPid : !caller.same(record.source)) {
                throw new SecurityException("Activity start result belongs to another caller");
            }
            boolean accepted = input.getBoolean("accepted");
            if (!accepted && record.token == null) {
                ServerAccountUiTickets.Launch ui = record.accountUi;
                record.accountUi = null;
                remove(record);
                if (ui != null) ui.cancel(record.capability, input.getBoolean("definite_failure"));
            }
            else if (accepted) {
                if (record.accountUi != null) record.accountUi.dispatched(record.capability);
                record.dispatched = true;
                if (record.state.equals("RESERVED")) record.state = "DISPATCHED";
            }
            return EngineProtocol.response();
        }
    }

    private Bundle claim(Bundle input, ProcessRegistry.Identity caller) throws Exception {
        IBinder token = input.getBinder("token");
        Record record;
        synchronized (this) { record = owned(input, caller); }
        if (token == null) throw new SecurityException("Activity launch has no physical token");
        int actualTask = tasks.taskId(token); // The OS, not a guest-supplied task number, is authoritative.
        if (actualTask < 0) throw new SecurityException("Activity token is not attached to an Android task");
        if (!caller.same(processes.identity(Process.myUid(), caller.pid))) {
            throw new SecurityException("Activity process changed during its token claim");
        }
        synchronized (this) {
            if (owned(input, caller) != record) throw new SecurityException("Activity reservation changed");
            Record previous = byToken.get(token);
            if ((previous != null && previous != record) || (record.token != null && !record.token.equals(token))) {
                throw new SecurityException("Activity capability cannot be rebound to a different token");
            }
            if (record.expectedTask >= 0 && actualTask != record.expectedTask) {
                throw new SecurityException("Activity was launched outside its source task");
            }
            if (record.task.physicalId >= 0 && actualTask != record.task.physicalId) {
                throw new SecurityException("Activity token belongs to another planned Android task");
            }
            if (record.token == null) {
                Operation creation = record.creationOperation;
                if (creation != null) {
                    if (operation(input, record) != creation || !creation.phase.equals("WAIT_CREATED")) {
                        throw new SecurityException("Activity creation does not match its queued operation");
                    }
                    try {
                        requireAccountUi(creation);
                        if (creation.accountUi != null) {
                            creation.accountUi.consume(record.capability, creation.capability, creation.id);
                            record.accountUiDelivered = true;
                        }
                    } catch (Exception denied) { fail(creation, "Account UI creation authorization failed"); throw denied; }
                    creation.payloadClaimed = true;
                } else if (record.accountUi != null) record.accountUi.consume(record.capability);
                record.token = token; record.taskId = actualTask; record.state = "CREATING";
                record.task.physicalId = actualTask;
                byToken.put(token, record);
            }
            return approved(record);
        }
    }

    private Bundle newIntent(Bundle input, ProcessRegistry.Identity caller) throws Exception {
        Record record;
        synchronized (this) { record = owned(input, caller); requireToken(record, input.getBinder("token")); }
        int actualTask = tasks.taskId(record.token);
        synchronized (this) {
            if (owned(input, caller) != record || actualTask != record.taskId || actualTask != record.task.physicalId) {
                throw new SecurityException("New Intent token belongs to another task");
            }
            Bundle result = approved(record);
            if (input.getBinder("launch_operation") != null) {
                Operation delivery = operation(input, record);
                if (!delivery.phase.equals("DELIVERING") || delivery.payloadClaimed) {
                    throw new SecurityException("Activity payload is not active or was already claimed");
                }
                try {
                    requireAccountUi(delivery);
                    if (delivery.accountUi != null) {
                        delivery.accountUi.consume(record.capability, delivery.capability, delivery.id);
                        record.accountUiDelivered = true;
                    }
                } catch (Exception denied) {
                    fail(delivery, "Account UI payload authorization failed"); throw denied;
                }
                delivery.payloadClaimed = true;
                result.putParcelable("original_intent", new Intent(delivery.original)); result.putString("referrer", delivery.referrer);
            } else if (record.accountUi != null || record.accountUiDelivered) {
                throw new SecurityException("Account UI new Intent requires a fresh operation");
            }
            return result;
        }
    }

    private Bundle event(Bundle input, ProcessRegistry.Identity caller) {
        synchronized (this) {
            Record record = owned(input, caller);
            requireToken(record, input.getBinder("token"));
            long sequence = input.getLong("event_sequence");
            if (sequence <= record.lastEvent) throw new SecurityException("Stale Activity lifecycle acknowledgement");
            String event = input.getString("event");
            switch (event == null ? "" : event) {
                case "CREATED":
                    if (!record.state.equals("CREATING") && !record.state.equals("RECREATING")) {
                        throw new SecurityException("Activity creation was already acknowledged");
                    }
                    record.state = "CREATED"; break;
                case "STARTED": case "RESUMED": case "PAUSED": case "STOPPED":
                    if (record.state.equals("RESERVED") || record.state.equals("DISPATCHED")
                            || record.state.equals("CREATING") || record.state.equals("RECREATING")) {
                        throw new SecurityException("Activity lifecycle arrived before creation");
                    }
                    record.state = event; break;
                case "FINISH_REQUESTED": record.state = event; record.finishRequested = true; break;
                case "NEW_INTENT":
                    if (input.getBinder("launch_operation") == null && (record.accountUi != null || record.accountUiDelivered)) {
                        throw new SecurityException("Account UI acknowledgement requires its delivery operation");
                    }
                    if (input.getBinder("launch_operation") != null) {
                        Operation delivered = operation(input, record);
                        if (!delivered.phase.equals("DELIVERING") || !delivered.payloadClaimed) {
                            throw new SecurityException("Activity delivery was not unwrapped before acknowledgement");
                        }
                        complete(delivered);
                    }
                    record.newIntents++; break;
                case "DESTROYED":
                    if (input.getBoolean("changing_configurations")) record.state = "RECREATING";
                    else {
                        destroyedCount++; remove(record);
                        if (record.task.singleInstance && record.finishRequested && record.task.stack.isEmpty()) {
                            Task returnTo = record.task.returnTo;
                            if (returnTo != null) worker.post(() -> restoreReturnTask(returnTo));
                        }
                    }
                    break;
                default: throw new EngineException("Unsupported Activity lifecycle event");
            }
            record.lastEvent = sequence;
            if (event.equals("RESUMED")) record.task.order = ++taskOrder;
            Operation pending = record.task.queue.peek();
            if (event.equals("CREATED") && pending != null && pending.target == record
                    && pending.phase.equals("WAIT_CREATED") && pending.payloadClaimed) complete(pending);
            schedule(record.task);
            return EngineProtocol.response();
        }
    }

    private void restoreReturnTask(Task task) {
        synchronized (this) {
            if (!taskRecords.contains(task) || task.stack.isEmpty() || !task.queue.isEmpty()) return;
        }
        try { requirePhysicalTask(task).moveToFront(); }
        catch (Exception failure) { android.util.Log.e("MyliteActivities", "Cannot restore the actual singleInstance caller task", failure); }
    }

    private static void requireProtocol(Bundle input) {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) {
            throw new SecurityException("Invalid Activity protocol");
        }
    }

    private Record record(Bundle input) {
        Record record = records.get(input.getBinder(ActivityEnvelope.RECORD));
        if (record == null) throw new SecurityException("Activity record is unknown, expired or finished");
        if (record.target.generation != input.getLong("generation")
                || !record.target.packageGeneration.equals(input.getString("package_generation"))) {
            throw new SecurityException("Activity record generation does not match");
        }
        return record;
    }

    private Record owned(Bundle input, ProcessRegistry.Identity caller) {
        Record record = record(input);
        if (!record.target.same(caller)) throw new SecurityException("Activity record belongs to another guest process");
        return record;
    }

    private static void requireToken(Record record, IBinder token) {
        if (record.token == null || !record.token.equals(token)) throw new SecurityException("Activity token does not match its record");
    }

    private Bundle approved(Record record) {
        Bundle result = new Bundle(record.identity);
        result.putParcelable("original_intent", new Intent(record.original));
        result.putString("referrer", record.source == null ? engine.hostContext().getPackageName() : record.source.packageName);
        result.putInt("task_id", record.taskId);
        return result;
    }

    private void remove(Record record) {
        if (records.get(record.capability) != record) return;
        if (record.accountUi != null) record.accountUi.cancel(record.capability, false);
        records.remove(record.capability);
        if (record.token != null && byToken.get(record.token) == record) byToken.remove(record.token);
        record.task.stack.remove(record);
        for (Operation operation : new ArrayList<>(operations.values())) {
            if (operation.target == record && !isTerminal(operation)) fail(operation, "Activity target was removed");
        }
        if (record.task.stack.isEmpty() && record.task.queue.isEmpty()) taskRecords.remove(record.task);
        schedule(record.task);
    }

    @Override public synchronized void died(ProcessRegistry.Identity identity) {
        for (Operation operation : new ArrayList<>(operations.values())) {
            if (operation.accountUi != null && identity.same(operation.requester) && !isTerminal(operation)) {
                fail(operation, "Account UI requesting process died");
            }
        }
        for (Record record : new ArrayList<>(records.values())) {
            if (record.target.same(identity) || (record.token == null && identity.same(record.source))) remove(record);
        }
    }

    private void prune() throws Exception {
        ArrayList<Record> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(records.values()); }
        Map<String, String> current = new HashMap<>();
        for (Record record : snapshot) if (!current.containsKey(record.target.packageName)) {
            InstalledPackage installed = engine.packages().get(record.target.packageName);
            current.put(record.target.packageName, installed == null ? null : installed.baseApk.getAbsolutePath());
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            for (Record record : snapshot) if (!record.target.client.isBinderAlive()
                    || !record.target.packageGeneration.equals(current.get(record.target.packageName))
                    || (record.token == null && now - record.reservedAt > UNCLAIMED_TIMEOUT_MS)) remove(record);
        }
    }

    private synchronized Bundle inspect() {
        Bundle result = EngineProtocol.response();
        ArrayList<Bundle> entries = new ArrayList<>();
        for (Record record : records.values()) {
            Bundle entry = new Bundle(record.identity);
            entry.putString("state", record.state);
            entry.putInt("pid", record.target.pid);
            entry.putInt("task_id", record.taskId);
            entry.putLong("last_event", record.lastEvent);
            entry.putInt("new_intents", record.newIntents);
            entries.add(entry);
        }
        result.putParcelableArrayList("records", entries);
        result.putLong("destroyed_count", destroyedCount);
        ArrayList<Bundle> pending = new ArrayList<>();
        for (Operation operation : operations.values()) {
            Bundle entry = new Bundle(operation.target.identity);
            entry.putLong("operation_id", operation.id);
            entry.putBinder("launch_operation", operation.capability);
            entry.putBoolean("payload_claimed", operation.payloadClaimed);
            entry.putString("phase", operation.phase);
            entry.putInt("task_id", operation.task.physicalId);
            if (operation.failure != null) entry.putString("failure", operation.failure);
            pending.add(entry);
        }
        result.putParcelableArrayList("operations", pending);
        return result;
    }
}
