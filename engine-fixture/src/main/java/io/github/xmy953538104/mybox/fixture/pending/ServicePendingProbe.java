package io.github.xmy953538104.mybox.fixture.pending;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInstaller;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Uses public PendingIntent/Service APIs; the host verifies the independent physical OS state. */
public final class ServicePendingProbe {
    private static final String RELAY = "mybox.fixture.pending.service.relay";
    private static final int MUTABLE = PendingIntent.FLAG_MUTABLE, IMMUTABLE = PendingIntent.FLAG_IMMUTABLE;

    public static void run(Activity activity) {
        final String run;
        try {
            File request = new File(activity.getFilesDir(), "service-pending-run");
            run = UUID.fromString(new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8)).toString();
            Files.delete(request.toPath());
        } catch (Exception failure) { throw new IllegalStateException("Invalid Service PendingIntent fixture request", failure); }
        new Thread(() -> {
            JSONObject report = new JSONObject();
            try { report.put("runId", run); probe(activity, run, report); }
            catch (Throwable failure) {
                try { report.put("failure", failure.getClass().getName() + ": " + failure.getMessage()); }
                catch (Exception ignored) { }
            }
            finally {
                try {
                    Files.write(file(activity, run, "work-release").toPath(), new byte[]{1});
                    activity.stopService(new Intent(activity, PlainService.class));
                    activity.stopService(new Intent(activity, ForegroundService.class));
                } catch (Exception ignored) { }
            }
            try { PendingReceiver.publish(activity, "service-pending-result-" + run + ".json", report); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }, "fixture-service-pending").start();
    }

