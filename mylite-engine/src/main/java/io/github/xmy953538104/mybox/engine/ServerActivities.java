package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Standard-Activity reservations and real framework lifecycle, owned by the server process. */
final class ServerActivities implements ProcessRegistry.DeathListener {
    interface TaskLookup { int taskId(IBinder token) throws Exception; }
    private static final int MAX_RECORDS = 256;
    private static final long UNCLAIMED_TIMEOUT_MS = 60000;
    private final Engine engine;
    private final ProcessRegistry processes;
    private final TaskLookup tasks;
    private final Map<IBinder, Record> records = new LinkedHashMap<>();
    private final Map<IBinder, Record> byToken = new HashMap<>();
    private long nextId = 1, destroyedCount;

    private static final class Record {
        final Binder capability = new Binder();
        final long id, reservedAt = SystemClock.elapsedRealtime();
        final ProcessRegistry.Identity target, source;
        final int hostPid, expectedTask, requestCode;
        final ComponentName component, sourceComponent;
        final Intent original;
        final Bundle identity;
        final String resultWho;
        IBinder token;
        int taskId = -1;
        long lastEvent;
        int newIntents;
        String state = "RESERVED";
        boolean dispatched;
        Record(long id, ProcessRegistry.Identity target, ProcessRegistry.Identity source, int hostPid,
               ComponentName component, Intent original, Record sourceRecord, String resultWho, int requestCode) {
            this.id = id; this.target = target; this.source = source; this.hostPid = hostPid;
            this.component = component; this.original = new Intent(original);
            this.expectedTask = sourceRecord == null ? -1 : sourceRecord.taskId;
            this.sourceComponent = sourceRecord == null ? null : sourceRecord.component;
            this.resultWho = resultWho; this.requestCode = requestCode;
            identity = EngineProtocol.response();
            identity.putBinder(ActivityEnvelope.RECORD, capability);
            identity.putString("package", target.packageName);
            identity.putString("activity", component.getClassName());
            identity.putString("package_generation", target.packageGeneration);
            identity.putLong("generation", target.generation);
            identity.putInt("slot", target.slot);
            identity.putLong("record_id", id);
        }
    }

    ServerActivities(Engine engine, ProcessRegistry processes) {
        this(engine, processes, GuestActivities::physicalTaskId);
    }

    ServerActivities(Engine engine, ProcessRegistry processes, TaskLookup tasks) {
        this.engine = engine; this.processes = processes; this.tasks = tasks;
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
        if ("new_intent".equals(operation)) {
            synchronized (this) {
                Record record = owned(input, caller);
                requireToken(record, input.getBinder("token"));
                return approved(record);
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
        if (original == null || original.getComponent() == null || original.getSelector() != null
                || original.getClipData() != null) throw new SecurityException("An explicit, ungranted guest Activity is required");
        ComponentName component = original.getComponent();
        if (caller != null && !caller.packageName.equals(component.getPackageName())) {
            throw new SecurityException("Cross-package Activity authorization is not implemented");
        }
        GoogleLaunchPolicy.require(engine, component.getPackageName());
        ActivityInfo activity = findActivity(component);
        requireStandard(activity, original, caller == null);
        Record source = null;
        if (caller != null) {
            synchronized (this) {
                source = byToken.get(input.getBinder("source_token"));
                if (source == null || !source.target.same(caller) || source.state.equals("RECREATING")) {
                    throw new SecurityException("Activity source token is not owned by this guest process");
                }
            }
        }
        Bundle acquire = EngineProtocol.response();
        acquire.putString("package", component.getPackageName());
        acquire.putString("process", activity.processName == null ? activity.packageName : activity.processName);
        Bundle target = processes.acquire(acquire);
        ProcessRegistry.Identity assigned = processes.identity(Process.myUid(), target.getInt("pid"));
        if (!assigned.packageGeneration.equals(activity.applicationInfo.sourceDir)
                || (caller != null && (!caller.packageGeneration.equals(assigned.packageGeneration)
                || !caller.same(processes.identity(Process.myUid(), caller.pid))))) {
            throw new SecurityException("Activity package/process changed while preparing its launch");
        }
        Record record;
        synchronized (this) {
            if (!assigned.client.isBinderAlive() || (source != null && byToken.get(source.token) != source)) {
                throw new SecurityException("Activity source or target died during preparation");
            }
            if (records.size() >= MAX_RECORDS) throw new EngineException("Too many outstanding Activity records");
            record = new Record(nextId++, assigned, caller, hostPid, component, original, source,
                    input.getString("result_who"), input.getInt("request_code", -1));
            records.put(record.capability, record);
        }
        Bundle result = new Bundle(record.identity);
        result.putParcelable("outer_intent", ActivityEnvelope.create(engine.hostContext(), component,
                original.getFlags(), record.identity));
        return result;
    }

    private ActivityInfo findActivity(ComponentName component) throws Exception {
        PackageInfo pkg = engine.packages().getPackageInfo(component.getPackageName(), PackageManager.GET_ACTIVITIES);
        if (pkg != null && pkg.activities != null) for (ActivityInfo activity : pkg.activities) {
            if (component.getClassName().equals(activity.name) && activity.enabled
                    && activity.applicationInfo != null && activity.applicationInfo.enabled) return activity;
        }
        throw new EngineException("Guest Activity is absent or disabled: " + component);
    }

    static void requireStandard(ActivityInfo info, Intent intent, boolean host) {
        int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        int reuse = Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_FORWARD_RESULT
                | Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP | Intent.FLAG_ACTIVITY_TASK_ON_HOME;
        if (!host) reuse |= Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
        if (info.launchMode != ActivityInfo.LAUNCH_MULTIPLE || (intent.getFlags() & (reuse | grants)) != 0
                || (host && (intent.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                != (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK))) {
            throw new EngineException("Guest task reuse, URI grants and non-standard launch modes are not implemented");
        }
    }

    private Bundle startResult(Bundle input, ProcessRegistry.Identity caller, int hostPid) {
        synchronized (this) {
            Record record = record(input);
            if (caller == null ? record.source != null || record.hostPid != hostPid : !caller.same(record.source)) {
                throw new SecurityException("Activity start result belongs to another caller");
            }
            boolean accepted = input.getBoolean("accepted");
            if (!accepted && record.token == null) remove(record);
            else if (accepted) {
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
            if (record.token == null) {
                record.token = token; record.taskId = actualTask; record.state = "CREATING";
                byToken.put(token, record);
            }
            return approved(record);
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
                case "FINISH_REQUESTED": record.state = event; break;
                case "NEW_INTENT": record.newIntents++; break;
                case "DESTROYED":
                    if (input.getBoolean("changing_configurations")) record.state = "RECREATING";
                    else { destroyedCount++; remove(record); }
                    break;
                default: throw new EngineException("Unsupported Activity lifecycle event");
            }
            record.lastEvent = sequence;
            return EngineProtocol.response();
        }
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
        records.remove(record.capability);
        if (record.token != null && byToken.get(record.token) == record) byToken.remove(record.token);
    }

    @Override public synchronized void died(ProcessRegistry.Identity identity) {
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
        return result;
    }
}
