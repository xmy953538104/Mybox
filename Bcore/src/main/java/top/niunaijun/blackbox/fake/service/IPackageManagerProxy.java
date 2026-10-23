package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.pm.FeatureInfo;
import android.os.Binder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.security.MessageDigest;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.content.pm.BRParceledListSlice;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.AppSystemEnv;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;

/**
 * Created by Milk on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class IPackageManagerProxy extends BinderInvocationStub {
    public static final String TAG = "PackageManagerStub";
    private static final String UNSUPPORTED_INCREMENTAL = "android.software.incremental_delivery";

    public IPackageManagerProxy() {
        super(BRActivityThread.get().sPackageManager().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRActivityThread.get().sPackageManager();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRActivityThread.get()._set_sPackageManager(proxyInvocation);
        replaceSystemService("package");
        Object systemContext = BRActivityThread.get(BlackBoxCore.mainThread()).getSystemContext();
        PackageManager packageManager = BRContextImpl.get(systemContext).mPackageManager();
        if (packageManager != null) {
            try {
                Reflector.on("android.app.ApplicationPackageManager")
                        .field("mPM")
                        .set(packageManager, proxyInvocation);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
    }

    @ProxyMethod("getPackageInstaller")
    public static class GetPackageInstaller extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) {
            return PackageInstallerProxy.create(method.getReturnType());
        }
    }

    @ProxyMethod("hasSystemFeature")
    public static class HasSystemFeature extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return UNSUPPORTED_INCREMENTAL.equals(args[0]) ? false : method.invoke(who, args);
        }
    }

    @ProxyMethod("getSystemAvailableFeatures")
    public static class GetSystemAvailableFeatures extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object original = method.invoke(who, args);
            if (original == null) return null;
            if (original instanceof FeatureInfo[]) return supportedFeatures(Arrays.asList((FeatureInfo[]) original)).toArray(new FeatureInfo[0]);
            if (ParceledListSliceCompat.isParceledListSlice(original)) {
                return ParceledListSliceCompat.create(supportedFeatures((List<FeatureInfo>) BRParceledListSlice.get(original).getList()));
            }
            if (original instanceof List) return supportedFeatures((List<FeatureInfo>) original);
            throw new IllegalStateException("Unsupported platform feature-list representation");
        }
    }

    static List<FeatureInfo> supportedFeatures(List<FeatureInfo> original) {
        List<FeatureInfo> supported = new ArrayList<>();
        for (FeatureInfo feature : original) {
            if (feature == null || !UNSUPPORTED_INCREMENTAL.equals(feature.name)) supported.add(feature);
        }
        return supported;
    }

    @ProxyMethod("resolveIntent")
    public static class ResolveIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = ((Number) args[2]).intValue();
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveIntent(intent, resolvedType, flags, BActivityThread.getUserId());
            if (resolveInfo != null) {
                return resolveInfo;
            }
            ResolveInfo system = (ResolveInfo) method.invoke(who, args);
            return system != null && system.activityInfo != null
                    && AppSystemEnv.isOpenPackage(system.activityInfo.packageName) ? system : null;
        }
    }

    @ProxyMethod("resolveService")
    public static class ResolveService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = ((Number) args[2]).intValue();
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveService(intent, flags, resolvedType, BActivityThread.getUserId());
            if (resolveInfo != null) {
                return resolveInfo;
            }
            ResolveInfo system = (ResolveInfo) method.invoke(who, args);
            return system != null && system.serviceInfo != null
                    && AppSystemEnv.isOpenPackage(system.serviceInfo.packageName) ? system : null;
        }
    }

    @ProxyMethod("setComponentEnabledSetting")
    public static class SetComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BlackBoxCore.getBPackageManager().setComponentEnabledSetting((ComponentName) args[0],
                    ((Number) args[1]).intValue(), ((Number) args[2]).intValue(), BActivityThread.getUserId());
            return null;
        }
    }

    @ProxyMethod("getPackageInfo")
    public static class GetPackageInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            int flag = ((Number) args[1]).intValue();
//            if (ClientSystemEnv.isFakePackage(packageName)) {
//                packageName = BlackBoxCore.getHostPkg();
//            }
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, flag, BActivityThread.getUserId());
            if (packageInfo != null) {
                return packageInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getPackageInfoVersioned")
    public static class GetPackageInfoVersioned extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object versioned = args[0];
            String name = (String) versioned.getClass().getMethod("getPackageName").invoke(versioned);
            long version;
            try { version = ((Number) versioned.getClass().getMethod("getLongVersionCode").invoke(versioned)).longValue(); }
            catch (NoSuchMethodException oldApi) { version = ((Number) versioned.getClass().getMethod("getVersionCode").invoke(versioned)).longValue(); }
            PackageInfo info = BlackBoxCore.getBPackageManager().getPackageInfo(name, ((Number) args[1]).intValue(), BActivityThread.getUserId());
            if (info == null) return AppSystemEnv.isOpenPackage(name) ? method.invoke(who, args) : null;
            long installedVersion = BuildCompat.isPie() ? info.getLongVersionCode() : info.versionCode;
            return version == -1 || version == installedVersion ? info : null;
        }
    }

    @ProxyMethods({"queryIntentActivities", "queryIntentServices"})
    public static class QueryIntentComponents extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            int flags = ((Number) args[2]).intValue();
            List<ResolveInfo> virtual = "queryIntentActivities".equals(method.getName())
                    ? BlackBoxCore.getBPackageManager().queryIntentActivities(intent, flags, (String) args[1], BActivityThread.getUserId())
                    : BlackBoxCore.getBPackageManager().queryIntentServices(intent, flags, BActivityThread.getUserId());
            List<ResolveInfo> result = new ArrayList<>();
            if (virtual != null) result.addAll(virtual);
            Object system = method.invoke(who, args);
            List<?> open = ParceledListSliceCompat.isParceledListSlice(system) ? BRParceledListSlice.get(system).getList()
                    : system instanceof List ? (List<?>) system : null;
            if (open != null) for (Object item : open) {
                ResolveInfo info = (ResolveInfo) item;
                String name = info.activityInfo != null ? info.activityInfo.packageName
                        : info.serviceInfo != null ? info.serviceInfo.packageName : null;
                if (AppSystemEnv.isOpenPackage(name)) result.add(info);
            }
            return ParceledListSliceCompat.isReturnParceledListSlice(method) ? ParceledListSliceCompat.create(result) : result;
        }
    }

    @ProxyMethod("getPackageUid")
    public static class GetPackageUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            ApplicationInfo info = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, 0, BActivityThread.getUserId());
            if (info != null) return info.uid;
            return AppSystemEnv.isOpenPackage(packageName) ? method.invoke(who, args) : -1;
        }
    }

    @ProxyMethod("getProviderInfo")
    public static class GetProviderInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = ((Number) args[1]).intValue();
            ProviderInfo providerInfo = BlackBoxCore.getBPackageManager().getProviderInfo(componentName, flags, BActivityThread.getUserId());
            if (providerInfo != null)
                return providerInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getReceiverInfo")
    public static class GetReceiverInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = ((Number) args[1]).intValue();
            ActivityInfo receiverInfo = BlackBoxCore.getBPackageManager().getReceiverInfo(componentName, flags, BActivityThread.getUserId());
            if (receiverInfo != null)
                return receiverInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getActivityInfo")
    public static class GetActivityInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = ((Number) args[1]).intValue();
            ActivityInfo activityInfo = BlackBoxCore.getBPackageManager().getActivityInfo(componentName, flags, BActivityThread.getUserId());
            if (activityInfo != null)
                return activityInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getServiceInfo")
    public static class GetServiceInfo extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = ((Number) args[1]).intValue();
            ServiceInfo serviceInfo = BlackBoxCore.getBPackageManager().getServiceInfo(componentName, flags, BActivityThread.getUserId());
            if (serviceInfo != null)
                return serviceInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getInstalledApplications")
    public static class GetInstalledApplications extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = ((Number) args[0]).intValue();
            List<ApplicationInfo> installedApplications = BlackBoxCore.getBPackageManager().getInstalledApplications(flags, BActivityThread.getUserId());
            return ParceledListSliceCompat.create(installedApplications);
        }
    }

    @ProxyMethod("getInstalledPackages")
    public static class GetInstalledPackages extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = ((Number) args[0]).intValue();
            List<PackageInfo> installedPackages = BlackBoxCore.getBPackageManager().getInstalledPackages(flags, BActivityThread.getUserId());
            return ParceledListSliceCompat.create(installedPackages);
        }
    }

    @ProxyMethod("getApplicationInfo")
    public static class GetApplicationInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            int flags = ((Number) args[1]).intValue();
//            if (ClientSystemEnv.isFakePackage(packageName)) {
//                packageName = BlackBoxCore.getHostPkg();
//            }
            ApplicationInfo applicationInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, flags, BActivityThread.getUserId());
            if (applicationInfo != null) {
                return applicationInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("queryContentProviders")
    public static class QueryContentProviders extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = ((Number) args[2]).intValue();
            List<ProviderInfo> providers = BlackBoxCore.getBPackageManager().
                    queryContentProviders(BActivityThread.getAppProcessName(), BActivityThread.getBUid(), flags, BActivityThread.getUserId());
            return ParceledListSliceCompat.create(providers);
        }
    }

    @ProxyMethod("queryIntentReceivers")
    public static class QueryBroadcastReceivers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            String type = MethodParameterUtils.getFirstParam(args, String.class);
            Integer flags = MethodParameterUtils.getFirstParam(args, Integer.class);
            List<ResolveInfo> resolves = BlackBoxCore.getBPackageManager().queryBroadcastReceivers(intent, flags, type, BActivityThread.getUserId());
            Slog.d(TAG, "queryIntentReceivers: " + resolves);

            // http://androidxref.com/7.0.0_r1/xref/frameworks/base/core/java/android/app/ApplicationPackageManager.java#872
            if (BuildCompat.isN()) {
                return ParceledListSliceCompat.create(resolves);
            }

            // http://androidxref.com/6.0.1_r10/xref/frameworks/base/core/java/android/app/ApplicationPackageManager.java#699
            return resolves;
        }
    }

    @ProxyMethod("resolveContentProvider")
    public static class ResolveContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String authority = (String) args[0];
            int flags = ((Number) args[1]).intValue();
            ProviderInfo providerInfo = BlackBoxCore.getBPackageManager().resolveContentProvider(authority, flags, BActivityThread.getUserId());
            if (providerInfo == null) {
                ProviderInfo system = (ProviderInfo) method.invoke(who, args);
                return system != null && (AppSystemEnv.isOpenPackage(system.packageName)
                        || authority.equals(BlackBoxCore.getHostPkg() + ".identity")) ? system : null;
            }
            return providerInfo;
        }
    }

    @ProxyMethod("canRequestPackageInstalls")
    public static class CanRequestPackageInstalls extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getPackagesForUid")
    public static class GetPackagesForUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int uid = (Integer) args[0];
            String[] packagesForUid = BlackBoxCore.getBPackageManager().getPackagesForUid(uid);
            Slog.d(TAG, args[0] + " , " + BActivityThread.getAppProcessName() + " GetPackagesForUid: " + Arrays.toString(packagesForUid));
            return packagesForUid;
        }
    }

    @ProxyMethod("getInstallerPackageName")
    public static class GetInstallerPackageName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BlackBoxCore.getBPackageManager().getInstallerPackageName((String) args[0], BActivityThread.getUserId());
        }
    }

    @ProxyMethod("getInstallSourceInfo")
    public static class GetInstallSourceInfo extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String name = (String) args[0];
            if (!BlackBoxCore.getBPackageManager().isInstalled(name, BActivityThread.getUserId())) return null;
            String installer = BlackBoxCore.getBPackageManager().getInstallerPackageName(name, BActivityThread.getUserId());
            PackageInfo source = installer == null ? null : BlackBoxCore.getBPackageManager().getPackageInfo(
                    installer, PackageManager.GET_SIGNING_CERTIFICATES, BActivityThread.getUserId());
            java.lang.reflect.Constructor<?> constructor = method.getReturnType().getDeclaredConstructor(
                    String.class, SigningInfo.class, String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(installer, source == null ? null : source.signingInfo, null, installer);
        }
    }

    @ProxyMethod("checkSignatures")
    public static class CheckSignatures extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            PackageInfo first = BlackBoxCore.getBPackageManager().getPackageInfo((String) args[0], PackageManager.GET_SIGNATURES, BActivityThread.getUserId());
            PackageInfo second = BlackBoxCore.getBPackageManager().getPackageInfo((String) args[1], PackageManager.GET_SIGNATURES, BActivityThread.getUserId());
            if (first == null || second == null) return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            return signaturesMatch(first.signatures, second.signatures) ? PackageManager.SIGNATURE_MATCH : PackageManager.SIGNATURE_NO_MATCH;
        }
    }

    @ProxyMethod("checkUidSignatures")
    public static class CheckUidSignatures extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) {
            String[] first = BlackBoxCore.getBPackageManager().getPackagesForUid((Integer) args[0]);
            String[] second = BlackBoxCore.getBPackageManager().getPackagesForUid((Integer) args[1]);
            if (first == null || second == null || first.length == 0 || second.length == 0) return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            PackageInfo a = BlackBoxCore.getBPackageManager().getPackageInfo(first[0], PackageManager.GET_SIGNATURES, BActivityThread.getUserId());
            PackageInfo b = BlackBoxCore.getBPackageManager().getPackageInfo(second[0], PackageManager.GET_SIGNATURES, BActivityThread.getUserId());
            return a != null && b != null && signaturesMatch(a.signatures, b.signatures)
                    ? PackageManager.SIGNATURE_MATCH : PackageManager.SIGNATURE_NO_MATCH;
        }
    }

    @ProxyMethods({"hasSigningCertificate", "hasUidSigningCertificate"})
    public static class HasSigningCertificate extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String[] packages = args[0] instanceof String ? new String[]{(String) args[0]}
                    : BlackBoxCore.getBPackageManager().getPackagesForUid((Integer) args[0]);
            if (packages == null || args[1] == null) return false;
            byte[] requested = (byte[]) args[1];
            int type = ((Number) args[2]).intValue();
            if (type != 0 && type != 1) return false;
            for (String name : packages) {
                PackageInfo info = BlackBoxCore.getBPackageManager().getPackageInfo(name, PackageManager.GET_SIGNATURES, BActivityThread.getUserId());
                if (info == null || info.signatures == null) continue;
                for (Signature signature : info.signatures) {
                    byte[] certificate = signature.toByteArray();
                    if (type == 1) certificate = MessageDigest.getInstance("SHA-256").digest(certificate);
                    if (MessageDigest.isEqual(certificate, requested)) return true;
                }
            }
            return false;
        }
    }

    @ProxyMethod("checkPermission")
    public static class CheckPermission extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if ("android.permission.FAKE_PACKAGE_SIGNATURE".equals(args[0])) {
                return BlackBoxCore.getBPackageManager().isTrustedMicroG((String) args[1], BActivityThread.getUserId())
                        ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
            }
            return method.invoke(who, args);
        }
    }

    private static boolean signaturesMatch(Signature[] first, Signature[] second) {
        return first != null && second != null && first.length != 0 && first.length == second.length
                && new java.util.HashSet<>(Arrays.asList(first)).equals(new java.util.HashSet<>(Arrays.asList(second)));
    }

    @ProxyMethod("getSharedLibraries")
    public static class GetSharedLibraries extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // todo
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethod("getComponentEnabledSetting")
    public static class getComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BlackBoxCore.getBPackageManager().getComponentEnabledSetting((ComponentName) args[0], BActivityThread.getUserId());
        }
    }
}