    private static void probe(Activity activity, String run, JSONObject result) throws Exception {
        List<PendingIntent> owned = new ArrayList<>();
        try {
            String pkg = activity.getPackageName();
            int uid = activity.getPackageManager().getPackageUid(pkg, 0);
            Intent base = start(activity, PlainService.class, run, "plain").putExtra("value", 1).putExtra("defer", true);
            result.put("stage", "identity");
            PendingIntent broadcast = PendingIntent.getBroadcast(activity, 825, base, PendingIntent.FLAG_CANCEL_CURRENT | MUTABLE);
            PendingIntent service = PendingIntent.getService(activity, 825, base, PendingIntent.FLAG_CANCEL_CURRENT | MUTABLE);
            PendingIntent foregroundIdentity = PendingIntent.getForegroundService(activity, 825, base, PendingIntent.FLAG_CANCEL_CURRENT | MUTABLE);
            owned.add(broadcast); owned.add(service); owned.add(foregroundIdentity);
            require(!broadcast.equals(service) && !broadcast.equals(foregroundIdentity) && !service.equals(foregroundIdentity), "PendingIntent kinds collided");
            checkKind(broadcast, 1, pkg, uid); checkKind(service, 4, pkg, uid); checkKind(foregroundIdentity, 5, pkg, uid);
            broadcast.cancel();
            require(service.equals(PendingIntent.getService(activity, 825, base, PendingIntent.FLAG_NO_CREATE | MUTABLE)), "Cancel broadcast removed Service capability");
            require(foregroundIdentity.equals(PendingIntent.getForegroundService(activity, 825, base, PendingIntent.FLAG_NO_CREATE | MUTABLE)), "Cancel broadcast removed FGS capability");
            result.put("kindIdentity", true);
            rejectInstallerStatus(activity, service);
            rejectInstallerStatus(activity, foregroundIdentity);
            result.put("installerBroadcastBoundary", true);

            result.put("stage", "ordinary");
            PendingIntent updated = PendingIntent.getService(activity, 825, new Intent(base).putExtra("value", 2), PendingIntent.FLAG_UPDATE_CURRENT | MUTABLE);
            require(updated.equals(service), "Service update changed capability");
            Delivery plain = send(activity, service, 47, new Intent().putExtra("status", 73));
            require(plain.code == 0, "Service OnFinished must acknowledge the accepted start with zero");
            JSONObject first = waitStart(activity, run, "plain");
            require(first.getInt("value") == 2 && first.getInt("status") == 73, "Service update/fill-in was lost");
            require(first.getInt("pid") == android.os.Process.myPid() && first.getInt("startId") == 1, "Service did not start in the real guest process");
            require(!file(activity, run, "work-done").exists(), "OnFinished was confused with asynchronous Service work");
            Files.write(file(activity, run, "work-release").toPath(), new byte[]{1});
            waitFile(file(activity, run, "work-done"), 10_000);
            phase(activity, run, "ordinary");
            result.put("actualOrdinary", true).put("updateCurrent", true).put("completionScope", true);

            result.put("stage", "foreground");
            Intent foregroundIntent = start(activity, ForegroundService.class, run, "foreground").putExtra("value", 9);
            PendingIntent foreground = PendingIntent.getForegroundService(activity, 826, foregroundIntent, PendingIntent.FLAG_CANCEL_CURRENT | IMMUTABLE);
            owned.add(foreground); checkKind(foreground, 5, pkg, uid);
            require(send(activity, foreground, 59, new Intent().putExtra("status", 88)).code == 0, "FGS start was rejected");
            JSONObject foregroundStart = waitStart(activity, run, "foreground");
            require(foregroundStart.getInt("value") == 9 && foregroundStart.getInt("status") == -1, "Immutable FGS accepted fill-in");
            require(foregroundStart.getBoolean("guestMetadata"), "FGS guest-only metadata was not preserved");
            phase(activity, run, "both");
            result.put("actualForeground", true).put("immutable", true);

            result.put("stage", "transfer");
            PendingIntent once = PendingIntent.getService(activity, 827, start(activity, PlainService.class, run, "transfer"),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT | IMMUTABLE);
            owned.add(once);
            JSONObject transfer = transfer(activity, once, pkg, uid);
            require(transfer.getInt("pid") != android.os.Process.myPid(), "PendingIntent did not cross a real process");
            require(transfer.getInt("code") == 0 && transfer.getBoolean("creator"), "Transferred PendingIntent lost creator/type or failed");
            JSONObject transferred = waitStart(activity, run, "transfer");
            require(transferred.getInt("startId") == first.getInt("startId") + 1, "Unapproved physical starts reached the guest callback");
            expectCanceled(once);
            result.put("transfer", true).put("oneShot", true).put("transferPid", transfer.getInt("pid"));

            result.put("stage", "cancel-update");
            Intent replacementIntent = start(activity, PlainService.class, run, "replace");
            PendingIntent previous = PendingIntent.getService(activity, 828, replacementIntent, PendingIntent.FLAG_CANCEL_CURRENT | IMMUTABLE);
            PendingIntent replacement = PendingIntent.getService(activity, 828, replacementIntent, PendingIntent.FLAG_CANCEL_CURRENT | IMMUTABLE);
            owned.add(previous); owned.add(replacement);
            require(!previous.equals(replacement), "CANCEL_CURRENT reused the revoked capability");
            expectCanceled(previous);
            require(send(activity, replacement, 0, null).code == 0, "Replacement capability failed");
            waitStart(activity, run, "replace");
            service.cancel(); foreground.cancel();
            expectCanceled(service); expectCanceled(foreground);
            require(PendingIntent.getService(activity, 825, base, PendingIntent.FLAG_NO_CREATE | MUTABLE) == null, "Canceled Service remains discoverable");
            require(PendingIntent.getForegroundService(activity, 826, foregroundIntent, PendingIntent.FLAG_NO_CREATE | IMMUTABLE) == null, "Canceled FGS remains discoverable");
            phase(activity, run, "cancelled-live");
            result.put("cancel", true).put("cancelCurrent", true);

            result.put("stage", "rejections");
            PendingIntent wrongKind = PendingIntent.getService(activity, 829, new Intent(activity, PendingReceiver.class)
                    .putExtra("runId", run), PendingIntent.FLAG_CANCEL_CURRENT | IMMUTABLE);
            owned.add(wrongKind);
            require(send(activity, wrongKind, 0, null).code == -96, "Receiver target was reinterpreted as Service success");
            PendingIntent wrongForegroundKind = PendingIntent.getForegroundService(activity, 829,
                    new Intent(activity, PendingReceiver.class).putExtra("runId", run), PendingIntent.FLAG_CANCEL_CURRENT | IMMUTABLE);
            owned.add(wrongForegroundKind);
            require(send(activity, wrongForegroundKind, 0, null).code == -96, "Receiver target was reinterpreted as FGS success");
            require(!new File(activity.getFilesDir(), "pending-manifest-" + run + ".json").exists(), "Service PendingIntent invoked a Receiver");
            PendingIntent scoped = PendingIntent.getService(activity, 830, start(activity, PlainService.class, run, "scope"),
                    PendingIntent.FLAG_CANCEL_CURRENT | MUTABLE | Intent.FILL_IN_COMPONENT);
            owned.add(scoped);
            try {
                scoped.send(activity, 0, new Intent().setComponent(new ComponentName("com.google.android.gms", "ForgedService")));
                throw new AssertionError("Mutable Service capability escaped creator package");
            } catch (RuntimeException expected) {
                require(String.valueOf(expected.getMessage()).contains("Cross-package"), "Unexpected scope error: " + expected);
            }
            try {
                PendingIntent.getService(activity, 831, new Intent(pkg + ".IMPLICIT"), IMMUTABLE);
                throw new AssertionError("Implicit Service PendingIntent was silently accepted");
            } catch (RuntimeException expected) {
                require(String.valueOf(expected.getMessage()).contains("explicit"), "Unexpected implicit-Service error: " + expected);
            }
            result.put("failureCallback", true).put("scope", true).put("implicitRejected", true);

            result.put("stage", "stop");
            require(activity.stopService(new Intent(activity, PlainService.class)), "Plain Service was not actually started");
            require(activity.stopService(new Intent(activity, ForegroundService.class)), "Foreground Service was not actually started");
            waitFile(file(activity, run, "destroy-PlainService.json"), 10_000);
            waitFile(file(activity, run, "destroy-ForegroundService.json"), 10_000);
            phase(activity, run, "stopped");
            result.put("actualStop", true).put("stage", "complete");
        } finally { for (PendingIntent item : owned) item.cancel(); }
    }

