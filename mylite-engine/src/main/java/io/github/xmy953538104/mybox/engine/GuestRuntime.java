package io.github.xmy953538104.mybox.engine;

import android.app.Application;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.accounts.GuestAccounts;

/** One immutable guest identity per OS process; binding occurs on the Android main thread. */
final class GuestRuntime {
    private final Engine engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final GuestComponents components = new GuestComponents(this);
    private final GuestActivities activities;
    private volatile String state = "EMPTY";
    private String packageName;
    private String processName;
    private String packageGeneration;
    private long generation;
    private volatile int serverPid;
    private volatile IBinder boundServer;
    private int assignedSlot;
    private PackageInfo packageInfo;
    private Object loadedApk;
    private Application application;
    private volatile LocalProviders localProviders;
    private volatile WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private String failure;
    private volatile int discardedActivityLaunches;

    boolean canDiscardPhysicalActivity(android.content.Intent outer, String declaredClass) {
        Bundle envelope = outer == null ? null : outer.getBundleExtra(EngineProtocol.LAUNCH);
        // Current-owner launches still take the full normal claim path, without an extra Binder probe.
        if (envelope != null && envelope.getBinder(ActivityEnvelope.OWNER) == client
                && !"EMPTY".equals(state) && !"PREPARED".equals(state)) return false;
        if (!ActivityEnvelope.owned(engine.hostContext(), outer, declaredClass)) return false;
        return "EMPTY".equals(state) || "PREPARED".equals(state)
                || ActivityEnvelope.recordDied(outer.getBundleExtra(EngineProtocol.LAUNCH));
    }

    boolean discardPhysicalActivity(android.content.Intent outer, String declaredClass) {
        if (!canDiscardPhysicalActivity(outer, declaredClass)) return false;
        discardedActivityLaunches++; return true;
    }

