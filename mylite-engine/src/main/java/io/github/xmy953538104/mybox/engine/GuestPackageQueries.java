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

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Routes metadata reads for committed virtual packages; system mutations remain on Android. */
final class GuestPackageQueries implements InvocationHandler {
    private final Engine engine;
    private final Object original;
    private volatile Object installer;

    GuestPackageQueries(Engine engine, Object original) {
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
        if (operation.equals("notifyDexLoad")) {
            PackageInfo bound = engine.runtime().packageInfo();
            Object[] physical = dexLoadArguments(args, engine.hostContext().getPackageName(),
                    bound == null ? null : bound.packageName);
            // Android profiles code under its real owning UID; guest APK paths stay unchanged.
            try { return method.invoke(original, physical); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
        if (operation.equals("getPackageInstaller")) {
            return installer();
        }
        if (operation.equals("getPackagesForUid") || operation.equals("getNameForUid")) {
            int uid = ((Number) args[0]).intValue();
            InstalledPackage installed = packageForUid(uid);
            if (installed != null) return operation.equals("getPackagesForUid")
                    ? new String[]{installed.packageName} : installed.packageName;
            // App UIDs belong to the container's own namespace. In particular the real host
            // UID must not disclose every package sharing that physical Android identity.
            if (uid < 0 || uid >= 10000) return null;
        }
        if (operation.equals("getNamesForUids")) {
            int[] uids = (int[]) args[0];
            if (uids == null) return null;
            String[] names = new String[uids.length];
            Method single = method.getDeclaringClass().getMethod("getNameForUid", int.class);
            for (int index = 0; index < uids.length; index++) {
                names[index] = (String) invoke(proxy, single, new Object[]{uids[index]});
            }
            return names;
        }
        if (isPackageRead(operation) && args != null && args.length > 0 && args[0] instanceof String) {
            String packageName = (String) args[0];
            InstalledPackage installed = packageName == null ? null : engine.packages().get(packageName);
            if (installed != null) {
                if (operation.equals("getPackageInfo")) {
                    int requestedFlags = flags(args, 1);
                    return GoogleSignatureView.apply(engine,
                            engine.packages().getPackageInfo(packageName, requestedFlags), requestedFlags);
                }
                if (operation.equals("getApplicationInfo")) {
                    PackageInfo info = engine.packages().getPackageInfo(packageName, flags(args, 1));
                    return info == null ? null : info.applicationInfo;
                }
                if (operation.equals("isPackageAvailable")) return true;
                if (operation.equals("getPackageUid")) {
                    return args.length > 2 && ((Number) args[2]).intValue() != 0 ? -1 : installed.appId;
                }
            }
            if (installed == null && isGoogleIdentity(packageName)) {
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
        if ((operation.equals("resolveIntent") || operation.equals("queryIntentActivities")
                || operation.equals("resolveService") || operation.equals("queryIntentServices")
                || operation.equals("queryIntentReceivers"))
                && args != null && args.length > 0 && args[0] instanceof Intent) {
            Intent intent = (Intent) args[0];
            ComponentName explicit = intent.getComponent();
            String packageName = explicit == null ? intent.getPackage() : explicit.getPackageName();
            boolean installed = packageName != null && engine.packages().get(packageName) != null;
            int kind = operation.equals("resolveService") || operation.equals("queryIntentServices")
                    ? PackageManager.GET_SERVICES : operation.equals("queryIntentReceivers")
                    ? PackageManager.GET_RECEIVERS : PackageManager.GET_ACTIVITIES;
            int requestedFlags = flags(args, 2);
            List<ResolveInfo> matches = new ArrayList<>();
            if (explicit != null && installed) {
                ComponentInfo selected = component(explicit, kind | requestedFlags,
                        kind == PackageManager.GET_SERVICES ? 2 : kind == PackageManager.GET_RECEIVERS ? 1 : 0);
                if (selected != null && selected.enabled && selected.applicationInfo.enabled) {
                    ResolveInfo match = new ResolveInfo();
                    if (kind == PackageManager.GET_SERVICES) match.serviceInfo = (android.content.pm.ServiceInfo) selected;
                    else match.activityInfo = (ActivityInfo) selected;
                    matches.add(match);
                }
            } else if (explicit == null && (packageName == null || installed)) {
                matches = ManifestIntents.query(engine, intent, (String) args[1], kind, requestedFlags);
            }
            if (installed || !matches.isEmpty() || isGoogleIdentity(packageName)) {
                if (args.length > 3 && ((Number) args[3]).intValue() != 0) matches.clear();
                if (operation.equals("resolveIntent") || operation.equals("resolveService")) return bestMatch(matches);
                if (List.class.isAssignableFrom(method.getReturnType())) return matches;
                return Class.forName("android.content.pm.ParceledListSlice").getConstructor(List.class).newInstance(matches);
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

    static Object[] dexLoadArguments(Object[] arguments, String hostPackage, String boundPackage) {
        if (arguments == null || arguments.length < 3 || arguments.length > 4
                || !(arguments[0] instanceof String)
                || !(hostPackage.equals(arguments[0])
                    || boundPackage != null && boundPackage.equals(arguments[0]))) {
            throw new SecurityException("DEX load report does not belong to this bound process");
        }
        Object[] physical = arguments.clone();
        physical[0] = hostPackage;
        return physical;
    }

    static ResolveInfo bestMatch(List<ResolveInfo> matches) {
        if (matches.isEmpty()) return null;
        ResolveInfo first = matches.get(0);
        if (matches.size() > 1 && first.priority == matches.get(1).priority && first.match == matches.get(1).match) {
            throw new EngineException("Multiple virtual components match; an explicit component is required");
        }
        return first;
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
        if (value instanceof String && isGoogleIdentity((String) value)) return null;
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

    private InstalledPackage packageForUid(int uid) throws Exception {
        for (InstalledPackage installed : engine.packages().list()) {
            if (installed.appId == uid) return installed;
        }
        return null;
    }

    private Object installer() throws Exception {
        Object current = installer;
        if (current != null) return current;
        synchronized (this) {
            if (installer == null) {
                android.os.Bundle request = EngineProtocol.response();
                request.putString("service", "installer");
                android.os.Bundle service = EngineProtocol.request(engine.server(), EngineProtocol.SERVER,
                        EngineProtocol.GET_SERVICE, request);
                String guest = engine.runtime().packageInfo().packageName;
                installer = io.github.xmy953538104.mybox.engine.installer.GuestInstaller.create(service.getBinder("service"),
                        guest, engine.hostContext().getPackageName());
            }
            return installer;
        }
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