    private static Intent start(Context context, Class<?> service, String run, String event) {
        return new Intent(context, service).setAction(context.getPackageName() + ".SERVICE_PENDING." + run)
                .putExtra("runId", run).putExtra("event", event);
    }
    private static void checkKind(PendingIntent pending, int kind, String pkg, int uid) {
        require(pkg.equals(pending.getCreatorPackage()) && pending.getCreatorUid() == uid, "Incorrect virtual creator");
        if (Build.VERSION.SDK_INT >= 31) require(!pending.isActivity() && pending.isBroadcast() == (kind == 1)
                && pending.isService() == (kind == 4) && pending.isForegroundService() == (kind == 5), "Incorrect PendingIntent kind");
    }
    private static final class Delivery {
        final int code;
        Delivery(int code) { this.code = code; }
    }
    private static Delivery send(Context context, PendingIntent pending, int code, Intent fillIn) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Delivery> result = new AtomicReference<>();
        pending.send(context, code, fillIn, (sender, intent, resultCode, data, extras) -> {
            callbacks.incrementAndGet(); result.set(new Delivery(resultCode)); done.countDown();
        }, new Handler(Looper.getMainLooper()));
        require(done.await(15, TimeUnit.SECONDS), "Service PendingIntent OnFinished did not arrive");
        require(callbacks.get() == 1, "Service PendingIntent completion was duplicated");
        return result.get();
    }
    private static void expectCanceled(PendingIntent pending) throws Exception {
        try { pending.send(); throw new AssertionError("Canceled/oneShot Service capability sent again"); }
        catch (PendingIntent.CanceledException expected) { }
    }
    private static void rejectInstallerStatus(Context context, PendingIntent service) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        int id = installer.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try {
                session.commit(service.getIntentSender());
                throw new AssertionError("Service status implicitly gained installer delivery authority");
            } catch (SecurityException expected) {
                require(String.valueOf(expected.getMessage()).contains("broadcast"), "Unexpected installer status rejection: " + expected);
            }
            try (OutputStream stream = session.openWrite("base.apk", 0, 1)) { stream.write(0); session.fsync(stream); }
        } finally { installer.abandonSession(id); }
    }
    private static JSONObject transfer(Context context, PendingIntent pending, String pkg, int uid) throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<IBinder> remote = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { remote.set(service); connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        require(context.bindService(new Intent(context, RelayService.class), connection, Context.BIND_AUTO_CREATE), "Transfer relay bind was rejected");
        try {
            require(connected.await(15, TimeUnit.SECONDS), "Transfer relay did not connect");
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(RELAY); pending.writeToParcel(data, 0); data.writeString(pkg); data.writeInt(uid);
                require(remote.get().transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0), "Relay transaction was rejected");
                reply.readException(); return new JSONObject(reply.readString());
            } finally { reply.recycle(); data.recycle(); }
        } finally { context.unbindService(connection); }
    }
    private static JSONObject waitStart(Context context, String run, String event) throws Exception {
        File target = file(context, run, "start-" + event + ".json"); waitFile(target, 15_000);
        JSONObject value = new JSONObject(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        require(run.equals(value.getString("runId")) && context.getPackageName().equals(value.getString("package")), "Wrong Service start identity");
        return value;
    }
    private static void phase(Context context, String run, String phase) throws Exception {
        PendingReceiver.publish(context, "service-pending-phase-" + run + ".json", new JSONObject()
                .put("runId", run).put("phase", phase).put("pid", android.os.Process.myPid()));
        waitFile(file(context, run, "ack-" + phase), 25_000);
    }
    private static File file(Context context, String run, String suffix) { return new File(context.getFilesDir(), "service-pending-" + run + "-" + suffix); }
    private static void waitFile(File file, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!file.isFile()) { if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError("Missing fixture state: " + file.getName()); Thread.sleep(25); }
    }
    private static void require(boolean condition, String error) { if (!condition) throw new AssertionError(error); }

    public abstract static class ObservedService extends Service {
        private String run;
        @Override public int onStartCommand(Intent intent, int flags, int startId) {
            run = UUID.fromString(intent.getStringExtra("runId")).toString();
            String event = intent.getStringExtra("event");
            try {
                if (this instanceof ForegroundService) {
                    String channel = "service-pending-" + run;
                    getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(channel, "Service PendingIntent fixture", NotificationManager.IMPORTANCE_LOW));
                    int icon = getResources().getIdentifier("foreground_probe_icon", "drawable", getPackageName());
                    require(icon != 0, "Fixture notification resource is absent");
                    String actionRun = java.util.UUID.nameUUIDFromBytes((run + ":fgs-action").getBytes(StandardCharsets.UTF_8)).toString();
                    Notification.Builder builder = new Notification.Builder(this, channel).setSmallIcon(icon)
                            .setContentTitle("PI FGS " + run).setContentText("Actual Service PendingIntent").setOngoing(true)
                            .addAction(new Notification.Action.Builder(icon, "FGS metadata", PendingIntent.getBroadcast(this, -879,
                                    NotificationMetadata.intent(new Intent("fixture.pending.NOTIFICATION_ACTION").setPackage(getPackageName())
                                            .putExtra("runId", actionRun).putExtra("value", 7), actionRun), PendingIntent.FLAG_IMMUTABLE))
                                    .addExtras(NotificationMetadata.extras(run)).build());
                    if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
                    if (Build.VERSION.SDK_INT >= 29) startForeground(-879, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    else startForeground(-879, builder.build());
                    NotificationMetadata.requirePosted(getSystemService(NotificationManager.class), -879, null, run);
                }
                PendingReceiver.publish(this, file(this, run, "start-" + event + ".json").getName(), new JSONObject()
                        .put("runId", run).put("package", getPackageName()).put("pid", android.os.Process.myPid())
                        .put("class", getClass().getName()).put("startId", startId)
                        .put("guestMetadata", this instanceof ForegroundService)
                        .put("value", intent.getIntExtra("value", -1)).put("status", intent.getIntExtra("status", -1)));
                if (intent.getBooleanExtra("defer", false)) {
                    final String taskRun = run;
                    new Thread(() -> {
                        try {
                            waitFile(file(this, taskRun, "work-release"), 20_000);
                            Files.write(file(this, taskRun, "work-done").toPath(), new byte[]{1});
                        } catch (Exception failure) { throw new IllegalStateException(failure); }
                    }, "fixture-pending-service-work").start();
                }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            return START_NOT_STICKY;
        }
        @Override public IBinder onBind(Intent intent) { return null; }
        @Override public void onDestroy() {
            try { if (run != null) PendingReceiver.publish(this, file(this, run, "destroy-" + getClass().getSimpleName() + ".json").getName(), new JSONObject().put("runId", run)); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            super.onDestroy();
        }
    }
    public static final class PlainService extends ObservedService { }
    public static final class ForegroundService extends ObservedService { }
    public static final class RelayService extends Service {
        private final Binder binder = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
                data.enforceInterface(RELAY);
                try {
                    PendingIntent pending = PendingIntent.CREATOR.createFromParcel(data);
                    String pkg = data.readString(); int uid = data.readInt();
                    checkKind(pending, 4, pkg, uid);
                    Delivery delivery = send(RelayService.this, pending, 73, null);
                    reply.writeNoException(); reply.writeString(new JSONObject().put("pid", android.os.Process.myPid())
                            .put("code", delivery.code).put("creator", true).toString());
                } catch (Throwable failure) { reply.writeException(new IllegalStateException("Transfer failed", failure)); }
                return true;
            }
        };
        @Override public IBinder onBind(Intent intent) { return binder; }
    }
    private ServicePendingProbe() { }
}
