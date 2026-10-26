package io.github.xmy953538104.mybox.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual OS entry point. Its finish is tied to receiver completion, never queue acceptance. */
public final class OsPendingReceiver extends BroadcastReceiver {
    private static final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), runnable -> new Thread(runnable, "MyliteOsAlarm"));
    private static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            runnable -> new Thread(runnable, "MyliteAlarmDeadline"));

    @Override public void onReceive(Context context, Intent intent) {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        boolean ordered = isOrderedBroadcast();
        PendingResult pending = goAsync();
        AtomicBoolean finished = new AtomicBoolean();
        AlarmDeliveryTicket[] ticket = new AlarmDeliveryTicket[1];
        android.os.Bundle failed = EngineProtocol.response();
        failed.putInt("result_code", ServerPendingIntents.CANCELED);
        java.util.function.Consumer<android.os.Bundle> finish = result -> {
            if (!finished.compareAndSet(false, true)) return;
            synchronized (ticket) { if (ticket[0] != null) ticket[0].close(); }
            try { if (ordered) pending.setResult(result.getInt("result_code"), result.getString("result_data"), result.getBundle("result_extras")); }
            finally { pending.finish(); }
        };
        java.util.concurrent.ScheduledFuture<?> timeout = watchdog.schedule(() -> {
            android.util.Log.w("MyliteAlarm", "OS delivery deadline expired; blocked Binder work may still be running");
            finish.accept(failed);
        }, 8_000, TimeUnit.MILLISECONDS);
        try {
            workers.execute(() -> {
                try {
                    if (finished.get()) return;
                    ServerAlarms alarms = EngineServerProvider.osAlarms();
                    OsPendingStore.Snapshot snapshot = alarms.verify(intent);
                    AlarmDeliveryTicket accepted = new AlarmDeliveryTicket(snapshot.creator, deadline);
                    synchronized (ticket) {
                        ticket[0] = accepted;
                        if (finished.get()) { accepted.close(); return; }
                    }
                    android.os.Bundle result = alarms.deliver(snapshot, accepted, ordered, pending.getResultCode());
                    accepted.requireActive();
                    finish.accept(result);
                } catch (Exception failure) {
                    android.util.Log.w("MyliteAlarm", "OS guest receiver failed: " + failure.getClass().getSimpleName());
                    finish.accept(failed);
                } finally { timeout.cancel(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            timeout.cancel(false); finish.accept(failed);
        }
    }
}
