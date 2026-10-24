package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Routes metadata reads for committed virtual packages; system mutations remain on Android. */
final class GuestPackageQueries implements InvocationHandler {
    private final Engine engine;
    private final Object original;

    private GuestPackageQueries(Engine engine, Object original) {
        this.engine = engine;
        this.original = original;
    }

    static void install(Engine engine, Object thread) throws Exception {
        Class<?> threadType = thread.getClass();
        Object original = RuntimeReflection.invoke(null,
                RuntimeReflection.method(threadType, "getPackageManager"));
        Class<?> contract = Class.forName("android.content.pm.IPackageManager");
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                new GuestPackageQueries(engine, original));
        RuntimeReflection.field(threadType, "sPackageManager").set(null, proxy);
        RuntimeReflection.set(engine.hostContext().getPackageManager(), "mPM", proxy);
        Class<?> applicationPm = Class.forName("android.app.ApplicationPackageManager");
        for (String name : new String[]{"disablePackageInfoCache", "disableApplicationInfoCache",
                "disableGetPackagesForUidCache"}) {
            Method method;
            try { method = RuntimeReflection.method(applicationPm, name); }
            catch (NoSuchMethodException olderAndroid) { continue; }
            RuntimeReflection.invoke(null, method);
        }
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String operation = method.getName();
        if (isPackageRead(operation) && args != null && args.length > 0 && args[0] instanceof String) {
            String packageName = (String) args[0];
            boolean installed = packageName != null && engine.packages().get(packageName) != null;
            if (installed) {
                if (operation.equals("getPackageInfo")) {
                    return engine.packages().getPackageInfo(packageName, flags(args, 1));
                }
                if (operation.equals("getApplicationInfo")) {
                    PackageInfo info = engine.packages().getPackageInfo(packageName, flags(args, 1));
                    return info == null ? null : info.applicationInfo;
                }
                if (operation.equals("isPackageAvailable")) return true;
                if (operation.equals("getPackageUid")) return android.os.Process.myUid();
            }
            if (!installed && isGoogleIdentity(packageName)) {
                if (operation.equals("getPackageInfo") || operation.equals("getApplicationInfo")) return null;
                if (operation.equals("isPackageAvailable")) return false;
                if (operation.equals("getPackageUid")) return -1;
                throw new EngineException("Google package is not installed in this container: " + packageName);
            }
        }
        if (isComponentRead(operation) && args != null && args.length > 0 && args[0] instanceof ComponentName) {
            ComponentName component = (ComponentName) args[0];
            if (engine.packages().get(component.getPackageName()) != null) {
                int flags = flags(args, 1);
                if (operation.equals("getActivityInfo")) return component(component, flags | PackageManager.GET_ACTIVITIES, 0);
                if (operation.equals("getReceiverInfo")) return component(component, flags | PackageManager.GET_RECEIVERS, 1);
                if (operation.equals("getServiceInfo")) return component(component, flags | PackageManager.GET_SERVICES, 2);
                if (operation.equals("getProviderInfo")) return component(component, flags | PackageManager.GET_PROVIDERS, 3);
            }
            if (isGoogleIdentity(component.getPackageName())) return null;
        }
        if (operation.equals("resolveContentProvider") && args != null && args.length > 0) {
            String authority = (String) args[0];
            for (io.github.xmy953538104.mybox.engine.packages.InstalledPackage installed : engine.packages().list()) {
                PackageInfo info = engine.packages().getPackageInfo(installed.packageName, PackageManager.GET_PROVIDERS);
                if (info.providers == null) continue;
                for (ProviderInfo provider : info.providers) {
                    if (!provider.enabled || !provider.applicationInfo.enabled || provider.authority == null) continue;
                    for (String value : provider.authority.split(";")) {
                        if (value.equals(authority)) return provider;
                    }
                }
            }
        }
        if ((operation.equals("resolveIntent") || operation.equals("queryIntentActivities"))
                && args != null && args.length > 0 && args[0] instanceof Intent) {
            Intent intent = (Intent) args[0];
            ComponentName explicit = intent.getComponent();
            String packageName = explicit == null ? intent.getPackage() : explicit.getPackageName();
            if (packageName != null && engine.packages().get(packageName) != null) {
                ActivityInfo activity = explicit == null
                        ? (Intent.ACTION_MAIN.equals(intent.getAction()) ? engine.packages().findLaunchActivity(packageName) : null)
                        : (ActivityInfo) component(explicit, PackageManager.GET_ACTIVITIES, 0);
                ResolveInfo resolved = null;
                if (activity != null && activity.enabled) {
                    resolved = new ResolveInfo();
                    resolved.activityInfo = activity;
                }
                if (operation.equals("resolveIntent")) return resolved;
                List<ResolveInfo> list = new ArrayList<>();
                if (resolved != null) list.add(resolved);
                if (List.class.isAssignableFrom(method.getReturnType())) return list;
                return Class.forName("android.content.pm.ParceledListSlice")
                        .getConstructor(List.class).newInstance(list);
            }
        }
        if (args != null) {
            for (Object argument : args) {
                String identity = argument instanceof String ? (String) argument
                        : argument instanceof ComponentName ? ((ComponentName) argument).getPackageName()
                        : argument instanceof android.content.pm.VersionedPackage
                            ? ((android.content.pm.VersionedPackage) argument).getPackageName() : null;
                if (isGoogleIdentity(identity)) {
                    throw new EngineException("Virtual Google identity operation is not implemented: " + operation);
                }
            }
        }
        try {
            return withoutPhysicalGoogle(method.invoke(original, args));
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    static boolean isPackageRead(String operation) {
        return operation.equals("getPackageInfo") || operation.equals("getApplicationInfo")
                || operation.equals("isPackageAvailable") || operation.equals("getPackageUid");
    }

    private static boolean isComponentRead(String operation) {
        return operation.equals("getActivityInfo") || operation.equals("getReceiverInfo")
                || operation.equals("getServiceInfo") || operation.equals("getProviderInfo");
    }

    static boolean isGoogleIdentity(String packageName) {
        return "com.google.android.gms".equals(packageName) || "com.google.android.gsf".equals(packageName)
                || "com.android.vending".equals(packageName);
    }

    private Object withoutPhysicalGoogle(Object value) throws Exception {
        String owner = value instanceof PackageInfo ? ((PackageInfo) value).packageName
                : value instanceof ApplicationInfo ? ((ApplicationInfo) value).packageName
                : value instanceof ComponentInfo ? ((ComponentInfo) value).packageName : null;
        if (value instanceof ResolveInfo) {
            ResolveInfo info = (ResolveInfo) value;
            ComponentInfo component = info.activityInfo != null ? info.activityInfo
                    : info.serviceInfo != null ? info.serviceInfo : info.providerInfo;
            owner = component == null ? null : component.packageName;
        }
        if (isGoogleIdentity(owner)) return null;
        if (value instanceof String[]) {
            List<String> filtered = new ArrayList<>();
            for (String item : (String[]) value) if (!isGoogleIdentity(item)) filtered.add(item);
            return filtered.toArray(new String[0]);
        }
        if (value instanceof List<?>) {
            List<Object> filtered = new ArrayList<>();
            for (Object item : (List<?>) value) {
                Object retained = withoutPhysicalGoogle(item);
                if (retained != null) filtered.add(retained);
            }
            return filtered;
        }
        if (value != null && value.getClass().getName().equals("android.content.pm.ParceledListSlice")) {
            Object list = RuntimeReflection.invoke(value, RuntimeReflection.method(value.getClass(), "getList"));
            return value.getClass().getConstructor(List.class).newInstance(withoutPhysicalGoogle(list));
        }
        return value;
    }

    private ComponentInfo component(ComponentName component, int flags, int kind) throws Exception {
        PackageInfo info = engine.packages().getPackageInfo(component.getPackageName(), flags);
        if (info == null) return null;
        ComponentInfo[] values = kind == 0 ? info.activities : kind == 1 ? info.receivers
                : kind == 2 ? info.services : info.providers;
        if (values != null) {
            for (ComponentInfo value : values) if (component.getClassName().equals(value.name)) return value;
        }
        return null;
    }

    private static int flags(Object[] args, int index) {
        return args.length > index && args[index] instanceof Number ? ((Number) args[index]).intValue() : 0;
    }
}
