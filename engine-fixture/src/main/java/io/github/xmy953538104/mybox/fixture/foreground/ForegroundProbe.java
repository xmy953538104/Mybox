package io.github.xmy953538104.mybox.fixture.foreground;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.widget.RemoteViews;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Public Android APIs in the guest; the instrumentation independently inspects real OS state. */
public final class ForegroundProbe {
    private static final Map<Class<?>, ProbeService> LIVE = new HashMap<>();
    private static final Map<Class<?>, Integer> DESTROYED = new HashMap<>();
    private static final int NEGATIVE_ID = -56862258;

    public static void run(Activity activity) {
        final String run;
        try { run = UUID.fromString(new String(Files.readAllBytes(new File(activity.getFilesDir(), "foreground-run").toPath()), StandardCharsets.UTF_8)).toString(); }
        catch (Exception failure) { throw new IllegalStateException("Invalid foreground fixture request", failure); }
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try { result.put("runId", run); probe(activity, run, result); }
            catch (Throwable failure) {
                try { result.put("failure", failure.getClass().getName() + ": " + failure.getMessage()); }
                catch (Exception ignored) { }
            } finally {
                try { main(activity, () -> { for (ProbeService service : new java.util.ArrayList<>(LIVE.values())) { service.stopForeground(true); service.stopSelf(); } return null; }); }
                catch (Exception ignored) { }
            }
            try { publish(activity, "foreground-result-" + run + ".json", result); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }, "fixture-foreground").start();
    }

    private static void probe(Activity activity, String run, JSONObject result) throws Exception {
        main(activity, () -> {
            LIVE.clear(); DESTROYED.clear();
            activity.startForegroundService(start(activity, ServiceA.class, run, "A", true));
            activity.startForegroundService(start(activity, ServiceB.class, run, "B", true));
            return null;
        });
        await(activity, () -> LIVE.containsKey(ServiceA.class) && LIVE.containsKey(ServiceB.class)
                && LIVE.get(ServiceA.class).starts == 1 && LIVE.get(ServiceB.class).starts == 1, "Both foreground Services did not start");
        int first = main(activity, () -> LIVE.get(ServiceA.class).lastId);
        main(activity, () -> { activity.startForegroundService(start(activity, ServiceA.class, run, "A", true)); return null; });
        await(activity, () -> LIVE.get(ServiceA.class).starts == 2, "Second start was not delivered");
        require(!main(activity, () -> LIVE.get(ServiceA.class).stopSelfResult(first)), "Old startId stopped a newer start");
        result.put("startId", true);

        java.util.concurrent.CountDownLatch nullBinding = new java.util.concurrent.CountDownLatch(1);
        android.content.ServiceConnection boundOnly = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name, IBinder service) { }
            @Override public void onServiceDisconnected(android.content.ComponentName name) { }
            @Override public void onNullBinding(android.content.ComponentName name) { nullBinding.countDown(); }
        };
        boolean admitted = main(activity, () -> activity.bindService(start(activity, ServiceE.class, run, "E", true), boundOnly, Context.BIND_AUTO_CREATE));
        require(admitted, "Android rejected the bound-only Service");
        try {
            require(nullBinding.await(15, TimeUnit.SECONDS), "Real null-binding callback missing");
            main(activity, () -> {
                ProbeService service = LIVE.get(ServiceE.class);
                require(service != null && service.starts == 0, "Bound-only Service was unexpectedly started");
                if (Build.VERSION.SDK_INT >= 29) require(service.getForegroundServiceType() == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, "Bound Service has no real foreground type");
                return null;
            });
            java.util.concurrent.CountDownLatch otherBinding = new java.util.concurrent.CountDownLatch(1);
            android.content.ServiceConnection other = new android.content.ServiceConnection() {
                @Override public void onServiceConnected(android.content.ComponentName name, IBinder binder) { }
                @Override public void onServiceDisconnected(android.content.ComponentName name) { }
                @Override public void onNullBinding(android.content.ComponentName name) { otherBinding.countDown(); }
            };
            require(main(activity, () -> activity.bindService(start(activity, ServiceC.class, run, "C", false), other, Context.BIND_AUTO_CREATE)), "Competing bound Service rejected before creation");
            try {
                require(otherBinding.await(15, TimeUnit.SECONDS), "Competing bound callback missing");
                main(activity, () -> {
                    ProbeService competing = LIVE.get(ServiceC.class);
                    try { competing.startForeground(44, competing.notification(competing.channel)); throw new AssertionError("A second bound Service took the active carrier"); }
                    catch (IllegalStateException expected) { }
                    competing.stopForeground(Service.STOP_FOREGROUND_REMOVE);
                    if (Build.VERSION.SDK_INT >= 29) require(competing.getForegroundServiceType() == 0, "A different guest inherited foreground state");
                    return null;
                });
            } finally { main(activity, () -> { activity.unbindService(other); return null; }); }
            await(activity, () -> !LIVE.containsKey(ServiceC.class), "Competing bound Service did not destroy");
            result.put("boundOwnershipIsolated", true);
            phase(activity, run, "bound");
            main(activity, () -> {
                ProbeService service = LIVE.get(ServiceE.class);
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
                service.stopForeground(Service.STOP_FOREGROUND_DETACH);
                service.stopForeground(false);
                if (Build.VERSION.SDK_INT >= 29) require(service.getForegroundServiceType() == 0, "Bound-only stop entered foreground mode");
                return null;
            });
            phase(activity, run, "bound-cleared");
            main(activity, () -> { LIVE.get(ServiceE.class).startForeground(43, LIVE.get(ServiceE.class).notification(LIVE.get(ServiceE.class).channel)); return null; });
        } finally { main(activity, () -> { activity.unbindService(boundOnly); return null; }); }
        await(activity, () -> !LIVE.containsKey(ServiceE.class), "Bound-only Service did not destroy after unbind");
        phase(activity, run, "bound-destroyed");
        result.put("boundStopWithoutStart", true).put("boundPromotedWithoutStart", true).put("boundDestroyClearsForeground", true);

        main(activity, () -> {
            activity.startService(start(activity, ServiceC.class, run, "C", false));
            activity.startService(start(activity, ServiceD.class, run, "D", false));
            return null;
        });
        await(activity, () -> LIVE.containsKey(ServiceC.class) && LIVE.containsKey(ServiceD.class), "Four physical slots did not fill");
        main(activity, () -> {
            try { activity.startService(start(activity, ServiceE.class, run, "E", false)); throw new AssertionError("A fifth physical Service was accepted"); }
            catch (IllegalStateException expected) { }
            require(!LIVE.containsKey(ServiceE.class), "Rejected Service was instantiated");
            LIVE.get(ServiceC.class).stopSelf(); LIVE.get(ServiceD.class).stopSelf(); return null;
        });
        await(activity, () -> !LIVE.containsKey(ServiceC.class) && !LIVE.containsKey(ServiceD.class), "Auxiliary Services did not stop");
        result.put("capacityRejected", true);

        main(activity, () -> {
            ProbeService service = LIVE.get(ServiceA.class);
            try { service.startForeground(100, service.notification("missing-channel")); throw new AssertionError("Missing channel was accepted"); }
            catch (IllegalArgumentException expected) { }
            Notification custom = service.notification(service.channel);
            custom.contentView = new RemoteViews(activity.getPackageName(), android.R.layout.simple_list_item_1);
            try { service.startForeground(100, custom); throw new AssertionError("Custom RemoteViews were silently dropped"); }
            catch (UnsupportedOperationException expected) { }
            if (Build.VERSION.SDK_INT >= 29) {
                try { service.startForeground(100, service.notification(service.channel), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION); throw new AssertionError("Unsupported foreground type was accepted"); }
                catch (UnsupportedOperationException expected) { }
                require(service.getForegroundServiceType() == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, "Physical foreground type differs");
            }
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            require(manager.getNotificationChannel(service.channel) != null, "Guest channel lookup failed");
            String group = "fixture-group-" + run;
            manager.createNotificationChannelGroup(new NotificationChannelGroup(group, "Fixture group"));
            require(manager.getNotificationChannelGroup(group) != null, "Guest group lookup failed");
            manager.deleteNotificationChannelGroup(group);
            manager.notify("one", 91, service.notification(service.channel));
            manager.notify("two", 91, service.notification(service.channel));
            return null;
        });
        await(activity, () -> active(activity, "one", 91) && active(activity, "two", 91), "Distinct notification tags collided");
        main(activity, () -> { activity.getSystemService(NotificationManager.class).cancel("one", 91); return null; });
        await(activity, () -> !active(activity, "one", 91) && active(activity, "two", 91), "Tagged cancellation removed a different notification");
        main(activity, () -> { activity.getSystemService(NotificationManager.class).cancel("two", 91); return null; });
        result.put("explicitFailures", true).put("channelAndTag", true);
        phase(activity, run, "both");

        main(activity, () -> {
            ProbeService service = LIVE.get(ServiceA.class);
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
            require(service.stopSelfResult(service.lastId), "Latest startId was rejected");
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
            service.stopForeground(true);
            return null;
        });
        await(activity, () -> !LIVE.containsKey(ServiceA.class) && LIVE.containsKey(ServiceB.class), "Independent removal failed");
        phase(activity, run, "removed");
        result.put("removeIndependent", true).put("stoppingStopIsIdempotent", true);

        main(activity, () -> { ProbeService service = LIVE.get(ServiceB.class); service.stopForeground(Service.STOP_FOREGROUND_DETACH); service.stopSelf(); return null; });
        await(activity, () -> !LIVE.containsKey(ServiceB.class), "Detached Service did not stop");
        phase(activity, run, "detached");
        main(activity, () -> {
            require(DESTROYED.get(ServiceA.class) == 1 && DESTROYED.get(ServiceB.class) == 1, "Guest onDestroy did not run exactly once");
            activity.getSystemService(NotificationManager.class).cancel(42);
            return null;
        });
        phase(activity, run, "cancelled");
        result.put("detachRetained", true).put("destroyExactlyOnce", true);
    }

    private static boolean active(Context context, String tag, int id) {
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (id == item.getId() && java.util.Objects.equals(tag, item.getTag())) return true;
        }
        return false;
    }
    private static Intent start(Context context, Class<?> type, String run, String label, boolean foreground) {
        return new Intent(context, type).putExtra("runId", run).putExtra("label", label).putExtra("foreground", foreground);
    }
    private interface Checked<T> { T run() throws Exception; }
    private static <T> T main(Activity activity, Checked<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action::run); activity.runOnUiThread(task);
        try { return task.get(15, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }
    private static void await(Activity activity, Checked<Boolean> condition, String error) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (!main(activity, condition)) { if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError(error); Thread.sleep(50); }
    }
    private static void phase(Activity activity, String run, String phase) throws Exception {
        publish(activity, "foreground-phase-" + run + ".json", new JSONObject().put("runId", run).put("phase", phase).put("pid", android.os.Process.myPid()));
        File ack = new File(activity.getFilesDir(), "foreground-ack-" + run + "-" + phase);
        long deadline = SystemClock.elapsedRealtime() + 25_000;
        while (!ack.isFile()) { if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError("Host did not verify OS phase " + phase); Thread.sleep(50); }
    }
    private static void publish(Context context, String name, JSONObject value) throws Exception {
        File target = new File(context.getFilesDir(), name), temporary = new File(context.getFilesDir(), name + ".tmp");
        Files.write(temporary.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void require(boolean condition, String error) { if (!condition) throw new AssertionError(error); }

    public abstract static class ProbeService extends Service {
        String run, label, channel;
        int starts, lastId;
        @Override public void onCreate() { super.onCreate(); LIVE.put(getClass(), this); }
        @Override public int onStartCommand(Intent intent, int flags, int startId) {
            run = intent.getStringExtra("runId"); label = intent.getStringExtra("label"); channel = "fixture-fgs-" + run;
            lastId = startId; starts++;
            if (intent.getBooleanExtra("foreground", false)) {
                getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(channel, "Foreground fixture", NotificationManager.IMPORTANCE_LOW));
                int id = label.equals("A") ? NEGATIVE_ID : 42;
                if (Build.VERSION.SDK_INT >= 29) startForeground(id, notification(channel), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                else startForeground(id, notification(channel));
            }
            return START_NOT_STICKY;
        }
        Notification notification(String channel) {
            int icon = getResources().getIdentifier("foreground_probe_icon", "drawable", getPackageName());
            require(icon != 0, "Fixture resource icon is missing");
            Notification.Builder builder = new Notification.Builder(this, channel).setSmallIcon(icon)
                    .setContentTitle("FG " + run + " " + label).setContentText("Real foreground fixture")
                    .setProgress(10, starts, false).setOngoing(true);
            new Notification.BigTextStyle(builder).bigText("Guest resource icon and actual foreground lifecycle");
            if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            return builder.build();
        }
        @Override public IBinder onBind(Intent intent) {
            if (intent.hasExtra("runId")) {
                run = intent.getStringExtra("runId"); label = intent.getStringExtra("label"); channel = "fixture-fgs-" + run;
            }
            if (intent.getBooleanExtra("foreground", false)) {
                getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(channel, "Foreground fixture", NotificationManager.IMPORTANCE_LOW));
                startForeground(43, notification(channel));
            }
            return null;
        }
        @Override public void onDestroy() {
            LIVE.remove(getClass()); DESTROYED.put(getClass(), DESTROYED.getOrDefault(getClass(), 0) + 1); super.onDestroy();
        }
    }
    public static final class ServiceA extends ProbeService { }
    public static final class ServiceB extends ProbeService { }
    public static final class ServiceC extends ProbeService { }
    public static final class ServiceD extends ProbeService { }
    public static final class ServiceE extends ProbeService { }
    private ForegroundProbe() { }
}