    private final Binder client = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(EngineProtocol.GUEST);
                return true;
            }
            EngineProtocol.requireCaller();
            if (Binder.getCallingPid() != serverPid) throw new SecurityException("Guest commands require its engine server");
            data.enforceInterface(EngineProtocol.GUEST);
            Bundle input = data.readBundle(getClass().getClassLoader());
            Bundle result;
            try {
                if (code == EngineProtocol.DESCRIBE) result = describe();
                else if (code == EngineProtocol.BIND_APPLICATION) result = onMain(GuestRuntime.this::bindOnMainThread, input);
                else if (code >= EngineProtocol.START_SERVICE && code <= EngineProtocol.ACQUIRE_PROVIDER) {
                    result = onMain(() -> components.dispatch(code, input));
                }
                else if (code == EngineProtocol.DELIVER_BROADCAST) result = GuestBroadcastCalls.deliver(input);
                else if (code == EngineProtocol.DELIVER_RECEIVER) result = GuestReceiverDelivery.deliver(GuestRuntime.this, input);
                else if (code == EngineProtocol.ACTIVITY_COMMAND) result = activities.enqueue(input);
                else return false;
            } catch (Exception failure) {
                result = EngineProtocol.failure(failure);
            }
            reply.writeNoException();
            reply.writeBundle(result);
            return true;
        }
    };

    GuestRuntime(Engine engine) {
        this.engine = engine;
        activities = new GuestActivities(engine, this);
        client.attachInterface(null, EngineProtocol.GUEST);
    }

    Bundle prepare(Bundle input) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) {
            throw new EngineException("Invalid guest bootstrap protocol");
        }
        final Bundle request = new Bundle(input);
        request.putInt("server_pid", Binder.getCallingPid());
        IBinder current = boundServer;
        if (current != null && current != request.getBinder("server_binder") && !current.isBinderAlive()) {
            retireWithServer(current);
            throw new android.os.DeadObjectException();
        }
        return onMain(() -> prepareOnMainThread(request), request);
    }

    Bundle onMain(Callable<Bundle> action) throws Exception {
        return onMain(action, null);
    }

    private Bundle onMain(Callable<Bundle> action, Bundle budget) throws Exception {
        long remaining = AlarmDeliveryTicket.remaining(budget, 30_000);
        FutureTask<Bundle> task = new FutureTask<>(() -> {
            AlarmDeliveryTicket.remaining(budget, 30_000);
            return action.call();
        });
        if (Looper.myLooper() == Looper.getMainLooper()) task.run(); else main.post(task);
        try {
            return task.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            task.cancel(false);
            throw new EngineException("Guest main-thread work exceeded its remaining budget", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new EngineException("Guest Application failed", cause);
        }
    }

    private Bundle prepareOnMainThread(Bundle request) throws Exception {
        String requestedPackage = request.getString("package");
        String requestedProcess = request.getString("process");
        String requestedPackageGeneration = request.getString("package_generation");
        long requestedGeneration = request.getLong("generation");
        int slot = request.getInt("slot", -1);
        IBinder requestedServer = request.getBinder("server_binder");
        if (requestedPackage == null || requestedProcess == null || requestedPackageGeneration == null || slot < 0
                || slot >= EngineProtocol.SLOT_COUNT || requestedGeneration <= 0 || requestedServer == null || !requestedServer.isBinderAlive()) {
            throw new EngineException("Guest binding identity is incomplete");
        }
        String physicalProcess = engine.hostContext().getPackageName() + ":engine_p" + slot;
        if (!physicalProcess.equals(currentProcessName())) {
            throw new EngineException("Guest slot does not match the running OS process");
        }
        if (!"EMPTY".equals(state)) {
            if (requestedPackage.equals(packageName) && requestedProcess.equals(processName)
                    && requestedGeneration == generation && requestedPackageGeneration.equals(packageGeneration)
                    && serverPid == request.getInt("server_pid") && requestedServer.equals(boundServer)
                    && ("BOUND".equals(state) || "PREPARED".equals(state))) {
                return handshake();
            }
            throw new EngineException("Guest process is already sealed: " + state + ", " + failure);
        }
        InstalledPackage installed = engine.packages().get(requestedPackage);
        if (installed == null || !requestedPackageGeneration.equals(installed.baseApk.getAbsolutePath())) {
            throw new EngineException("Guest package changed during process reservation");
        }
        packageName = requestedPackage;
        processName = requestedProcess;
        packageGeneration = requestedPackageGeneration;
        generation = requestedGeneration;
        assignedSlot = slot;
        serverPid = request.getInt("server_pid");
        boundServer = requestedServer;
        state = "PREPARED";
        requestedServer.linkToDeath(() -> retireWithServer(requestedServer), 0);
        if (!requestedServer.isBinderAlive()) retireWithServer(requestedServer);
        return handshake();
    }

    private void retireWithServer(IBinder expected) {
        // Never retire an ordinary host runtime, or a guest because some other/new server died.
        if (boundServer == expected && !expected.isBinderAlive() && !"EMPTY".equals(state)) {
            android.util.Log.w("MyliteProcesses", "Retiring a sealed guest after its exact engine server died");
            Process.killProcess(Process.myPid());
        }
    }

    Bundle requestBoundServer(int operation, Bundle input) {
        IBinder expected = boundServer;
        if (expected == null) throw new EngineException("Guest has no bound engine server");
        if (!expected.isBinderAlive()) retireWithServer(expected);
        try { return EngineProtocol.request(expected, EngineProtocol.SERVER, operation, input); }
        catch (EngineException failure) {
            if (failure.getCause() instanceof android.os.DeadObjectException) retireWithServer(expected);
            throw failure;
        }
    }

    boolean hasFocusedGuestActivity() {
        IBinder server = boundServer;
        Activity candidate = resumedActivity.get();
        return "BOUND".equals(state) && server != null && server.isBinderAlive()
                && candidate != null && !candidate.isFinishing() && !candidate.isDestroyed()
                && candidate.hasWindowFocus() && activities.ownsCurrentActivity(candidate);
    }

    private Bundle bindOnMainThread() throws Exception {
        if ("BOUND".equals(state)) return describe();
        if (!"PREPARED".equals(state)) throw new EngineException("Guest is not prepared: " + state);
        state = "BINDING";
        try {
            InstalledPackage installed = engine.packages().get(packageName);
            if (installed == null) throw new EngineException("Guest package is not installed");
            if (!packageGeneration.equals(installed.baseApk.getAbsolutePath())) {
                throw new EngineException("Guest package changed during process reservation");
            }
            int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                    | PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS | PackageManager.GET_META_DATA
                    | PackageManager.MATCH_DISABLED_COMPONENTS;
            packageInfo = engine.packages().getPackageInfo(packageName, flags);
            if (packageInfo == null || packageInfo.applicationInfo == null) {
                throw new EngineException("Guest package metadata could not be parsed");
            }
            ApplicationInfo appInfo = packageInfo.applicationInfo;
            appInfo.uid = Process.myUid();
            appInfo.processName = processName;
            NativeBridge.Result nativeResult = NativeBridge.initializeGuest(engine.hostContext(), appInfo);
            if (!nativeResult.supported || !nativeResult.ioEnabled || nativeResult.errno != 0) {
                throw new EngineException("Guest native I/O initialization failed (errno "
                        + nativeResult.errno + "): " + nativeResult.detail);
            }
            Object thread = RuntimeReflection.activityThread();
            GuestPackageQueries.install(engine, thread);
            GuestComponentCalls.install(engine, this);
            PhysicalProviderCalls.installCachedSettings(engine.hostContext(), packageName);
            GuestBroadcastCalls.install(engine, this);
            GuestPendingIntents.install(engine);
            GuestActivityCalls.install(engine, this);
            Bundle serviceRequest = EngineProtocol.response();
            serviceRequest.putString("service", "accounts");
            Bundle accountService = EngineProtocol.request(engine.server(), EngineProtocol.SERVER,
                    EngineProtocol.GET_SERVICE, serviceRequest);
            GuestAccounts.install(engine.hostContext(), packageName, accountService.getBinder("service"));
            Class<?> compatibility = Class.forName("android.content.res.CompatibilityInfo");
            GuestSharedLibraries.apply(engine, appInfo);
            Field defaultInfo = RuntimeReflection.field(compatibility, "DEFAULT_COMPATIBILITY_INFO");
            loadedApk = RuntimeReflection.invoke(thread, RuntimeReflection.method(thread.getClass(),
                    "getPackageInfoNoCheck", ApplicationInfo.class, compatibility), appInfo, defaultInfo.get(null));
            if (loadedApk == null) throw new EngineException("Android did not create a guest LoadedApk");
            cacheLoadedPackage(thread, "mPackages");
            cacheLoadedPackage(thread, "mResourcePackages");
            Object bound = RuntimeReflection.get(thread, "mBoundApplication");
            RuntimeReflection.set(bound, "appInfo", appInfo);
            RuntimeReflection.set(bound, "processName", processName);
            RuntimeReflection.set(bound, "info", loadedApk);
            List<ProviderInfo> providers = providersForProcess();
            RuntimeReflection.set(bound, "providers", providers);
            LaunchInterceptor.install(thread, this);
            application = (Application) RuntimeReflection.invoke(loadedApk,
                    RuntimeReflection.method(loadedApk.getClass(), "makeApplication",
                            boolean.class, Instrumentation.class), false, null);
            if (application == null) throw new EngineException("Guest Application construction returned null");
            application.registerActivityLifecycleCallbacks(activities);
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) { resumedActivity = new WeakReference<>(activity); }
                @Override public void onActivityPaused(Activity activity) {
                    if (resumedActivity.get() == activity) resumedActivity = new WeakReference<>(null);
                }
                @Override public void onActivityCreated(Activity activity, Bundle saved) { }
                @Override public void onActivityStarted(Activity activity) { }
                @Override public void onActivityStopped(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) { }
                @Override public void onActivityDestroyed(Activity activity) { }
            });
            RuntimeReflection.set(thread, "mInitialApplication", application);
            localProviders = new LocalProviders(application, thread, processName, providers);
            localProviders.installAll();
            Instrumentation instrumentation = (Instrumentation) RuntimeReflection.get(thread, "mInstrumentation");
            instrumentation.callApplicationOnCreate(application);
            state = "BOUND";
            return handshake();
        } catch (Throwable error) {
            state = "FAILED";
            failure = error.getClass().getSimpleName() + ": " + error.getMessage();
            android.util.Log.e("MyliteProcesses", "Guest Application binding failed: " + packageName, error);
            throw new EngineException("Binding " + packageName + " failed: " + failure, error);
        }
    }

    @SuppressWarnings("unchecked")
    private void cacheLoadedPackage(Object thread, String field) throws Exception {
        Map<String, WeakReference<Object>> packages = (Map<String, WeakReference<Object>>)
                RuntimeReflection.get(thread, field);
        synchronized (packages) { packages.put(packageName, new WeakReference<>(loadedApk)); }
    }

    private List<ProviderInfo> providersForProcess() {
        List<ProviderInfo> providers = new ArrayList<>();
        if (packageInfo.providers != null) {
            for (ProviderInfo provider : packageInfo.providers) {
                String owner = provider.processName == null ? packageName : provider.processName;
                if (provider.enabled && processName.equals(owner)) providers.add(provider);
            }
        }
        return providers;
    }

    private static String currentProcessName() throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        return (String) RuntimeReflection.invoke(null, RuntimeReflection.method(thread, "currentProcessName"));
    }

    ActivityInfo activity(Bundle request) {
        if (!"BOUND".equals(state) || request == null
                || request.getLong("generation") != generation
                || !packageGeneration.equals(request.getString("package_generation"))
                || !packageName.equals(request.getString("package"))) {
            throw new EngineException("Launch does not match the bound guest generation");
        }
        String requested = request.getString("activity");
        if (packageInfo.activities != null) {
            for (ActivityInfo info : packageInfo.activities) {
                // The authenticated server checks current enabled state; live records can outlast a self-disable.
                if (info.name.equals(requested)) {
                    String process = info.processName == null ? packageName : info.processName;
                    if (!processName.equals(process)) throw new EngineException("Activity belongs to another guest process");
                    return new ActivityInfo(info);
                }
            }
        }
        throw new EngineException("Activity is absent from the committed package: " + requested);
    }

    Object loadedApk() { return loadedApk; }
    ClassLoader classLoader() { return application.getClassLoader(); }
    Application application() {
        if (application == null) throw new EngineException("Guest Application is not ready");
        return application;
    }
    PackageInfo packageInfo() { return packageInfo; }
    GuestComponents components() { return components; }
    GuestActivities activities() { return activities; }
    Object localProvider(ProviderInfo info, String authority, int userId) throws Exception {
        LocalProviders providers = localProviders;
        if (providers == null || !("BINDING".equals(state) || "BOUND".equals(state))) {
            throw new EngineException("Local Provider requested before its Application is ready: " + authority);
        }
        return providers.acquire(info, authority, userId);
    }
    IBinder clientBinder() { return client; }
    boolean ownsProcess(String process) { return processName.equals(process == null ? packageName : process); }
    int slot() { return assignedSlot; }
    int serverPid() { return serverPid; }
    void post(Runnable action) { main.post(action); }

    private Bundle handshake() {
        Bundle result = describe();
        result.putBinder("client", client);
        return result;
    }

    private Bundle describe() {
        Bundle result = EngineProtocol.response();
        result.putString("state", state);
        result.putString("package", packageName);
        result.putString("process", processName);
        result.putString("package_generation", packageGeneration);
        result.putInt("pid", Process.myPid());
        result.putLong("generation", generation);
        result.putInt("discarded_activity_launches", discardedActivityLaunches);
        if (application != null) result.putString("application_class", application.getClass().getName());
        Activity resumed = resumedActivity.get();
        if (resumed != null) {
            result.putString("resumed_activity", resumed.getClass().getName());
            result.putString("resumed_package", resumed.getPackageName());
        }
        if (failure != null) result.putString("error", failure);
        return result;
    }
}
