package top.niunaijun.blackbox.core.system.am;

import android.content.BroadcastReceiver;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.entity.am.ReceiverData;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.pm.BPackage;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.core.system.pm.PackageMonitor;
import top.niunaijun.blackbox.entity.am.PendingResultData;
import top.niunaijun.blackbox.proxy.ProxyBroadcastReceiver;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Created by BlackBox on 2022/2/28.
 */
public class BroadcastManager implements PackageMonitor {
    public static final String TAG = "BroadcastManager";

    public static final int TIMEOUT = 9000;

    public static final int MSG_TIME_OUT = 1;

    private static BroadcastManager sBroadcastManager;

    private final BActivityManagerService mAms;
    private final BPackageManagerService mPms;
    private final Map<String, List<BroadcastReceiver>> mReceivers = new HashMap<>();
    private final Map<String, PendingResultData> mReceiversData = new HashMap<>();
    private final Map<IBinder, DynamicReceiver> mDynamicReceivers = new HashMap<>();
    private final ExecutorService mDispatch = Executors.newSingleThreadExecutor();

    private static final class DynamicReceiver {
        final IIntentReceiver receiver;
        final ProcessRecord process;
        final List<IntentFilter> filters = new ArrayList<>();

        DynamicReceiver(IBinder binder, ProcessRecord process) {
            this.receiver = IIntentReceiver.Stub.asInterface(binder);
            this.process = process;
        }
    }

    public void registerReceiver(IBinder binder, IntentFilter filter, int userId) throws RemoteException {
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (process == null || process.userId != userId || binder == null || filter == null) {
            throw new SecurityException("Receiver registration does not belong to a running guest");
        }
        synchronized (mDynamicReceivers) {
            DynamicReceiver receiver = mDynamicReceivers.get(binder);
            if (receiver == null) {
                receiver = new DynamicReceiver(binder, process);
                binder.linkToDeath(() -> {
                    synchronized (mDynamicReceivers) { mDynamicReceivers.remove(binder); }
                }, 0);
                mDynamicReceivers.put(binder, receiver);
            } else if (receiver.process.pid != process.pid) {
                throw new SecurityException("Receiver belongs to another guest process");
            }
            receiver.filters.add(new IntentFilter(filter));
        }
    }

    public void unregisterReceiver(IBinder binder) {
        synchronized (mDynamicReceivers) {
            DynamicReceiver receiver = mDynamicReceivers.get(binder);
            if (receiver != null && receiver.process.pid != Binder.getCallingPid()) {
                throw new SecurityException("Receiver belongs to another guest process");
            }
            mDynamicReceivers.remove(binder);
        }
    }

