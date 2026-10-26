package io.github.xmy953538104.mybox.fixture.alarm;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import org.json.JSONObject;

/** Ordinary Service/AlarmManager APIs only; actual onStartCommand publishes each delivery. */
public final class ServiceAlarmProbe extends Service {
    private static final int IMMUTABLE = PendingIntent.FLAG_IMMUTABLE;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            AlarmReceiver.publish(this, intent.getStringExtra("run"),
                    "service-" + intent.getStringExtra("label") + "-" + intent.getIntExtra("value", -1),
                    new JSONObject().put("package", getPackageName()).put("pid", android.os.Process.myPid())
                            .put("value", intent.getIntExtra("value", -1)).put("startId", startId));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    public static void run(Activity activity, String run) {
        new Thread(() -> {
            JSONObject report = new JSONObject();
            java.util.List<PendingIntent> owned = new java.util.ArrayList<>();
            PendingIntent cold = null;
            try {
                AlarmManager alarms = activity.getSystemService(AlarmManager.class);
                if (android.os.Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms())
                    throw new IllegalStateException("Actual exact-alarm access is required; this probe never grants it");
                Intent original = intent(activity, run, "service-update", 1);
                PendingIntent warm = PendingIntent.getService(activity, 8201, original,
                        IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
                owned.add(warm);
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60_000, warm);
                AlarmReceiver.publish(activity, run, "service-ready", new JSONObject().put("pid", android.os.Process.myPid()));
                AlarmReceiver.waitFile(AlarmReceiver.file(activity, run, "service-ack"), 15_000);
                PendingIntent updated = PendingIntent.getService(activity, 8201,
                        intent(activity, run, "service-update", 2), IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                require(warm.equals(updated), "UPDATE_CURRENT changed the Service sender");
                schedule(alarms, updated, 750);
                AlarmReceiver.waitFile(AlarmReceiver.file(activity, run, "service-service-update-2"), 15_000);
                require(!AlarmReceiver.file(activity, run, "service-service-update-1").exists(), "Stale Service payload executed");
                warm.cancel();
                report.put("update", true);

                PendingIntent canceled = operation(activity, run, "service-cancel", 3, 8202, false);
                owned.add(canceled);
                schedule(alarms, canceled, 750); alarms.cancel(canceled);
                require(PendingIntent.getService(activity, 8202, intent(activity, run, "service-cancel", 3),
                        IMMUTABLE | PendingIntent.FLAG_NO_CREATE) != null, "Alarm cancel canceled its sender");
                PendingIntent removed = operation(activity, run, "service-remove-all", 4, 8203, false);
                owned.add(removed);
                schedule(alarms, removed, 750);
                if (android.os.Build.VERSION.SDK_INT >= 34) alarms.cancelAll(); else alarms.cancel(removed);
                Thread.sleep(1800);
                require(!AlarmReceiver.file(activity, run, "service-service-cancel-3").exists(), "Canceled Service alarm executed");
                require(!AlarmReceiver.file(activity, run, "service-service-remove-all-4").exists(), "Removed Service alarm executed");
                canceled.cancel(); removed.cancel(); report.put("cancellation", true);

                PendingIntent once = operation(activity, run, "service-once", 5, 8204, true);
                owned.add(once);
                schedule(alarms, once, 750);
                AlarmReceiver.waitFile(AlarmReceiver.file(activity, run, "service-service-once-5"), 15_000);
                require(PendingIntent.getService(activity, 8204, intent(activity, run, "service-once", 5),
                        IMMUTABLE | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_NO_CREATE) == null,
                        "One-shot Service sender was recreated");
                try { once.send(); throw new AssertionError("One-shot Service sent twice"); }
                catch (PendingIntent.CanceledException expected) { }
                report.put("oneShot", true);

                PendingIntent mutable = PendingIntent.getService(activity, 8205, intent(activity, run, "service-mutable", 6),
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
                owned.add(mutable);
                try {
                    try { schedule(alarms, mutable, 750); throw new AssertionError("Mutable Service alarm accepted"); }
                    catch (UnsupportedOperationException expected) { report.put("mutableRejected", true); }
                } finally { mutable.cancel(); }
                PendingIntent wrongTarget = PendingIntent.getService(activity, 8207,
                        new Intent(activity, AlarmReceiver.class).setAction("fixture.alarm." + run + ".wrong-kind"),
                        IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
                owned.add(wrongTarget);
                try {
                    try { schedule(alarms, wrongTarget, 750); throw new AssertionError("Receiver accepted as an OS Service"); }
                    catch (RuntimeException expected) { report.put("wrongTargetRejected", true); }
                } finally { wrongTarget.cancel(); }
                cold = operation(activity, run, "service-cold", 9, 8206, false);
                owned.add(cold);
                schedule(alarms, cold, 15_000);
                report.put("pid", android.os.Process.myPid());
            } catch (Throwable failure) {
                try { report.put("failure", failure.getClass().getName() + ": " + failure.getMessage()); }
                catch (Exception ignored) { }
            } finally {
                for (PendingIntent operation : owned) if (operation != cold || report.has("failure")) operation.cancel();
            }
            try { AlarmReceiver.publish(activity, run, "service-summary", report); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }, "fixture-service-alarm").start();
    }

    private static PendingIntent operation(Context context, String run, String label, int value, int request, boolean once) {
        return PendingIntent.getService(context, request, intent(context, run, label, value),
                IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT | (once ? PendingIntent.FLAG_ONE_SHOT : 0));
    }
    private static Intent intent(Context context, String run, String label, int value) {
        return new Intent(context, ServiceAlarmProbe.class).setAction("fixture.alarm." + run + "." + label)
                .putExtra("run", run).putExtra("label", label).putExtra("value", value);
    }
    private static void schedule(AlarmManager alarms, PendingIntent operation, long delay) {
        alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, operation);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
