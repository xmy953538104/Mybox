package top.niunaijun.blackbox;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContentProviderClient;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import black.android.app.BRActivityThread;
import black.android.content.BRContentProviderClient;
import black.android.os.BRUserHandle;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import top.niunaijun.blackbox.app.LauncherActivity;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.DaemonService;
import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.fake.delegate.ContentProviderDelegate;
import top.niunaijun.blackbox.fake.frameworks.BActivityManager;
import top.niunaijun.blackbox.fake.frameworks.BJobManager;
import top.niunaijun.blackbox.fake.frameworks.BPackageManager;
import top.niunaijun.blackbox.fake.frameworks.BStorageManager;
import top.niunaijun.blackbox.fake.frameworks.BUserManager;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.BundleCompat;

/**
 * Created by Milk on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@SuppressLint({"StaticFieldLeak", "NewApi"})
public class BlackBoxCore extends ClientConfiguration {
    public static final String TAG = "BlackBoxCore";

    private static final BlackBoxCore sBlackBoxCore = new BlackBoxCore();
    private static Context sContext;
    private ProcessType mProcessType;
    private final Map<String, IBinder> mServices = new HashMap<>();
    private final Object mServiceProviderLock = new Object();
    private ContentProviderClient mServiceProvider;
    private IBinder mServiceProviderBinder;
    private volatile boolean mServerConnectionLost;
    private Thread.UncaughtExceptionHandler mExceptionHandler;
    private ClientConfiguration mClientConfiguration;
    private final List<AppLifecycleCallback> mAppLifecycleCallbacks = new ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final int mHostUid = Process.myUid();
    private final int mHostUserId = BRUserHandle.get().myUserId();

    public static BlackBoxCore get() {
        return sBlackBoxCore;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public static PackageManager getPackageManager() {
        return sContext.getPackageManager();
    }

    public static String getHostPkg() {
        return get().getHostPackageName();
    }

    public static int getHostUid() {
        return get().mHostUid;
    }

    public static int getHostUserId() {
        return get().mHostUserId;
    }

    public static Context getContext() {
        return sContext;
    }

    public Thread.UncaughtExceptionHandler getExceptionHandler() {
        return mExceptionHandler;
    }

    public void setExceptionHandler(Thread.UncaughtExceptionHandler exceptionHandler) {
        mExceptionHandler = exceptionHandler;
    }

    public void doAttachBaseContext(Context context, ClientConfiguration clientConfiguration) {
        if (clientConfiguration == null) {
            throw new IllegalArgumentException("ClientConfiguration is null!");
        }
        if (Build.VERSION.SDK_INT >= 28 && !HiddenApiBypass.addHiddenApiExemptions("")) {
            throw new IllegalStateException("Mybox: hidden Android APIs could not be enabled on this firmware");
        }
        sContext = context;
        mClientConfiguration = clientConfiguration;
        initNotificationManager();

        String processName = getProcessName(getContext());
        if (processName.equals(BlackBoxCore.getHostPkg())) {
            mProcessType = ProcessType.Main;
        } else if (processName.endsWith(getContext().getString(R.string.black_box_service_name))) {
            mProcessType = ProcessType.Server;
        } else {
            mProcessType = ProcessType.BAppClient;
        }
        if (BlackBoxCore.get().isBlackProcess()) {
            BEnvironment.load();
            if (processName.endsWith("p0")) {
//                android.os.Debug.waitForDebugger();
            }
//            android.os.Debug.waitForDebugger();
        }
        if (isServerProcess()) {
            if (clientConfiguration.isEnableDaemonService()) {
                Intent intent = new Intent();
                intent.setClass(getContext(), DaemonService.class);
                if (BuildCompat.isOreo()) {
                    getContext().startForegroundService(intent);
                } else {
                    getContext().startService(intent);
                }
            }
        }
        HookManager.get().init();
    }

    public void doCreate() {
        // fix contentProvider
        if (isBlackProcess()) {
            ContentProviderDelegate.init();
        }
        if (!isServerProcess()) {
            ServiceManager.initBlackManager();
        }
    }

    public static Object mainThread() {
        return BRActivityThread.get().currentActivityThread();
    }

    public void startActivity(Intent intent, int userId) {
        if (mClientConfiguration.isEnableLauncherActivity()) {
            LauncherActivity.launch(intent, userId);
        } else {
            getBActivityManager().startActivity(intent, userId);
        }
    }

    public static BJobManager getBJobManager() {
        return BJobManager.get();
    }

    public static BPackageManager getBPackageManager() {
        return BPackageManager.get();
    }

    public static BActivityManager getBActivityManager() {
        return BActivityManager.get();
    }

    public static BStorageManager getBStorageManager() {
        return BStorageManager.get();
    }

    public boolean launchApk(String packageName, int userId) {
        Intent launchIntentForPackage = getBPackageManager().getLaunchIntentForPackage(packageName, userId);
        if (launchIntentForPackage == null) {
            return false;
        }
        startActivity(launchIntentForPackage, userId);
        return true;
    }

    public boolean isInstalled(String packageName, int userId) {
        return getBPackageManager().isInstalled(packageName, userId);
    }

    public void uninstallPackageAsUser(String packageName, int userId) {
        getBPackageManager().uninstallPackageAsUser(packageName, userId);
    }

    public void uninstallPackage(String packageName) {
        getBPackageManager().uninstallPackage(packageName);
    }

    public InstallResult installPackageAsUser(String packageName, int userId) {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName, 0);
            return getBPackageManager().installPackageAsUser(packageInfo.applicationInfo.sourceDir, InstallOption.installBySystem(), userId);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return new InstallResult().installError(e.getMessage());
        }
    }

    public InstallResult installPackageAsUser(File apk, int userId) {
        return getBPackageManager().installPackageAsUser(apk.getAbsolutePath(), InstallOption.installByStorage(), userId);
    }

    public InstallResult installPackageAsUser(Uri apk, int userId) {
        return getBPackageManager().installPackageAsUser(apk.toString(), InstallOption.installByStorage().makeUriFile(), userId);
    }

    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        return getBPackageManager().getInstalledApplications(flags, userId);
    }

    public List<PackageInfo> getInstalledPackages(int flags, int userId) {
        return getBPackageManager().getInstalledPackages(flags, userId);
    }

    public void clearPackage(String packageName, int userId) {
        BPackageManager.get().clearPackage(packageName, userId);
    }

    public void stopPackage(String packageName, int userId) {
        BPackageManager.get().stopPackage(packageName, userId);
    }

    public List<BUserInfo> getUsers() {
        return BUserManager.get().getUsers();
    }

    public BUserInfo createUser(int userId) {
        return BUserManager.get().createUser(userId);
    }

    public void deleteUser(int userId) {
        BUserManager.get().deleteUser(userId);
    }

    public List<AppLifecycleCallback> getAppLifecycleCallbacks() {
        return mAppLifecycleCallbacks;
    }

    public void removeAppLifecycleCallback(AppLifecycleCallback appLifecycleCallback) {
        mAppLifecycleCallbacks.remove(appLifecycleCallback);
    }

    public void addAppLifecycleCallback(AppLifecycleCallback appLifecycleCallback) {
        mAppLifecycleCallbacks.add(appLifecycleCallback);
    }

    public boolean isSupportGms() {
        return GmsCore.isSupportGms();
    }

    public boolean isInstallGms(int userId) {
        return GmsCore.isInstalledGoogleService(userId);
    }

    public InstallResult installGms(int userId) {
        return GmsCore.installGApps(userId);
    }

    public boolean uninstallGms(int userId) {
        GmsCore.uninstallGApps(userId);
        return !GmsCore.isInstalledGoogleService(userId);
    }

    public IBinder getService(String name) {
        if (isServerProcess()) return ServiceManager.getService(name);
        if (mServerConnectionLost) {
            throw new IllegalStateException("Virtual server connection was lost; restart this process");
        }
        synchronized (mServices) {
            IBinder cached = mServices.get(name);
            if (cached != null && cached.isBinderAlive()) return cached;
            mServices.remove(name);
        }
        Bundle vm = lookupVirtualService(name);
        IBinder binder = vm == null ? null : BundleCompat.getBinder(vm, "_B_|_server_");
        if (binder == null || !binder.isBinderAlive()) {
            throw new IllegalStateException("Virtual service unavailable: " + name);
        }
        synchronized (mServices) {
            IBinder cached = mServices.get(name);
            if (cached != null && cached.isBinderAlive()) return cached;
            mServices.put(name, binder);
            return binder;
        }
    }

    private Bundle lookupVirtualService(String name) {
        synchronized (mServiceProviderLock) {
            if (mServerConnectionLost) {
                throw new IllegalStateException("Virtual server connection was lost; restart this process");
            }
            if (mServiceProvider != null && !mServiceProviderBinder.isBinderAlive()) {
                loseServerConnection();
                throw new IllegalStateException("Virtual server died; restart this process");
            }
            if (mServiceProvider == null) {
                // Retain one stable platform dependency while this process holds server Binders.
                // A bare Binder does not prevent Android from freezing the server behind an active guest.
                ContentProviderClient client = getContext().getContentResolver()
                        .acquireContentProviderClient(ProxyManifest.getBindProvider());
                if (client == null) throw new IllegalStateException("Virtual server provider is unavailable");
                IInterface provider = BRContentProviderClient.get(client).mContentProvider();
                if (provider == null) {
                    closeProviderClient(client);
                    throw new IllegalStateException("Virtual server provider has no Binder");
                }
                mServiceProvider = client;
                mServiceProviderBinder = provider.asBinder();
            }
            Bundle request = new Bundle();
            request.putString("_B_|_server_name_", name);
            try {
                return mServiceProvider.call("VM", null, request);
            } catch (RemoteException failure) {
                loseServerConnection();
                throw new IllegalStateException("Virtual server lookup failed: " + name, failure);
            }
        }
    }

    private void loseServerConnection() {
        // Android tears down stable-provider dependents. Never attach a surviving guest's PID to
        // a replacement server whose virtual process/identity registry has not registered that PID.
        mServerConnectionLost = true;
        synchronized (mServices) { mServices.clear(); }
        ContentProviderClient client = mServiceProvider;
        mServiceProvider = null;
        mServiceProviderBinder = null;
        if (client != null) closeProviderClient(client);
    }

    @SuppressWarnings("deprecation")
    private static void closeProviderClient(ContentProviderClient client) {
        try {
            if (Build.VERSION.SDK_INT >= 24) client.close();
            else client.release();
        } catch (RuntimeException ignored) {
            // Preserve the original service failure even if the platform already removed the client.
        }
    }

    /**
     * Process type
     */
    private enum ProcessType {
        /**
         * Server process
         */
        Server,
        /**
         * Black app process
         */
        BAppClient,
        /**
         * Main process
         */
        Main,
    }

    public boolean isBlackProcess() {
        return mProcessType == ProcessType.BAppClient;
    }

    public boolean isMainProcess() {
        return mProcessType == ProcessType.Main;
    }

    public boolean isServerProcess() {
        return mProcessType == ProcessType.Server;
    }

    @Override
    public boolean isHideRoot() {
        return mClientConfiguration.isHideRoot();
    }

    @Override
    public String getHostPackageName() {
        return mClientConfiguration.getHostPackageName();
    }

    @Override
    public boolean requestInstallPackage(File file, int userId) {
        return mClientConfiguration.requestInstallPackage(file, userId);
    }

    private static String getProcessName(Context context) {
        int pid = Process.myPid();
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.pid == pid) {
                processName = info.processName;
                break;
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static boolean is64Bit() {
        if (BuildCompat.isM()) {
            return Process.is64Bit();
        } else {
            return Build.CPU_ABI.equals("arm64-v8a");
        }
    }

    private void initNotificationManager() {
        NotificationManager nm = (NotificationManager) BlackBoxCore.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        String CHANNEL_ONE_ID = BlackBoxCore.getContext().getPackageName() + ".blackbox_core";
        String CHANNEL_ONE_NAME = "blackbox_core";
        if (BuildCompat.isOreo()) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ONE_ID,
                    CHANNEL_ONE_NAME, NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.setShowBadge(true);
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(notificationChannel);
        }
    }
}
