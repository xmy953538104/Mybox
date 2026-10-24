package io.github.xmy953538104.mybox.fixture.alarm;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class AlarmProbe {
    private static final int IMMUTABLE = PendingIntent.FLAG_IMMUTABLE;
    private static final int CANCELED = -96;
    public static void run(Activity activity, boolean recover) {
        new Thread(() -> {
            String run = "invalid";
            JSONObject report = new JSONObject();
            try {
                run = UUID.fromString(new String(Files.readAllBytes(new File(activity.getFilesDir(), "alarm-run").toPath()), StandardCharsets.UTF_8)).toString();
                report.put("run", run);
                if (recover) recover(activity, run, report); else warm(activity, run, report);
            } catch (Throwable failure) {
                try { report.put("failure", failure.getClass().getName() + ": " + failure.getMessage()); }
                catch (Exception ignored) { }
            }
            try { AlarmReceiver.publish(activity, run, recover ? "recovery" : "warm", report); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }, "fixture-alarm-probe").start();
    }

    private static void warm(Activity activity, String run, JSONObject report) throws Exception {
        AlarmManager alarms = activity.getSystemService(AlarmManager.class);
        AlarmManager second = activity.createPackageContext(activity.getPackageName(), 0).getSystemService(AlarmManager.class);
        boolean exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms();
        require(exact == (Build.VERSION.SDK_INT < 31 || second.canScheduleExactAlarms()), "Context alarm permission views differ");
        report.put("exactAllowed", exact);
        List<PendingIntent> owned = new ArrayList<>();
        try {
            Intent original = intent(activity, run, "update", 1).putExtra("hold", true);
            PendingIntent operation = PendingIntent.getBroadcast(activity, 8101, original, IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
            owned.add(operation);
            alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 80_000, 2_000, operation);
            require(operation.isImmutable() && operation.isBroadcast() && activity.getPackageName().equals(operation.getCreatorPackage()), "Virtual creator/type changed");
            AlarmReceiver.publish(activity, run, "physical", new JSONObject().put("pid", android.os.Process.myPid()));
            AlarmReceiver.waitFile(AlarmReceiver.file(activity, run, "physical-ack"), 15_000);
            alarms.cancel(operation); // Alarm cancellation must leave the same PI sendable.
            Completion first = new Completion();
            send(activity, operation, new Intent().putExtra("value", 999), first);
            AlarmReceiver.waitFile(AlarmReceiver.file(activity, run, "update-entered-1"), 10_000);
            require(first.callbacks.get() == 0, "OnFinished preceded goAsync finish");
            PendingIntent updated = PendingIntent.getBroadcast(activity, 8101, new Intent(original).putExtra("value", 2).putExtra("hold", false), IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            require(operation.equals(updated), "UPDATE_CURRENT changed the capability");
            Files.write(AlarmReceiver.file(activity, run, "update-release-1").toPath(), new byte[]{1});
            first.await();
            require(first.code == 83 && first.value == 1, "OS accepted snapshot was replaced or fill-in bypassed immutable");
            require(first.at >= AlarmReceiver.read(AlarmReceiver.file(activity, run, "update-done-1")).getLong("finishedAt"), "Callback was synthetic queue acceptance");
            Completion next = new Completion(); send(activity, operation, null, next); next.await();
            require(next.code == 83 && next.value == 2, "Updated OS snapshot was not delivered");
            report.put("realCompletion", true).put("updateSnapshot", true).put("immutable", true).put("alarmCancelKeepsPi", true);
            PendingIntent replacement = PendingIntent.getBroadcast(activity, 8101,
                    new Intent(original).putExtra("value", 8).putExtra("hold", false), IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
            owned.add(replacement); require(!replacement.equals(operation), "CANCEL_CURRENT reused the virtual capability");
            physicalize(alarms, replacement);
            Completion replaced = new Completion(); send(activity, replacement, null, replaced); replaced.await();
            require(replaced.code == 83 && replaced.value == 8, "Replacement physical capability did not deliver");
            try { send(activity, operation, null, new Completion()); throw new AssertionError("Old CANCEL_CURRENT reference remained live"); }
            catch (PendingIntent.CanceledException expected) { }
            report.put("cancelCurrent", true);

            PendingIntent lowBit = PendingIntent.getBroadcast(activity, 8101, original, IMMUTABLE | Intent.FILL_IN_ACTION);
            owned.add(lowBit); require(!lowBit.equals(operation), "Low fill-in flag changed virtual Key identity");
            report.put("keyFlags", true);
            Intent inFlight = intent(activity, run, "cancel-inflight", 3).putExtra("hold", true);
            PendingIntent cancel = PendingIntent.getBroadcast(activity, 8102, inFlight, IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
            Cancellation cancelNotice = cancellation(cancel);
            owned.add(cancel); physicalize(alarms, cancel);
            Completion inflight = new Completion(); send(activity, cancel, null, inflight);
            AlarmReceiver.waitFile(AlarmReceiver.file(activity, run, "cancel-inflight-entered-3"), 10_000);
            cancel.cancel();
            cancelNotice.await();
            Files.write(AlarmReceiver.file(activity, run, "cancel-inflight-release-3").toPath(), new byte[]{1});
            inflight.await(); require(inflight.code == 83, "Cancel revoked an already accepted OS snapshot");
            Completion rejected = new Completion();
            try { send(activity, cancel, null, rejected); throw new AssertionError("Canceled PI sent"); }
            catch (PendingIntent.CanceledException expected) { }
            require(rejected.callbacks.get() == 0, "Canceled send invented an OS completion");
            require(PendingIntent.getBroadcast(activity, 8102, inFlight, IMMUTABLE | PendingIntent.FLAG_NO_CREATE) == null, "Canceled record resurrected");
            report.put("cancelInflight", true);

            PendingIntent once = PendingIntent.getBroadcast(activity, 8103, intent(activity, run, "once", 4), IMMUTABLE | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT);
            Cancellation oneNotice = cancellation(once);
            owned.add(once);
            if (exact) alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, once);
            else physicalize(alarms, once);
            Completion one = new Completion(); send(activity, once, null, one); one.await();
            oneNotice.await();
            require(one.code == 83, "One-shot cancel notification swallowed the accepted receive");
            try { send(activity, once, null, new Completion()); throw new AssertionError("One-shot sent twice"); }
            catch (PendingIntent.CanceledException expected) { }
            require(PendingIntent.getBroadcast(activity, 8103, intent(activity, run, "once", 4), IMMUTABLE | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_NO_CREATE) == null, "One-shot NO_CREATE resurrected");

            // First receiver blocks its main thread. The second reaches its deadline while still queued.
            PendingIntent blocker = PendingIntent.getBroadcast(activity, 8104, intent(activity, run, "block", 5).putExtra("blockMain", 20_000L), IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
            PendingIntent late = PendingIntent.getBroadcast(activity, 8105, intent(activity, run, "late", 6), IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
            owned.add(blocker); owned.add(late); physicalize(alarms, blocker); physicalize(alarms, late);
            Completion blocked = new Completion(), delayed = new Completion();
            long started = SystemClock.elapsedRealtime(); send(activity, blocker, null, blocked);
            AlarmReceiver.waitFile(AlarmReceiver.file(activity, run, "block-entered-5"), 10_000);
            send(activity, late, null, delayed);
            blocked.await(); delayed.await();
            // Android may serialize the second physical broadcast behind the first one's finish.
            // Its own eight-second budget starts at onReceive, not at this guest's send call.
            require(blocked.code == CANCELED && delayed.code == CANCELED
                    && blocked.at - started < 10_000 && delayed.at - started < 18_000, "OS timeout did not bound completion");
            AlarmReceiver.waitFile(AlarmReceiver.file(activity, run, "block-done-5"), 6_000);
            Thread.sleep(250);
            require(!AlarmReceiver.file(activity, run, "late-entered-6").exists(), "Expired queued receiver executed late");
            require(blocked.callbacks.get() == 1 && delayed.callbacks.get() == 1, "OS result finished more than once");
            require("1".equals(new String(Files.readAllBytes(AlarmReceiver.file(activity, run, "once-count").toPath()), StandardCharsets.UTF_8)), "Alarm/direct-send one-shot race delivered twice");
            report.put("oneShot", true).put("deadline", true).put("lateAdmissionRejected", true);
            require(cancelNotice.calls.get() == 1 && oneNotice.calls.get() == 1, "Physical cancel listener was duplicated");
            report.put("physicalCancellation", true);

            PendingIntent broken = PendingIntent.getBroadcast(activity, 8106, intent(activity, run, "throw", 7).putExtra("throw", true), IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
            owned.add(broken); physicalize(alarms, broken);
            Completion failed = new Completion(); send(activity, broken, null, failed); failed.await();
            require(failed.code == CANCELED, "Receiver exception was reported as a successful OS delivery");
            report.put("receiverFailure", true);

            reject(() -> alarms.set(AlarmManager.ELAPSED_REALTIME, 1, PendingIntent.getBroadcast(activity, 8120, intent(activity, run, "mutable", 0), PendingIntent.FLAG_MUTABLE)));
            reject(() -> alarms.set(AlarmManager.ELAPSED_REALTIME, 1, PendingIntent.getBroadcast(activity, 8121, new Intent("fixture.alarm.dynamic"), IMMUTABLE)));
            reject(() -> alarms.set(AlarmManager.ELAPSED_REALTIME, 1, PendingIntent.getService(activity, 8122, intent(activity, run, "wrong-kind", 0), IMMUTABLE)));
            reject(() -> alarms.setRepeating(AlarmManager.ELAPSED_REALTIME, 1, 1000, replacement));
            reject(() -> alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000,
                    "unsupported-listener", () -> { throw new AssertionError("Unsupported listener executed"); }, new Handler(Looper.getMainLooper())));
            reject(() -> alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 1000, replacement), replacement));
            Bundle binder = new Bundle(); binder.putBinder("unsupported", new android.os.Binder());
            reject(() -> alarms.set(AlarmManager.ELAPSED_REALTIME, 1, PendingIntent.getBroadcast(activity, 8123, intent(activity, run, "binder", 0).putExtras(binder), IMMUTABLE)));
            report.put("unsupportedRejected", true);
            if (!exact) {
                try { alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, replacement); throw new AssertionError("Exact alarm denied by query but set succeeded"); }
                catch (SecurityException expected) { report.put("exactDenied", true); }
                throw new AssertionError("Real OS exact-alarm permission is required for the cold-delivery test; no permission was granted automatically");
            }
            PendingIntent cold = PendingIntent.getBroadcast(activity, 8190, intent(activity, run, "cold", 99), IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 12_000, cold);
            report.put("coldScheduled", true).put("mainPid", android.os.Process.myPid());
        } finally { for (PendingIntent pending : owned) pending.cancel(); }
    }

    private static void recover(Activity activity, String run, JSONObject report) throws Exception {
        PendingIntent restored = PendingIntent.getBroadcast(activity, 8190, intent(activity, run, "cold", 99), IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        require(restored != null, "Cold server did not restore the still-live OS record");
        Completion done = new Completion(); send(activity, restored, null, done); done.await();
        require(done.code == 83 && done.value == 99, "Restored capability did not really deliver");
        report.put("noCreateRestored", true).put("realRestoredSend", true);
        restored.cancel();
        require(PendingIntent.getBroadcast(activity, 8190, intent(activity, run, "cold", 99), IMMUTABLE | PendingIntent.FLAG_NO_CREATE) == null, "Cold restored cancellation recreated capability");
        report.put("cancelRestored", true);
        PendingIntent guestCold = PendingIntent.getBroadcast(activity, 8191, intent(activity, run, "guest-cold", 100), IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
        activity.getSystemService(AlarmManager.class).setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 12_000, guestCold);
        report.put("guestColdScheduled", true);
    }

    private static Intent intent(Context context, String run, String label, int value) {
        return new Intent(context, AlarmReceiver.class).setAction("fixture.alarm." + run + "." + label)
                .putExtra("run", run).putExtra("label", label).putExtra("value", value);
    }
    private static void physicalize(AlarmManager alarms, PendingIntent pending) {
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 80_000, pending); alarms.cancel(pending);
    }
    private static void send(Context context, PendingIntent pending, Intent fillIn, Completion result) throws Exception {
        pending.send(context, 17, fillIn, result, new Handler(Looper.getMainLooper()));
    }
    private static final class Completion implements PendingIntent.OnFinished {
        final CountDownLatch done = new CountDownLatch(1); final AtomicInteger callbacks = new AtomicInteger();
        volatile int code, value; volatile long at;
        @Override public void onSendFinished(PendingIntent pending, Intent intent, int code, String data, Bundle extras) {
            this.code = code; value = intent.getIntExtra("value", -1); at = SystemClock.elapsedRealtime(); callbacks.incrementAndGet(); done.countDown();
        }
        void await() throws Exception { require(done.await(10, TimeUnit.SECONDS), "Real OS completion did not arrive"); }
    }
    private static final class Cancellation {
        final CountDownLatch done = new CountDownLatch(1); final AtomicInteger calls = new AtomicInteger();
        void await() throws Exception { require(done.await(5, TimeUnit.SECONDS), "Physical cancellation did not notify its virtual listener"); }
    }
    private static Cancellation cancellation(PendingIntent pending) throws Exception {
        Cancellation observed = new Cancellation();
        Class<?> type = Class.forName("android.app.PendingIntent$CancelListener");
        Object listener = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            if (method.getName().equals("onCanceled")) { observed.calls.incrementAndGet(); observed.done.countDown(); return null; }
            if (method.getName().equals("equals")) return proxy == arguments[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            return "FixtureCancelListener";
        });
        java.lang.reflect.Method method = PendingIntent.class.getDeclaredMethod("addCancelListener", java.util.concurrent.Executor.class, type);
        method.setAccessible(true);
        require((Boolean) method.invoke(pending, (java.util.concurrent.Executor) Runnable::run, listener), "Fresh cancellation listener was rejected");
        return observed;
    }
    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Unsupported alarm escaped the virtual boundary");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private AlarmProbe() { }
}
