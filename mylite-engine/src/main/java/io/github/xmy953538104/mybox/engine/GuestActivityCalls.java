package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pins Activity targets before crossing the physical Android boundary. */
final class GuestActivityCalls implements InvocationHandler {
    private final Engine engine;
    private final GuestRuntime runtime;
    private final Object original;

    GuestActivityCalls(Engine engine, GuestRuntime runtime, Object original) {
        this.engine = engine;
        this.runtime = runtime;
        this.original = original;
    }

    static void install(Engine engine, GuestRuntime runtime) throws Exception {
        String managerName = Build.VERSION.SDK_INT >= 29 ? "android.app.ActivityTaskManager" : "android.app.ActivityManager";
        String contractName = Build.VERSION.SDK_INT >= 29 ? "android.app.IActivityTaskManager" : "android.app.IActivityManager";
        String singletonName = Build.VERSION.SDK_INT >= 29 ? "IActivityTaskManagerSingleton" : "IActivityManagerSingleton";
        Class<?> manager = Class.forName(managerName);
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(manager, "getService"));
        Object singleton = RuntimeReflection.field(manager, singletonName).get(null);
        Class<?> contract = Class.forName(contractName);
        RuntimeReflection.set(singleton, "mInstance", Proxy.newProxyInstance(contract.getClassLoader(),
                new Class<?>[]{contract}, new GuestActivityCalls(engine, runtime, original)));
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("startActivity") || name.equals("startActivityWithFeature") || name.equals("startActivityAsUser")) {
            Object[] routed = args.clone();
            int intentIndex = -1;
            for (int i = 0; i < routed.length; i++) if (routed[i] instanceof Intent) { intentIndex = i; break; }
            if (intentIndex < 0) throw new EngineException("Unsupported Activity IPC signature");
            String resolvedType = intentIndex + 1 < routed.length && routed[intentIndex + 1] instanceof String
                    ? (String) routed[intentIndex + 1] : null;
            Intent intent = explicitTarget((Intent) routed[intentIndex], resolvedType);
            routed[intentIndex] = intent;
            ComponentName component = intent.getComponent();
            String targetPackage = component == null ? intent.getPackage() : component.getPackageName();
            if (ServerAccountUiTickets.hasTicket(intent) && (targetPackage == null || engine.packages().get(targetPackage) == null)) {
                throw new SecurityException("Account UI tickets cannot enter a physical Activity");
            }
            if (targetPackage != null && engine.packages().get(targetPackage) != null) {
                if (!runtime.packageInfo().packageName.equals(targetPackage) && !ServerAccountUiTickets.hasTicket(intent)) {
                    throw new SecurityException("Cross-package Activity authorization is not implemented");
                }
                int resultIndex = intentIndex + 2;
                Class<?>[] types = method.getParameterTypes();
                if (resultIndex + 3 >= routed.length || types[resultIndex] != IBinder.class
                        || types[resultIndex + 1] != String.class || types[resultIndex + 2] != int.class
                        || types[resultIndex + 3] != int.class) throw new EngineException("Unsupported Activity result signature");
                if (((Integer) routed[resultIndex + 3]) != 0) throw new EngineException("Activity start modifiers are not implemented");
                for (Object argument : routed) if (argument instanceof Bundle && !((Bundle) argument).isEmpty()) {
                    throw new EngineException("Custom Activity task/display options are not implemented");
                }
                if (name.equals("startActivityAsUser") && !Integer.valueOf(0).equals(routed[routed.length - 1])) {
                    throw new SecurityException("Only virtual Activity user 0 is supported");
                }
                Bundle request = EngineProtocol.response();
                request.putString("operation", "prepare");
                request.putParcelable("intent", intent);
                request.putBinder("source_token", (IBinder) routed[resultIndex]);
                request.putString("result_who", (String) routed[resultIndex + 1]);
                request.putInt("request_code", (Integer) routed[resultIndex + 2]);
                Bundle launch = EngineProtocol.request(engine.server(), EngineProtocol.SERVER,
                        EngineProtocol.PREPARE_ACTIVITY, request);
                if (launch.getBoolean("queued")) return 0; // Framework lifecycle completion is acknowledged asynchronously.
                Intent stub = launch.getParcelable("outer_intent");
                if (stub == null) throw new EngineException("Server returned no Activity envelope");
                routed[intentIndex] = stub;
                routed[intentIndex + 1] = stub.getType();
                if (!(routed[1] instanceof String)) throw new EngineException("Unsupported Activity caller signature");
                routed[1] = engine.hostContext().getPackageName();
                Bundle outcome = ActivityEnvelope.operation(launch, "start_result", null);
                Object result;
                try {
                    result = method.invoke(original, routed);
                    int code = ((Number) result).intValue();
                    outcome.putBoolean("accepted", code >= 0 && code < 100);
                    outcome.putBoolean("definite_failure", code < 0 || code >= 100);
                } catch (Throwable failure) {
                    Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
                    outcome.putBoolean("accepted", false);
                    try { EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.PREPARE_ACTIVITY, outcome); }
                    catch (RuntimeException reportFailure) { cause.addSuppressed(reportFailure); }
                    throw cause;
                }
                EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.PREPARE_ACTIVITY, outcome);
                return result;
            } else if (GuestPackageQueries.isGoogleIdentity(targetPackage)) {
                throw new EngineException("Google Activity is not installed in the container");
            }
            if (!(routed[1] instanceof String)) throw new EngineException("Unsupported Activity caller signature");
            routed[1] = engine.hostContext().getPackageName();
            args = routed;
        } else if (name.startsWith("startActivit") || name.equals("startNextMatchingActivity")) {
            throw new EngineException("Activity entry point is not implemented: " + name);
        } else if (name.equals("getActivityClientController")) {
            try { return runtime.activities().wrapController(method.invoke(original, args)); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        } else if (GuestActivities.handlesController(name)) {
            return runtime.activities().controllerCall(original, method, args);
        } else if (name.equals("getAppTasks")) {
            if (args == null || args.length != 1 || !runtime.packageInfo().packageName.equals(args[0]))
                throw new SecurityException("AppTasks package differs from the calling guest");
            Bundle request = EngineProtocol.response(); request.putString("operation", "app_tasks");
            Bundle response = EngineProtocol.request(engine.server(), EngineProtocol.SERVER,
                    EngineProtocol.PREPARE_ACTIVITY, request);
            java.util.ArrayList<Bundle> entries = response.getParcelableArrayList("tasks");
            if (entries == null) throw new EngineException("Server returned no AppTasks collection");
            java.util.ArrayList<IBinder> result = new java.util.ArrayList<>();
            for (Bundle entry : entries) {
                IBinder capability = entry.getBinder("task");
                if (capability == null) throw new EngineException("Server returned an invalid AppTask capability");
                result.add(capability);
            }
            return result;
        } else if (name.equals("removeTask") || name.equals("moveTaskToFront")) {
            throw new EngineException("Guest task management is not implemented: " + name);
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private Intent explicitTarget(Intent original, String resolvedType) throws Exception {
        if (original.getSelector() != null || Intent.ACTION_CHOOSER.equals(original.getAction())) {
            throw new EngineException("Activity selectors and chooser delegation are not implemented");
        }
        ComponentName explicit = original.getComponent();
        if (explicit != null) {
            if (isResolver(explicit.getClassName())) throw new EngineException("System resolver delegation is not implemented");
            return new Intent(original);
        }
        Intent query = new Intent(original).setDataAndType(original.getData(), resolvedType);
        Map<ComponentName, ActivityInfo> candidates = new LinkedHashMap<>();
        for (ResolveInfo match : engine.hostContext().getPackageManager()
                .queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)) {
            ActivityInfo info = match.activityInfo;
            if (info == null || info.applicationInfo == null || !info.enabled || !info.applicationInfo.enabled
                    || info.packageName == null || info.name == null || isResolver(info.name)) continue;
            if (original.getPackage() != null && !original.getPackage().equals(info.packageName)) continue;
            boolean virtual = engine.packages().get(info.packageName) != null;
            if (GuestPackageQueries.isGoogleIdentity(info.packageName) && !virtual) continue;
            candidates.put(new ComponentName(info.packageName, info.name), info);
        }
        if (candidates.isEmpty()) throw new ActivityNotFoundException("No permitted Activity matches this Intent");
        if (candidates.size() != 1) {
            throw new EngineException("Multiple Activities match; an explicit component is required");
        }
        ComponentName selected = candidates.keySet().iterator().next();
        // Never delegate a second implicit resolution to the OS or its ResolverActivity.
        return new Intent(original).setPackage(selected.getPackageName()).setComponent(selected);
    }

    private static boolean isResolver(String className) {
        return "com.android.internal.app.ResolverActivity".equals(className)
                || "com.android.internal.app.ChooserActivity".equals(className);
    }
}
