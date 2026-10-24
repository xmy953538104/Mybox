package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;

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
            if (targetPackage != null && engine.packages().get(targetPackage) != null) {
                if (!runtime.packageInfo().packageName.equals(targetPackage)) {
                    throw new SecurityException("Cross-package Activity authorization is not implemented");
                }
                ActivityInfo target = engine.hostContext().getPackageManager().getActivityInfo(component, 0);
                if (!target.enabled || !target.applicationInfo.enabled) throw new EngineException("Guest Activity is disabled");
                int taskFlags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                if (target.launchMode != ActivityInfo.LAUNCH_MULTIPLE || (intent.getFlags() & taskFlags) != 0) {
                    throw new EngineException("Guest task reuse and non-standard launch modes are not implemented");
                }
                Bundle launch;
                int slot;
                if (runtime.ownsProcess(target.processName)) {
                    launch = runtime.launchRequest(component.getClassName());
                    runtime.activity(launch);
                    slot = runtime.slot();
                } else {
                    Bundle request = EngineProtocol.response();
                    request.putParcelable("component", component);
                    launch = EngineProtocol.request(engine.server(), EngineProtocol.SERVER,
                            EngineProtocol.PREPARE_ACTIVITY, request);
                    slot = launch.getInt("slot", -1);
                    if (slot < 0 || slot >= EngineProtocol.SLOT_COUNT) throw new EngineException("Invalid prepared Activity slot");
                }
                launch.putParcelable("original_intent", new Intent(intent));
                Intent stub = new Intent(intent).setPackage(null).setComponent(new ComponentName(engine.hostContext(),
                        GuestActivity.class.getName() + "$P" + slot));
                stub.putExtra(EngineProtocol.LAUNCH, launch);
                routed[intentIndex] = stub;
            } else if (GuestPackageQueries.isGoogleIdentity(targetPackage)) {
                throw new EngineException("Google Activity is not installed in the container");
            }
            if (!(routed[1] instanceof String)) throw new EngineException("Unsupported Activity caller signature");
            routed[1] = engine.hostContext().getPackageName();
            args = routed;
        } else if (name.startsWith("startActivit") || name.equals("startNextMatchingActivity")) {
            throw new EngineException("Activity entry point is not implemented: " + name);
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
