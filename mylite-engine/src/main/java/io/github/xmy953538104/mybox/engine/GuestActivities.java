package io.github.xmy953538104.mybox.engine;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import io.github.xmy953538104.mybox.engine.accounts.GuestAuthenticatorBridge;

/** Associates real ActivityThread tokens with authenticated records, without replacing Activity code. */
final class GuestActivities implements Application.ActivityLifecycleCallbacks {
    private final Engine engine;
    private final GuestRuntime runtime;
    private final Map<IBinder, Entry> entries = new HashMap<>();
    private final ThreadLocal<IBinder> discardedFinish = new ThreadLocal<>();

    private static final class Entry {
        final Bundle identity;
        long eventSequence;
        long lastCommand;
        final ArrayList<Bundle> pending = new ArrayList<>();
        Entry(Bundle identity) { this.identity = new Bundle(identity); }
    }

    GuestActivities(Engine engine, GuestRuntime runtime) { this.engine = engine; this.runtime = runtime; }

    Bundle claim(Intent outer, IBinder token) throws Exception {
        if (!ActivityEnvelope.isStub(outer) || !engine.hostContext().getPackageName().equals(outer.getComponent().getPackageName())) {
            throw new SecurityException("Activity envelope does not name this host's stub");
        }
        Bundle envelope = outer.getBundleExtra(EngineProtocol.LAUNCH);
        ActivityInfo requested = runtime.activity(envelope);
        if (envelope.getInt("slot", -1) != runtime.slot()
                || !outer.getComponent().getClassName().equals(GuestActivity.class.getName() + "$P" + runtime.slot())) {
            throw new SecurityException("Activity envelope targets another physical slot");
        }
        Intent expected = ActivityEnvelope.create(engine.hostContext(),
                new ComponentName(requested.packageName, requested.name), outer.getFlags(), envelope);
        if (!expected.filterEquals(outer) || outer.getSelector() != null || outer.getClipData() != null) {
            throw new SecurityException("Activity envelope filter identity was changed");
        }
        Bundle approved = request(ActivityEnvelope.operation(envelope, "claim", token));
        ActivityInfo info = runtime.activity(approved);
        if (!new ComponentName(info.packageName, info.name).flattenToString().equals(outer.getType())) {
            throw new SecurityException("Activity envelope filter identity was changed");
        }
        synchronized (entries) {
            Entry previous = entries.get(token);
            if (previous == null) entries.put(token, new Entry(envelope));
            else if (!previous.identity.getBinder(ActivityEnvelope.RECORD).equals(envelope.getBinder(ActivityEnvelope.RECORD))) {
                throw new SecurityException("Physical token was already assigned to another Activity");
            }
        }
        installController(); // LaunchActivityItem.preExecute may have installed a fresh physical controller.
        return approved;
    }

    Intent restore(Bundle approved) throws Exception {
        ActivityInfo info = runtime.activity(approved);
        Intent original = approved.getParcelable("original_intent");
        if (original == null || original.getComponent() == null
                || !info.packageName.equals(original.getComponent().getPackageName())) {
            throw new SecurityException("Server Activity snapshot is incomplete");
        }
        Bundle requested = new Bundle(approved);
        requested.putString("activity", original.getComponent().getClassName());
        ActivityInfo requestedInfo = runtime.activity(requested);
        if (!ActivityEnvelope.realComponent(info).equals(ActivityEnvelope.realComponent(requestedInfo))) {
            throw new SecurityException("Server Activity snapshot targets another real Activity");
        }
        Intent restored = new Intent(original);
        restored.setExtrasClassLoader(runtime.classLoader());
        GuestAuthenticatorBridge.prepareActivityIntent(restored);
        return restored;
    }

