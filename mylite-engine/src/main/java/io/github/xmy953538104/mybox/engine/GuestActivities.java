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
import io.github.xmy953538104.mybox.engine.accounts.GuestAuthenticatorBridge;

/** Associates real ActivityThread tokens with authenticated records, without replacing Activity code. */
final class GuestActivities implements Application.ActivityLifecycleCallbacks {
    private final Engine engine;
    private final GuestRuntime runtime;
    private final Map<IBinder, Entry> entries = new HashMap<>();

    private static final class Entry {
        final Bundle identity;
        long eventSequence;
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
        if (original == null || !new ComponentName(info.packageName, info.name).equals(original.getComponent())) {
            throw new SecurityException("Server Activity snapshot is incomplete");
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

    void newIntentDelivered(IBinder token) {
        synchronized (entries) { if (!entries.containsKey(token)) return; }
        event(token, "NEW_INTENT", false);
    }

    private Entry entry(IBinder token) {
        synchronized (entries) {
            Entry entry = entries.get(token);
            if (entry == null) throw new SecurityException("Physical Activity token has no guest record");
            return entry;
        }
    }

    private Bundle request(Bundle request) {
        return EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.PREPARE_ACTIVITY, request);
    }

    private void event(IBinder token, String event, boolean changingConfigurations) {
        Entry entry = entry(token);
        Bundle request = ActivityEnvelope.operation(entry.identity, "event", token);
        synchronized (entry) { request.putLong("event_sequence", ++entry.eventSequence); }
        request.putString("event", event);
        request.putBoolean("changing_configurations", changingConfigurations);
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

    Object controllerCall(Object original, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        IBinder token = args != null && args.length > 0 && args[0] instanceof IBinder ? (IBinder) args[0] : null;
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
