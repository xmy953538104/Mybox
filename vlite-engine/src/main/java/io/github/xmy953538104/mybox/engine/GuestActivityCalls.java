package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Routes ordinary explicit guest Activity starts while preserving result tokens and extras. */
final class GuestActivityCalls implements InvocationHandler {
    private final Engine engine;
    private final GuestRuntime runtime;
    private final Object original;

    private GuestActivityCalls(Engine engine, GuestRuntime runtime, Object original) {
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
            Intent intent = (Intent) routed[intentIndex];
            ComponentName component = intent.getComponent();
            String targetPackage = component == null ? intent.getPackage() : component.getPackageName();
            if (targetPackage != null && engine.packages().get(targetPackage) != null) {
                if (component == null) throw new EngineException("Implicit guest Activity routing is not implemented");
                if (!runtime.packageInfo().packageName.equals(targetPackage)) {
                    throw new SecurityException("Cross-package Activity authorization is not implemented");
                }
                Bundle launch = runtime.launchRequest(component.getClassName());
                ActivityInfo target = runtime.activity(launch);
                int taskFlags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                if (target.launchMode != ActivityInfo.LAUNCH_MULTIPLE || (intent.getFlags() & taskFlags) != 0) {
                    throw new EngineException("Guest task reuse and non-standard launch modes are not implemented");
                }
                launch.putParcelable("original_intent", new Intent(intent));
                Intent stub = new Intent(intent).setPackage(null).setComponent(new ComponentName(engine.hostContext(),
                        GuestActivity.class.getName() + "$P" + runtime.slot()));
                stub.putExtra(EngineProtocol.LAUNCH, launch);
                routed[intentIndex] = stub;
            } else if (GuestPackageQueries.isGoogleIdentity(targetPackage)) {
                throw new EngineException("Google Activity is not installed in the container");
            }
            if (!(routed[1] instanceof String)) throw new EngineException("Unsupported Activity caller signature");
            routed[1] = engine.hostContext().getPackageName();
            args = routed;
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