    Intent unwrapNewIntent(IBinder token, Intent incoming) throws Exception {
        Entry entry = entry(token);
        if (!ActivityEnvelope.isStub(incoming)) {
            throw new SecurityException("Unwrapped physical new Intent cannot enter a guest Activity");
        }
        Bundle envelope = incoming.getBundleExtra(EngineProtocol.LAUNCH);
        if (envelope == null || !entry.identity.getBinder(ActivityEnvelope.RECORD).equals(envelope.getBinder(ActivityEnvelope.RECORD))) {
            throw new SecurityException("New Intent belongs to a different Activity record");
        }
        Bundle approved = request(ActivityEnvelope.operation(envelope, "new_intent", token));
        Intent restored = restore(approved);
        Class<?> referrer = Class.forName("com.android.internal.content.ReferrerIntent");
        return (Intent) referrer.getConstructor(Intent.class, String.class)
                .newInstance(restored, approved.getString("referrer"));
    }

    void newIntentDelivered(IBinder token, Bundle operation) {
        synchronized (entries) { if (!entries.containsKey(token)) return; }
        event(token, "NEW_INTENT", false, operation);
    }

    /** Called on Binder pool threads. It never waits for the main thread that may be starting us. */
    Bundle enqueue(Bundle input) {
        runtime.activity(input);
        IBinder token = input.getBinder("token");
        Entry entry = entry(token);
        if (input.getBinder("launch_operation") == null
                || !entry.identity.getBinder(ActivityEnvelope.RECORD).equals(input.getBinder(ActivityEnvelope.RECORD))) {
            throw new SecurityException("Activity command does not own this token");
        }
        String action = input.getString("operation");
        if (!"finish".equals(action) && !"new_intent".equals(action)) throw new SecurityException("Unknown Activity command");
        Bundle command = new Bundle(input);
        synchronized (entry) {
            if (input.getLong("operation_id") <= entry.lastCommand) throw new SecurityException("Stale Activity command sequence");
            entry.lastCommand = input.getLong("operation_id");
        }
        runtime.post(() -> {
            try {
                if (entry(token) != entry) throw new SecurityException("Activity token was replaced");
                Object record = frameworkRecord(token);
                Activity activity = (Activity) RuntimeReflection.get(record, "activity");
                if (activity == null || activity.getTaskId() != command.getInt("task_id", -1)) {
                    throw new SecurityException("Activity command targets a different real task");
                }
                if (action.equals("finish")) activity.finish();
                else {
                    int state = (Integer) RuntimeReflection.invoke(record,
                            RuntimeReflection.method(record.getClass(), "getLifecycleState"));
                    if (state == 3 || state == 4) scheduleIntent(token, command, state == 3);
                    else synchronized (entry) { entry.pending.add(command); }
                }
            } catch (Exception failure) { commandFailure(command, failure); }
        });
        return EngineProtocol.response();
    }

    private Object frameworkRecord(IBinder token) throws Exception {
        Map<?, ?> activities = (Map<?, ?>) RuntimeReflection.get(RuntimeReflection.activityThread(), "mActivities");
        Object record = activities.get(token);
        if (record == null) throw new EngineException("ActivityThread no longer owns the target token");
        return record;
    }

    /** Read-only membership check for a real Activity, never an Activity acquisition or launch. */
    boolean ownsCurrentActivity(Activity candidate) {
        if (candidate == null) return false;
        try {
            IBinder token = token(candidate);
            if (token == null) return false;
            Entry entry;
            synchronized (entries) { entry = entries.get(token); }
            if (entry == null) return false;
            ActivityInfo info = runtime.activity(entry.identity);
            if (!info.packageName.equals(candidate.getPackageName())
                    || RuntimeReflection.get(frameworkRecord(token), "activity") != candidate) return false;
            synchronized (entries) { return entries.get(token) == entry; }
        } catch (Exception unavailable) {
            // A destroyed token or stale generation cannot authorize a clipboard read.
            return false;
        }
    }

    private Object newIntentItem(Bundle operation, boolean resume) throws Exception {
        ComponentName component = new ComponentName(operation.getString("package"), operation.getString("activity"));
        Intent envelope = ActivityEnvelope.create(engine.hostContext(), component, 0, operation);
        Class<?> referrer = Class.forName("com.android.internal.content.ReferrerIntent");
        Object outer = referrer.getConstructor(Intent.class, String.class).newInstance(envelope, engine.hostContext().getPackageName());
        Class<?> item = Class.forName("android.app.servertransaction.NewIntentItem");
        return RuntimeReflection.invoke(null, RuntimeReflection.method(item, "obtain", java.util.List.class, boolean.class),
                Collections.singletonList(outer), resume);
    }