    // Called only by trusted server code; protected package actions stay inside the container.
    public void sendPackageBroadcast(Intent original, int userId) {
        Intent intent = new Intent(original);
        mDispatch.execute(() -> {
            List<DynamicReceiver> dynamic = new ArrayList<>();
            synchronized (mDynamicReceivers) {
                for (DynamicReceiver receiver : mDynamicReceivers.values()) {
                    if (receiver.process.userId != userId || intent.getComponent() != null) continue;
                    if (intent.getPackage() != null && !intent.getPackage().equals(receiver.process.getPackageName())) continue;
                    for (IntentFilter filter : receiver.filters) {
                        if (filter.match(BlackBoxCore.getContext().getContentResolver(), intent, true, TAG) >= 0) {
                            dynamic.add(receiver);
                            break;
                        }
                    }
                }
            }
            for (DynamicReceiver receiver : dynamic) {
                try {
                    receiver.receiver.performReceive(new Intent(intent), 0, null, null, false, false, userId);
                } catch (RemoteException failure) {
                    Slog.e(TAG, "Dynamic package broadcast failed: " + receiver.process.getPackageName(), failure);
                }
            }
            if ((intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) != 0) return;
            try {
                List<ResolveInfo> targets = mPms.queryBroadcastReceivers(intent, 0, null, userId);
                for (ResolveInfo target : targets) {
                    ProcessRecord process = BProcessManagerService.get().startProcessLocked(
                            target.activityInfo.packageName, target.activityInfo.processName, userId, -1,
                            android.os.Process.myPid());
                    if (process == null) {
                        Slog.e(TAG, "Cannot start broadcast receiver: " + target.activityInfo.name);
                        continue;
                    }
                    ReceiverData data = new ReceiverData();
                    data.intent = new Intent(intent);
                    data.activityInfo = target.activityInfo;
                    data.data = PendingResultData.forVirtualBroadcast(userId);
                    process.bActivityThread.scheduleReceiver(data);
                }
            } catch (RemoteException failure) {
                Slog.e(TAG, "Static package broadcast failed: " + intent.getAction(), failure);
            }
        });
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case MSG_TIME_OUT:
                    try {
                        PendingResultData data = (PendingResultData) msg.obj;
                        synchronized (mReceiversData) { mReceiversData.remove(data.mBToken); }
                        data.build().finish();
                        Slog.d(TAG, "Receiver timed out");
                    } catch (Throwable ignore) {
                    }
                    break;
            }
        }
    };

    public static BroadcastManager startSystem(BActivityManagerService ams, BPackageManagerService pms) {
        if (sBroadcastManager == null) {
            synchronized (BroadcastManager.class) {
                if (sBroadcastManager == null) {
                    sBroadcastManager = new BroadcastManager(ams, pms);
                }
            }
        }
        return sBroadcastManager;
    }

    public BroadcastManager(BActivityManagerService ams, BPackageManagerService pms) {
        mAms = ams;
        mPms = pms;
    }

    public void startup() {
        mPms.addPackageMonitor(this);
        List<BPackageSettings> bPackageSettings = mPms.getBPackageSettings();
        for (BPackageSettings bPackageSetting : bPackageSettings) {
            BPackage bPackage = bPackageSetting.pkg;
            registerPackage(bPackage);
        }
    }

    private void registerPackage(BPackage bPackage) {
        synchronized (mReceivers) {
            Slog.d(TAG, "register: " + bPackage.packageName + ", size: " + bPackage.receivers.size());
            for (BPackage.Activity receiver : bPackage.receivers) {
                List<BPackage.ActivityIntentInfo> intents = receiver.intents;
                for (BPackage.ActivityIntentInfo intent : intents) {
                    ProxyBroadcastReceiver proxyBroadcastReceiver = new ProxyBroadcastReceiver();
                    BlackBoxCore.getContext().registerReceiver(proxyBroadcastReceiver, intent.intentFilter);
                    addReceiver(bPackage.packageName, proxyBroadcastReceiver);
                }
            }
        }
    }

    private void addReceiver(String packageName, BroadcastReceiver receiver) {
        List<BroadcastReceiver> broadcastReceivers = mReceivers.get(packageName);
        if (broadcastReceivers == null) {
            broadcastReceivers = new ArrayList<>();
            mReceivers.put(packageName, broadcastReceivers);
        }
        broadcastReceivers.add(receiver);
    }

    public void sendBroadcast(PendingResultData pendingResultData) {
        synchronized (mReceiversData) {
            // Slog.d(TAG, "sendBroadcast: " + pendingResultData);
            mReceiversData.put(pendingResultData.mBToken, pendingResultData);
            Message obtain = Message.obtain(mHandler, MSG_TIME_OUT, pendingResultData);
            mHandler.sendMessageDelayed(obtain, TIMEOUT);
        }
    }

    public void finishBroadcast(PendingResultData data) {
        synchronized (mReceiversData) {
            // Slog.d(TAG, "finishBroadcast: " + data);
            PendingResultData pending = mReceiversData.remove(data.mBToken);
            if (pending != null) mHandler.removeMessages(MSG_TIME_OUT, pending);
        }
    }

    private void unregisterPackage(String packageName) {
        List<BroadcastReceiver> receivers = mReceivers.remove(packageName);
        if (receivers == null) return;
        for (BroadcastReceiver receiver : receivers) {
            try {
                BlackBoxCore.getContext().unregisterReceiver(receiver);
            } catch (IllegalArgumentException failure) {
                Slog.e(TAG, "Receiver was already unregistered: " + packageName, failure);
            }
        }
    }

    @Override
    public void onPackageUninstalled(String packageName, boolean removeApp, int userId) {
        if (removeApp) {
            synchronized (mReceivers) { unregisterPackage(packageName); }
        }
    }

    @Override
    public void onPackageInstalled(String packageName, int userId) {
        synchronized (mReceivers) {
            unregisterPackage(packageName);
            BPackageSettings bPackageSetting = mPms.getBPackageSetting(packageName);
            if (bPackageSetting != null) {
                registerPackage(bPackageSetting.pkg);
            }
        }
    }
}
