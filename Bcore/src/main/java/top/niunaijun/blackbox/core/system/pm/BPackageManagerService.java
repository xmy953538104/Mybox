package top.niunaijun.blackbox.core.system.pm;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.Build;
import android.os.RemoteException;
import android.text.TextUtils;

import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.core.system.user.BUserManagerService;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.entity.pm.InstalledPackage;
import top.niunaijun.blackbox.utils.AbiUtils;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.PackageParserCompat;
import top.niunaijun.blackbox.core.system.pm.installer.AtomicPackageDirectory;
import top.niunaijun.blackbox.core.system.pm.installer.PackageSetValidator;
import top.niunaijun.blackbox.utils.NativeUtils;
import top.niunaijun.blackbox.core.system.pm.installer.ApkNativeLibraries;

import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;


/**
 * Created by Milk on 4/1/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BPackageManagerService extends IBPackageManagerService.Stub implements ISystemService {
    public static final String TAG = "BPackageManagerService";
    public static BPackageManagerService sService = new BPackageManagerService();
    private final Settings mSettings = new Settings();
    private final ComponentResolver mComponentResolver;
    private static final BUserManagerService sUserManager = BUserManagerService.get();
    private final List<PackageMonitor> mPackageMonitors = new ArrayList<>();

    final Map<String, BPackageSettings> mPackages = mSettings.mPackages;
    final Object mInstallLock = new Object();

    public static BPackageManagerService get() {
        return sService;
    }

    public BPackageManagerService() {
        mComponentResolver = new ComponentResolver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");
        filter.addDataScheme("package");
        BlackBoxCore.getContext()
                .registerReceiver(mPackageChangedHandler, filter);
    }

    private final BroadcastReceiver mPackageChangedHandler = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!TextUtils.isEmpty(action)) {
                if ("android.intent.action.PACKAGE_ADDED".equals(action) || "android.intent.action.PACKAGE_REMOVED".equals(action)) {
                    mSettings.scanPackage();
                }
            }
        }
    };

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        if (Objects.equals(packageName, BlackBoxCore.getHostPkg())) {
            try {
                return BlackBoxCore.getPackageManager().getApplicationInfo(packageName, flags);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }
        flags = updateFlags(flags, userId);
        // reader
        synchronized (mPackages) {
            // Normalize package name to handle renamed packages and static libs
            BPackageSettings ps = mPackages.get(packageName);
            if (ps != null) {
                BPackage p = ps.pkg;
                return PackageManagerCompat.generateApplicationInfo(p, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ResolveInfo resolveService(Intent intent, int flags, String resolvedType, int userId) {
        if (!sUserManager.exists(userId)) return null;
        List<ResolveInfo> query = queryIntentServicesInternal(
                intent, resolvedType, flags, userId);
        if (query != null) {
            if (query.size() >= 1) {
                // If there is more than one service with the same priority,
                // just arbitrarily pick the first one.
                return query.get(0);
            }
        }
        return null;
    }

    private List<ResolveInfo> queryIntentServicesInternal(Intent intent, String resolvedType, int flags, int userId) {
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ServiceInfo si = getServiceInfo(comp, flags, userId);
            if (si != null) {
                // When specifying an explicit component, we prevent the service from being
                // used when either 1) the service is in an instant application and the
                // caller is not the same instant application or 2) the calling package is
                // ephemeral and the activity is not visible to ephemeral applications.
                final ResolveInfo ri = new ResolveInfo();
                ri.serviceInfo = si;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName != null) {
                BPackageSettings bPackageSettings = mPackages.get(pkgName);
                if (bPackageSettings != null) {
                    final BPackage pkg = bPackageSettings.pkg;
                    return mComponentResolver.queryServices(intent, resolvedType, flags, pkg.services,
                            userId);
                }
            } else {
               return mComponentResolver.queryServices(intent, resolvedType, flags, userId);
            }
            return Collections.emptyList();
        }
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, int flags, String resolvedType, int userId) {
        if (!sUserManager.exists(userId)) return null;
        List<ResolveInfo> resolves = queryIntentActivities(intent, resolvedType, flags, userId);
        return chooseBestActivity(intent, resolvedType, flags, resolves);
    }

    @Override
    public ProviderInfo resolveContentProvider(String authority, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        return mComponentResolver.queryProvider(authority, flags, userId);
    }

    @Override
    public ResolveInfo resolveIntent(Intent intent, String resolvedType, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        List<ResolveInfo> resolves = queryIntentActivities(intent, resolvedType, flags, userId);
        return chooseBestActivity(intent, resolvedType, flags, resolves);
    }

    private ResolveInfo chooseBestActivity(Intent intent, String resolvedType,
                                           int flags, List<ResolveInfo> query) {
        if (query != null) {
            final int N = query.size();
            if (N == 1) {
                return query.get(0);
            } else if (N > 1) {
                // If there is more than one activity with the same priority,
                // then let the user decide between them.
                ResolveInfo r0 = query.get(0);
                ResolveInfo r1 = query.get(1);
                // If the first activity has a higher priority, or a different
                // default, then it is always desirable to pick it.
                if (r0.priority != r1.priority
                        || r0.preferredOrder != r1.preferredOrder
                        || r0.isDefault != r1.isDefault) {
                    return query.get(0);
                }
            }
        }
        return null;
    }

    private List<ResolveInfo> queryIntentActivities(Intent intent,
                                                    String resolvedType, int flags, int userId) {
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }

        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getActivity(comp, flags, userId);
            if (ai != null) {
                // When specifying an explicit component, we prevent the activity from being
                // used when either 1) the calling package is normal and the activity is within
                // an ephemeral application or 2) the calling package is ephemeral and the
                // activity is not visible to ephemeral applications.
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
                return list;
            }
        }

        // reader
        synchronized (mPackages) {
            return mComponentResolver.queryActivities(intent, resolvedType, flags, userId);
        }
    }

    @Override
    public List<ResolveInfo> queryIntentServices(
            Intent intent, int flags, int userId) {
        final String resolvedType = intent.resolveTypeIfNeeded(BlackBoxCore.getContext().getContentResolver());
        return this.queryIntentServicesInternal(intent, resolvedType, flags, userId);
    }

    private ActivityInfo getActivity(ComponentName component, int flags,
                                     int userId) {
        flags = updateFlags(flags, userId);
        synchronized (mPackages) {
            BPackage.Activity a = mComponentResolver.getActivity(component);

            if (a != null) {
                BPackageSettings ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageManagerCompat.generateActivityInfo(a, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        if (Objects.equals(packageName, BlackBoxCore.getHostPkg())) {
            try {
                return BlackBoxCore.getPackageManager().getPackageInfo(packageName, flags);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }

        flags = updateFlags(flags, userId);
        BPackageSettings ps = null;
        // reader
        synchronized (mPackages) {
            // Normalize package name to handle renamed packages and static libs
            ps = mPackages.get(packageName);
        }
        if (ps != null) {
            return PackageManagerCompat.generatePackageInfo(ps, flags, ps.readUserState(userId), userId);
        }
        return null;
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        synchronized (mPackages) {
            BPackage.Service s = mComponentResolver.getService(component);
            if (s != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageManagerCompat.generateServiceInfo(
                        s, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        synchronized (mPackages) {
            BPackage.Activity a = mComponentResolver.getReceiver(component);
            if (a != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageManagerCompat.generateActivityInfo(
                        a, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        synchronized (mPackages) {
            BPackage.Activity a = mComponentResolver.getActivity(component);

            if (a != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageManagerCompat.generateActivityInfo(
                        a, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        synchronized (mPackages) {
            BPackage.Provider p = mComponentResolver.getProvider(component);
            if (p != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageManagerCompat.generateProviderInfo(
                        p, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        return getInstalledApplicationsListInternal(flags, userId, Binder.getCallingUid());
    }

    @Override
    public List<PackageInfo> getInstalledPackages(int flags, int userId) {
        final int callingUid = Binder.getCallingUid();
//        if (getInstantAppPackageName(callingUid) != null) {
//            return ParceledListSlice.emptyList();
//        }
        if (!sUserManager.exists(userId)) return Collections.emptyList();

        // writer
        synchronized (mPackages) {
            ArrayList<PackageInfo> list;
            list = new ArrayList<>(mPackages.size());
            for (BPackageSettings ps : mPackages.values()) {
//                if (filterSharedLibPackageLPr(ps, callingUid, userId, flags)) {
//                    continue;
//                }
//                if (filterAppAccessLPr(ps, callingUid, userId)) {
//                    continue;
//                }
                PackageInfo pi = getPackageInfo(ps.pkg.packageName, flags, userId);
                if (pi != null) {
                    list.add(pi);
                }
            }
            return new ArrayList<>(list);
        }
    }

    private List<ApplicationInfo> getInstalledApplicationsListInternal(int flags, int userId,
                                                                       int callingUid) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();

        // writer
        synchronized (mPackages) {
            ArrayList<ApplicationInfo> list;
            list = new ArrayList<>(mPackages.size());
            Collection<BPackageSettings> packageSettings = mPackages.values();
            for (BPackageSettings ps : packageSettings) {
//                if (filterSharedLibPackageLPr(ps, Binder.getCallingUid(), userId, flags)) {
//                    continue;
//                }
//                if (filterAppAccessLPr(ps, callingUid, userId)) {
//                    continue;
//                }
                ApplicationInfo ai = PackageManagerCompat.generateApplicationInfo(ps.pkg, flags,
                        ps.readUserState(userId), userId);
                if (ai != null) {
                    list.add(ai);
                }
            }
            return list;
        }
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags, String resolvedType, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        final String pkgName = intent.getPackage();
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }

        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getActivityInfo(comp, flags, userId);
            if (ai != null) {
                // When specifying an explicit component, we prevent the activity from being
                // used when either 1) the calling package is normal and the activity is within
                // an ephemeral application or 2) the calling package is ephemeral and the
                // activity is not visible to ephemeral applications.
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        // reader
        List<ResolveInfo> result;
        synchronized (mPackages) {
            if (pkgName != null) {
                BPackageSettings bPackageSettings = mPackages.get(pkgName);
                result = null;
                if (bPackageSettings != null) {
                    final BPackage pkg = bPackageSettings.pkg;

                    result = mComponentResolver.queryActivities(
                            intent, resolvedType, flags, pkg.activities, userId);
                }
                if (result == null || result.size() == 0) {
                    // the caller wants to resolve for a particular package; however, there
                    // were no installed results, so, try to find an ephemeral result
                    if (result == null) {
                        result = new ArrayList<>();
                    }
                }
                return result;
            }
        }
        return Collections.emptyList();
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags, String resolvedType, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return Collections.emptyList();

        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getReceiverInfo(comp, flags, userId);
            if (ai != null) {
                // When specifying an explicit component, we prevent the activity from being
                // used when either 1) the calling package is normal and the activity is within
                // an instant application or 2) the calling package is ephemeral and the
                // activity is not visible to instant applications.
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            BPackageSettings bPackageSettings = mPackages.get(pkgName);
            if (bPackageSettings != null) {
                final BPackage pkg = bPackageSettings.pkg;
                return mComponentResolver.queryReceivers(
                        intent, resolvedType, flags, pkg.receivers, userId);
            } else {
                return mComponentResolver.queryReceivers(intent, resolvedType, flags, userId);
            }
        }
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return Collections.emptyList();

        List<ProviderInfo> providers = new ArrayList<>();
        if (TextUtils.isEmpty(processName))
            return providers;
        providers.addAll(mComponentResolver.queryProviders(processName, null, flags, userId));
        return providers;
    }

    @Override
    public InstallResult installPackageAsUser(String file, InstallOption option, int userId) {
        return installPackageSetAsUser(new String[]{file}, option, userId, BlackBoxCore.getHostPkg());
    }

    @Override
    public InstallResult installPackageSetAsUser(String[] files, InstallOption option, int userId, String installerPackageName) {
        enforceHostImport();
        synchronized (mInstallLock) {
            return installPackageSetLocked(files, option, userId, installerPackageName);
        }
    }

    @Override
    public Bundle installerCall(String operation, Bundle arguments) {
        return VirtualPackageInstaller.get().call(operation, arguments);
    }

    @Override public ParcelFileDescriptor openSessionWrite(int id, String name, long offset, long length, boolean fileBridge) {
        return VirtualPackageInstaller.get().openWrite(id, name, offset, length, fileBridge);
    }

    @Override public ParcelFileDescriptor openSessionRead(int id, String name) {
        return VirtualPackageInstaller.get().openRead(id, name);
    }

    @Override
    public String getInstallerPackageName(String packageName, int userId) {
        synchronized (mPackages) {
            BPackageSettings ps = mPackages.get(packageName);
            return ps != null && ps.getInstalled(userId) ? ps.installerPackageName : null;
        }
    }

    @Override
    public boolean isTrustedMicroG(String packageName, int userId) {
        synchronized (mPackages) {
            BPackageSettings ps = mPackages.get(packageName);
            return ps != null && ps.getInstalled(userId) && ps.pkg.trustedMicroG;
        }
    }

    /** Only the host identity provider supplies queryingPid, from its incoming Binder call. */
    public Bundle resolveCallingIdentity(int queryingPid, int originalUid, int originalPid) {
        ProcessRecord querying = BProcessManagerService.get().findProcessByPid(queryingPid);
        if (querying == null || !isTrustedMicroG(querying.getPackageName(), querying.userId)
                || originalUid != Process.myUid() || originalPid <= 0) {
            throw new SecurityException("Untrusted identity query");
        }
        if (originalPid == Process.myPid()) {
            Bundle identity = new Bundle();
            identity.putInt("uid", 1000);
            identity.putInt("userId", querying.userId);
            identity.putString("packageName", "android");
            identity.putStringArray("packages", new String[]{"android"});
            return identity;
        }
        ProcessRecord original = BProcessManagerService.get().findProcessByPid(originalPid);
        if (original == null || original.userId != querying.userId) {
            throw new SecurityException("Caller PID is not registered in the same virtual user");
        }
        Bundle identity = new Bundle();
        identity.putInt("uid", original.buid);
        identity.putInt("userId", original.userId);
        identity.putString("packageName", original.getPackageName());
        try { identity.putStringArray("packages", getPackagesForUid(original.buid, original.userId)); }
        catch (RemoteException impossibleLocalCall) { throw new IllegalStateException(impossibleLocalCall); }
        return identity;
    }

    private void enforceHostImport() {
        if (Binder.getCallingUid() != Process.myUid()
                || BProcessManagerService.get().findProcessByPid(Binder.getCallingPid()) != null) {
            throw new SecurityException("Guest packages must use PackageInstaller sessions");
        }
    }

    @Override public int getComponentEnabledSetting(ComponentName component, int userId) {
        synchronized (mPackages) {
            BPackageSettings settings = requireComponent(component, userId);
            return settings.readUserState(userId).componentState(component.getClassName());
        }
    }

    @Override public void setComponentEnabledSetting(ComponentName component, int state, int flags, int userId) {
        if (state < PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || state > PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
            throw new IllegalArgumentException("Invalid component enabled state");
        }
        ProcessRecord caller = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (Binder.getCallingUid() != Process.myUid() || (caller != null
                && (!caller.getPackageName().equals(component.getPackageName()) || caller.userId != userId))) {
            throw new SecurityException("Cannot change another guest's components");
        }
        synchronized (mPackages) {
            BPackageSettings settings = requireComponent(component, userId);
            int previous = settings.readUserState(userId).componentState(component.getClassName());
            settings.setComponentState(component.getClassName(), state, userId);
            if (!settings.save()) {
                settings.setComponentState(component.getClassName(), previous, userId);
                throw new IllegalStateException("Cannot persist component state");
            }
        }
        if ((flags & PackageManager.DONT_KILL_APP) == 0) {
            BProcessManagerService.get().killPackageAsUser(component.getPackageName(), userId);
        }
    }

    private BPackageSettings requireComponent(ComponentName component, int userId) {
        if (component == null) throw new IllegalArgumentException("Missing component");
        BPackageSettings settings = mPackages.get(component.getPackageName());
        if (settings == null || !settings.getInstalled(userId)
                || (mComponentResolver.getActivity(component) == null && mComponentResolver.getReceiver(component) == null
                && mComponentResolver.getService(component) == null && mComponentResolver.getProvider(component) == null)) {
            throw new IllegalArgumentException("Unknown component");
        }
        return settings;
    }

    @Override
    public void uninstallPackageAsUser(String packageName, int userId) throws RemoteException {
        enforceHostImport();
        synchronized (mInstallLock) {
            synchronized (mPackages) {
                BPackageSettings ps = mPackages.get(packageName);
                if (ps == null)
                    return;
                if (!isInstalled(packageName, userId)) {
                    return;
                }
                boolean removeApp = ps.getUserState().size() <= 1;
                BProcessManagerService.get().killPackageAsUser(packageName, userId);
                int i = BPackageInstallerService.get().uninstallPackageAsUser(ps, removeApp, userId);
                if (i < 0) {
                    // todo
                }

                if (removeApp) {
                    mSettings.removePackage(packageName);
                    mComponentResolver.removeAllComponents(ps.pkg);
                } else {
                    ps.removeUser(userId);
                    ps.save();
                }
                onPackageUninstalled(packageName, removeApp, userId);
            }
        }
    }

    @Override
    public void uninstallPackage(String packageName) {
        enforceHostImport();
        synchronized (mInstallLock) {
            synchronized (mPackages) {
                BPackageSettings ps = mPackages.get(packageName);
                if (ps == null)
                    return;
                BProcessManagerService.get().killAllByPackageName(packageName);
                    for (Integer userId : ps.getUserIds()) {
                        int i = BPackageInstallerService.get().uninstallPackageAsUser(ps, true, userId);
                        if (i < 0) {
                            continue;
                        }
                        onPackageUninstalled(packageName, true, userId);
                    }
                mSettings.removePackage(packageName);
                mComponentResolver.removeAllComponents(ps.pkg);
            }
        }
    }

    @Override
    public void clearPackage(String packageName, int userId) {
        enforceHostImport();
        if (!isInstalled(packageName, userId)) {
            return;
        }
        BProcessManagerService.get().killPackageAsUser(packageName, userId);
        BPackageSettings ps = mPackages.get(packageName);
        if (ps == null)
            return;
        int i = BPackageInstallerService.get().clearPackage(ps, userId);
    }

    @Override
    public void stopPackage(String packageName, int userId) {
        enforceHostImport();
        BProcessManagerService.get().killPackageAsUser(packageName, userId);
    }

    @Override
    public void deleteUser(int userId) throws RemoteException {
        enforceHostImport();
        synchronized (mPackages) {
            for (BPackageSettings ps : mPackages.values()) {
                uninstallPackageAsUser(ps.pkg.packageName, userId);
            }
        }
    }

    @Override
    public boolean isInstalled(String packageName, int userId) {
        if (!sUserManager.exists(userId)) return false;
        synchronized (mPackages) {
            BPackageSettings ps = mPackages.get(packageName);
            if (ps == null)
                return false;
            return ps.getInstalled(userId);
        }
    }

    @Override
    public List<InstalledPackage> getInstalledPackagesAsUser(int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        synchronized (mPackages) {
            List<InstalledPackage> installedPackages = new ArrayList<>();
            for (BPackageSettings ps : mPackages.values()) {
                if (ps.getInstalled(userId)) {
                    InstalledPackage installedPackage = new InstalledPackage();
                    installedPackage.userId = userId;
                    installedPackage.packageName = ps.pkg.packageName;
                    installedPackages.add(installedPackage);
                }
            }
            return installedPackages;
        }
    }

    @Override
    public String[] getPackagesForUid(int uid, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return new String[]{};
        synchronized (mPackages) {
            List<String> packages = new ArrayList<>();
            for (BPackageSettings ps : mPackages.values()) {
                String packageName = ps.pkg.packageName;
                if (ps.getInstalled(userId) && BUserHandle.getUid(userId, getAppId(packageName)) == uid) {
                    packages.add(packageName);
                }
            }
            return packages.toArray(new String[]{});
        }
    }

    InstallResult installFromSession(String[] files, String[] removedSplits, int mode,
                                     String expectedPackage, int userId, String installer) {
        synchronized (mInstallLock) {
            try {
                java.util.LinkedHashMap<String, String> apks = new java.util.LinkedHashMap<>();
                if (mode == PackageInstaller.SessionParams.MODE_INHERIT_EXISTING) {
                    BPackageSettings installed = mPackages.get(expectedPackage);
                    if (installed == null || !installed.getInstalled(userId)) {
                        throw new IllegalArgumentException("No package to inherit");
                    }
                    apks.put("", installed.pkg.baseCodePath);
                    if (installed.pkg.splitNames != null) for (int i = 0; i < installed.pkg.splitNames.length; i++) {
                        apks.put(installed.pkg.splitNames[i], installed.pkg.splitCodePaths[i]);
                    }
                    if (removedSplits != null) for (String removed : removedSplits) {
                        if (!apks.containsKey(removed)) throw new IllegalArgumentException("Unknown split to remove");
                        apks.remove(removed);
                    }
                } else if (removedSplits != null && removedSplits.length != 0) {
                    throw new IllegalArgumentException("Split removal requires inherit mode");
                }
                java.util.HashSet<String> supplied = new java.util.HashSet<>();
                for (String file : files) {
                    ApkLiteInspector lite = new ApkLiteInspector(new File(file));
                    if (expectedPackage != null && !expectedPackage.equals(lite.packageName)) {
                        throw new IllegalArgumentException("Session package mismatch");
                    }
                    String name = lite.splitName == null ? "" : lite.splitName;
                    if (!supplied.add(name)) throw new IllegalArgumentException("Duplicate split APK");
                    apks.put(name, file);
                }
                return installPackageSetLocked(apks.values().toArray(new String[0]),
                        InstallOption.installByStorage(), userId, installer);
            } catch (Exception e) {
                InstallResult result = new InstallResult().installError(e.getMessage());
                result.status = PackageInstaller.STATUS_FAILURE_INVALID;
                return result;
            }
        }
    }

    private InstallResult installPackageSetLocked(String[] files, InstallOption option, int userId, String installer) {
        InstallResult result = new InstallResult();
        File input = new File(BEnvironment.getCacheDir(), "install-" + UUID.randomUUID());
        AtomicPackageDirectory transaction = null;
        boolean published = false;
        try {
            if (userId < 0 || files == null || files.length == 0 || files.length > 512 || option == null) {
                throw new IllegalArgumentException("Invalid install request");
            }
            if ((option.flags & ~(InstallOption.FLAG_STORAGE | InstallOption.FLAG_URI_FILE | InstallOption.FLAG_SYSTEM)) != 0) {
                throw new IllegalArgumentException("Unsupported install option");
            }
            if (!input.mkdirs()) throw new IOException("Cannot create install input directory");
            for (int i = 0; i < files.length; i++) {
                File destination = new File(input, "input" + i + ".apk");
                try (InputStream stream = option.isFlag(InstallOption.FLAG_URI_FILE)
                        ? BlackBoxCore.getContext().getContentResolver().openInputStream(Uri.parse(files[i]))
                        : new FileInputStream(files[i])) {
                    if (stream == null) throw new IOException("Cannot read APK input");
                    copyAndSync(stream, destination);
                }
            }
            // Cluster parsing verifies base/split identity, dependencies and every APK's real signature.
            PackageParser.Package parsed = parserApk(input.getAbsolutePath());
            if (parsed == null) throw new IllegalArgumentException("APK set could not be parsed or verified");
            BPackage candidate = new BPackage(parsed);
            result.packageName = candidate.packageName;
            int splitCount = candidate.splitCodePaths == null ? 0 : candidate.splitCodePaths.length;
            if (splitCount + 1 != files.length) throw new IllegalArgumentException("Incomplete or duplicate APK set");
            ArrayList<String> dependencies = new ArrayList<>();
            boolean splitRequired = false;
            File[] stagedInput = input.listFiles();
            if (stagedInput == null) throw new IOException("Cannot list input APKs");
            for (File apk : stagedInput) {
                ApkLiteInspector lite = new ApkLiteInspector(apk);
                splitRequired |= lite.splitRequired;
                dependencies.add(lite.usesSplitName);
                dependencies.add(lite.configForSplit);
            }
            PackageSetValidator.checkSplitRequirements(candidate.splitNames, splitRequired, dependencies.toArray(new String[0]));
            BPackageSettings previous = mPackages.get(candidate.packageName);
            PackageSetValidator.check(candidate.packageName, candidate.longVersionCode, candidate.splitNames,
                    MicroGSignaturePolicy.actualSignerDigests(candidate.mSignatures),
                    previous == null ? null : previous.pkg.packageName,
                    previous == null ? 0 : previous.pkg.longVersionCode,
                    previous == null ? null : MicroGSignaturePolicy.actualSignerDigests(previous.pkg.mSignatures));
            for (BPackageSettings other : mPackages.values()) {
                if (candidate.mSharedUserId != null && candidate.mSharedUserId.equals(other.pkg.mSharedUserId)
                        && !PackageSetValidator.sameSigners(MicroGSignaturePolicy.actualSignerDigests(candidate.mSignatures),
                        MicroGSignaturePolicy.actualSignerDigests(other.pkg.mSignatures))) {
                    throw new SecurityException("Shared UID signer mismatch");
                }
                if (!other.pkg.packageName.equals(candidate.packageName)) {
                    java.util.HashSet<String> authorities = new java.util.HashSet<>();
                    for (BPackage.Provider provider : other.pkg.providers) if (provider.info.authority != null) {
                        Collections.addAll(authorities, provider.info.authority.split(";"));
                    }
                    for (BPackage.Provider provider : candidate.providers) if (provider.info.authority != null) {
                        for (String authority : provider.info.authority.split(";")) {
                            if (authorities.contains(authority)) throw new SecurityException("Content provider authority already belongs to another package");
                        }
                    }
                }
            }
            candidate.trustedMicroG = MicroGSignaturePolicy.isApproved(new File(candidate.baseCodePath), candidate);
            if ("com.google.android.gms".equals(candidate.packageName) && !candidate.trustedMicroG) {
                throw new SecurityException("microG build is not pinned by this Mybox build");
            }
            if ("com.android.vending".equals(candidate.packageName)
                    && !PackageSetValidator.isOfficialGoogleSigner(MicroGSignaturePolicy.actualSignerDigests(candidate.mSignatures))) {
                throw new SecurityException("Play Store must carry an approved original Google signer");
            }
            transaction = new AtomicPackageDirectory(BEnvironment.getAppRootDir(), candidate.packageName);
            File libraryDirectory = new File(transaction.stage, "lib");
            if (!libraryDirectory.mkdir()) throw new IOException("Cannot create native library directory");
            String[] stagedPaths = new String[splitCount + 1];
            stagedPaths[0] = candidate.baseCodePath;
            for (int i = 0; i < splitCount; i++) stagedPaths[i + 1] = candidate.splitCodePaths[i];
            for (int i = 0; i < stagedPaths.length; i++) {
                File source = new File(stagedPaths[i]);
                String name = i == 0 ? "base.apk" : "split_" + candidate.splitNames[i - 1] + ".apk";
                File target = new File(transaction.stage, name);
                try (InputStream stream = new FileInputStream(source)) { copyAndSync(stream, target); }
                String[] supported = BlackBoxCore.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
                if (supported.length == 0) throw new IllegalStateException("No ABI for the current process");
                ApkNativeLibraries.extract(target, libraryDirectory, supported[0]);
                if (i == 0) candidate.baseCodePath = new File(BEnvironment.getAppDir(candidate.packageName), name).getAbsolutePath();
                else candidate.splitCodePaths[i - 1] = new File(BEnvironment.getAppDir(candidate.packageName), name).getAbsolutePath();
            }
            if (!sUserManager.exists(userId)) sUserManager.createUser(userId);
            BPackageSettings installed = mSettings.getPackageLPw(candidate.packageName, parsed, InstallOption.installByStorage());
            installed.pkg = candidate;
            candidate.installOption = installed.installOption;
            candidate.mExtras = installed;
            installed.setInstalled(true, userId);
            installed.installerPackageName = installer;
            installed.lastUpdateTime = System.currentTimeMillis();
            if (installed.firstInstallTime == 0) installed.firstInstallTime = installed.lastUpdateTime;
            if (!installed.save(new File(transaction.stage, "package.conf"))) throw new IOException("Cannot save package settings");
            if (new top.niunaijun.blackbox.core.system.pm.installer.CreateUserExecutor()
                    .exec(installed, installed.installOption, userId) != 0) throw new IOException("Cannot create user directory");
            BProcessManagerService.get().killAllByPackageName(candidate.packageName);
            synchronized (mPackages) {
                transaction.commit();
                try {
                    if (previous != null) mComponentResolver.removeAllComponents(previous.pkg);
                    mPackages.put(candidate.packageName, installed);
                    mComponentResolver.addAllComponents(candidate);
                    published = true;
                } catch (Throwable indexingFailure) {
                    transaction.rollbackPublished();
                    mComponentResolver.removeAllComponents(candidate);
                    if (previous == null) mPackages.remove(candidate.packageName);
                    else {
                        mPackages.put(candidate.packageName, previous);
                        mComponentResolver.addAllComponents(previous.pkg);
                    }
                    throw indexingFailure;
                }
            }
            onPackageInstalled(candidate.packageName, userId);
            notifyPackageChanged(candidate.packageName, installed.appId, userId, previous != null);
            return result;
        } catch (Throwable error) {
            if (published) {
                Slog.e(TAG, "Package published; post-install notification failed", error);
                return result;
            }
            result.status = error instanceof SecurityException ? PackageInstaller.STATUS_FAILURE_CONFLICT
                    : error instanceof IOException ? PackageInstaller.STATUS_FAILURE_STORAGE : PackageInstaller.STATUS_FAILURE_INVALID;
            return result.installError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        } finally {
            try { if (transaction != null) transaction.abort(); }
            catch (IOException e) { Slog.w(TAG, "Install stage cleanup deferred"); }
            try { AtomicPackageDirectory.delete(input); }
            catch (IOException e) { Slog.w(TAG, "Install input cleanup deferred"); }
        }
    }

    private static void copyAndSync(InputStream input, File destination) throws IOException {
        try (FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private void notifyPackageChanged(String packageName, int appId, int userId, boolean replacing) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:" + packageName));
        intent.putExtra(Intent.EXTRA_UID, BUserHandle.getUid(userId, appId));
        intent.putExtra(Intent.EXTRA_REPLACING, replacing);
        top.niunaijun.blackbox.core.system.am.BActivityManagerService.get().sendPackageBroadcast(intent, userId);
        if (replacing) {
            Intent replaced = new Intent(intent).setAction(Intent.ACTION_PACKAGE_REPLACED);
            top.niunaijun.blackbox.core.system.am.BActivityManagerService.get().sendPackageBroadcast(replaced, userId);
        }
    }

    private PackageParser.Package parserApk(String file) {
        try {
            PackageParser parser = PackageParserCompat.createParser(new File(file));
            PackageParser.Package aPackage = PackageParserCompat.parsePackage(parser, new File(file), 0);
            PackageParserCompat.collectCertificates(parser, aPackage, 0);
            return aPackage;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }

    static String fixProcessName(String defProcessName, String processName) {
        if (processName == null) {
            return defProcessName;
        }
        return processName;
    }

    /**
     * Update given flags based on encryption status of current user.
     */
    private int updateFlags(int flags, int userId) {
        if ((flags & (PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_DIRECT_BOOT_AWARE)) != 0) {
            // Caller expressed an explicit opinion about what encryption
            // aware/unaware components they want to see, so fall through and
            // give them what they want
        } else {
            // Caller expressed no opinion, so match based on user state
            flags |= PackageManager.MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
        }
        return flags;
    }

    public int getAppId(String packageName) {
        BPackageSettings bPackageSettings = mPackages.get(packageName);
        if (bPackageSettings != null)
            return bPackageSettings.appId;
        return -1;
    }

    Settings getSettings() {
        return mSettings;
    }

    public void addPackageMonitor(PackageMonitor monitor) {
        mPackageMonitors.add(monitor);
    }

    public void removePackageMonitor(PackageMonitor monitor) {
        mPackageMonitors.remove(monitor);
    }

    void onPackageUninstalled(String packageName, boolean isRemove, int userId) {
        for (PackageMonitor packageMonitor : mPackageMonitors) {
            packageMonitor.onPackageUninstalled(packageName, isRemove, userId);
        }
        Slog.d(TAG, "onPackageUninstalled: " + packageName + ", userId: " + userId);
    }

    void onPackageInstalled(String packageName, int userId) {
        for (PackageMonitor packageMonitor : mPackageMonitors) {
            packageMonitor.onPackageInstalled(packageName, userId);
        }
        Slog.d(TAG, "onPackageInstalled: " + packageName + ", userId: " + userId);
    }

    public BPackageSettings getBPackageSetting(String packageName) {
        return mPackages.get(packageName);
    }

    public List<BPackageSettings> getBPackageSettings() {
        return new ArrayList<>(mPackages.values());
    }

    @Override
    public void systemReady() {
        mSettings.scanPackage();
        for (BPackageSettings value : mPackages.values()) {
            mComponentResolver.removeAllComponents(value.pkg);
            mComponentResolver.addAllComponents(value.pkg);
        }
    }
}