    private void addIntent(Object transaction, Bundle operation, boolean resume) throws Exception {
        RuntimeReflection.invoke(transaction, RuntimeReflection.method(transaction.getClass(), "addCallback",
                Class.forName("android.app.servertransaction.ClientTransactionItem")), newIntentItem(operation, resume));
    }

    private void scheduleIntent(IBinder token, Bundle operation, boolean resume) throws Exception {
        Object thread = RuntimeReflection.activityThread();
        Class<?> transactionType = Class.forName("android.app.servertransaction.ClientTransaction");
        Object transaction = RuntimeReflection.invoke(null, RuntimeReflection.method(transactionType, "obtain",
                Class.forName("android.app.IApplicationThread"), IBinder.class), RuntimeReflection.get(thread, "mAppThread"), token);
        addIntent(transaction, operation, resume);
        RuntimeReflection.invoke(thread, RuntimeReflection.method(thread.getClass(), "scheduleTransaction", transactionType), transaction);
    }

    /** Add queued callbacks before a real framework resume, without inventing a resumed state. */
    void beforeTransaction(Object transaction, IBinder token) throws Exception {
        if (token == null) return;
        Entry entry;
        synchronized (entries) { entry = entries.get(token); }
        if (entry == null) return;
        Object lifecycle = RuntimeReflection.invoke(transaction,
                RuntimeReflection.method(transaction.getClass(), "getLifecycleStateRequest"));
        if (lifecycle == null || !lifecycle.getClass().getName().equals("android.app.servertransaction.ResumeActivityItem")) return;
        ArrayList<Bundle> pending;
        synchronized (entry) { pending = new ArrayList<>(entry.pending); entry.pending.clear(); }
        for (Bundle operation : pending) addIntent(transaction, operation, false);
    }

    private void commandFailure(Bundle command, Exception failure) {
        Bundle report = new Bundle(command);
        report.putString("operation", "command_failure");
        report.putString("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        try { request(report); }
        catch (RuntimeException reporting) { failure.addSuppressed(reporting); }
        android.util.Log.e("MyliteActivities", "Guest Activity command failed", failure);
    }

    private Entry entry(IBinder token) {
        synchronized (entries) {
            Entry entry = entries.get(token);
            if (entry == null) throw new SecurityException("Physical Activity token has no guest record");
            return entry;
        }
    }

    private Bundle request(Bundle request) {
        return runtime.requestBoundServer(EngineProtocol.PREPARE_ACTIVITY, request);
    }

    private void event(IBinder token, String event, boolean changingConfigurations) {
        event(token, event, changingConfigurations, null);
    }

    private void event(IBinder token, String event, boolean changingConfigurations, Bundle operation) {
        Entry entry = entry(token);
        Bundle request = ActivityEnvelope.operation(entry.identity, "event", token);
        synchronized (entry) { request.putLong("event_sequence", ++entry.eventSequence); }
        request.putString("event", event);
        request.putBoolean("changing_configurations", changingConfigurations);
        if (operation != null && operation.getBinder("launch_operation") != null) {
            request.putBinder("launch_operation", operation.getBinder("launch_operation"));
            request.putLong("operation_id", operation.getLong("operation_id"));
        }
        request(request);
        if (event.equals("DESTROYED") && !changingConfigurations) {
            synchronized (entries) { if (entries.get(token) == entry) entries.remove(token); }
        }
    }

    private static IBinder token(Activity activity) {
        try { return (IBinder) RuntimeReflection.get(activity, "mToken"); }
        catch (Exception failure) { throw new EngineException("Activity token is unavailable", failure); }
    }

    @Override public void onActivityCreated(Activity activity, Bundle saved) { event(token(activity), "CREATED", false); }
    @Override public void onActivityStarted(Activity activity) { event(token(activity), "STARTED", false); }
    @Override public void onActivityResumed(Activity activity) { event(token(activity), "RESUMED", false); }
    @Override public void onActivityPaused(Activity activity) { event(token(activity), "PAUSED", false); }
    @Override public void onActivityStopped(Activity activity) { event(token(activity), "STOPPED", false); }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) { }
    @Override public void onActivityDestroyed(Activity activity) {
        IBinder token = token(activity);
        if (activity.isChangingConfigurations()) event(token, "DESTROYED", true);
        else {
            Entry destroyed = entry(token);
            // Application callbacks run inside super.onDestroy; finish cleanup after guest code returns.
            runtime.post(() -> {
                synchronized (entries) { if (entries.get(token) != destroyed) return; }
                event(token, "DESTROYED", false);
            });
        }
    }

