package io.github.xmy953538104.mybox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Build;
import android.os.IBinder;
import top.niunaijun.blackbox.BlackBoxCore;

public final class KeepAliveService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        String channel = "microg-connection";
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(channel,
                    "容器后台连接", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Mybox 正在后台运行")
                .setContentText("保持容器进程可用；推送连接状态请在 microG 中查看。")
                .setContentIntent(open).setOngoing(true).build());
        new Thread(() -> {
            try {
                ((MyboxApplication) getApplication()).requireEngine();
                if (!BlackBoxCore.get().isInstalled("com.google.android.gms", 0)) {
                    throw new IllegalStateException("请先安装内置 microG");
                }
                Intent connect = new Intent("org.microg.gms.gcm.mcs.CONNECT")
                        .setClassName("com.google.android.gms", "org.microg.gms.gcm.McsService");
                ComponentName started = BlackBoxCore.getBActivityManager().startService(connect, null, false, 0);
                if (started == null) throw new IllegalStateException("microG 服务未启动");
            } catch (Throwable failure) {
                manager.notify(1, builder.setContentTitle("Mybox 后台连接启动失败")
                        .setContentText(failure.getClass().getSimpleName() + "：" + failure.getMessage()).build());
            }
        }, "Mybox-microG-start").start();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