    static int physicalTaskId(IBinder token) throws Exception {
        Object manager;
        Class<?> contract;
        if (Build.VERSION.SDK_INT >= 31) {
            Class<?> activityClient = Class.forName("android.app.ActivityClient");
            manager = RuntimeReflection.invoke(null, RuntimeReflection.method(activityClient, "getInstance"));
            return (Integer) RuntimeReflection.invoke(manager,
                    RuntimeReflection.method(activityClient, "getTaskForActivity", IBinder.class, boolean.class), token, false);
        }
        Class<?> type = Class.forName(Build.VERSION.SDK_INT >= 29 ? "android.app.ActivityTaskManager" : "android.app.ActivityManager");
        manager = RuntimeReflection.invoke(null, RuntimeReflection.method(type, "getService"));
        contract = Class.forName(Build.VERSION.SDK_INT >= 29 ? "android.app.IActivityTaskManager" : "android.app.IActivityManager");
        return (Integer) RuntimeReflection.invoke(manager, contract.getMethod("getTaskForActivity", IBinder.class, boolean.class), token, false);
    }

    void installController() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        Class<?> activityClient = Class.forName("android.app.ActivityClient");
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(activityClient, "getActivityClientController"));
        Object wrapped = wrapController(original);
        Object singleton = RuntimeReflection.field(activityClient, "INTERFACE_SINGLETON").get(null);
        RuntimeReflection.set(singleton, "mKnownInstance", wrapped);
        RuntimeReflection.set(singleton, "mInstance", wrapped);
    }

    Object wrapController(Object original) throws Exception {
        if (Proxy.isProxyClass(original.getClass()) && Proxy.getInvocationHandler(original) instanceof Controller) return original;
        Class<?> contract = Class.forName("android.app.IActivityClientController");
        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, new Controller(original));
    }

    static boolean handlesController(String name) {
        return name.equals("finishActivity") || name.equals("getCallingActivity") || name.equals("getCallingPackage")
                || name.equals("finishActivityAffinity") || name.equals("finishSubActivity") || name.equals("navigateUpTo");
    }

    void finishDiscarded(Activity activity) {
        discardedFinish.set(token(activity));
        try { if (activity.isTaskRoot()) activity.finishAndRemoveTask(); else activity.finish(); }
        finally { discardedFinish.remove(); }
    }

    Object controllerCall(Object original, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        IBinder token = args != null && args.length > 0 && args[0] instanceof IBinder ? (IBinder) args[0] : null;
        if (name.equals("finishActivity") && token != null && token.equals(discardedFinish.get())) {
            try { return method.invoke(original, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
        if (name.equals("getCallingActivity") || name.equals("getCallingPackage")) {
            Entry entry = entry(token);
            Bundle source = request(ActivityEnvelope.operation(entry.identity, "source", token));
            return name.equals("getCallingActivity") ? source.getParcelable("component") : source.getString("package");
        }
        if (name.equals("finishActivityAffinity") || name.equals("finishSubActivity") || name.equals("navigateUpTo")) {
            throw new EngineException("Guest task-wide Activity mutation is not implemented: " + name);
        }
        if (name.equals("finishActivity")) entry(token);
        Object result;
        try { result = method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
        if (name.equals("finishActivity") && Boolean.TRUE.equals(result)) event(token, "FINISH_REQUESTED", false);
        return result;
    }

    private final class Controller implements InvocationHandler {
        private final Object original;
        Controller(Object original) { this.original = original; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return controllerCall(original, method, args);
        }
    }
}
