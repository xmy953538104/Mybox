package io.github.xmy953538104.mybox.engine;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A real OS Service sender; it never substitutes a broadcast for Service admission. */
public final class OsPendingService extends Service {
    private static final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), runnable -> new Thread(runnable, "MyliteOsService"));
    private static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            runnable -> new Thread(runnable, "MyliteOsServiceDeadline"));
    private int inFlight, latestStart;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        synchronized (this) { inFlight++; latestStart = startId; }
        AtomicBoolean finished = new AtomicBoolean();
        AlarmDeliveryTicket[] ticket = new AlarmDeliveryTicket[1];
        Runnable finish = () -> {
            if (!finished.compareAndSet(false, true)) return;
            synchronized (ticket) { if (ticket[0] != null) ticket[0].close(); }
            int stop;
            synchronized (OsPendingService.this) { stop = --inFlight == 0 ? latestStart : -1; }
            if (stop >= 0) stopSelfResult(stop);
        };
        java.util.concurrent.ScheduledFuture<?> timeout = watchdog.schedule(() -> {
            android.util.Log.w("MyliteOsService", "OS Service delivery deadline expired; Binder work may still be running");
            finish.run();
        }, 8_000, TimeUnit.MILLISECONDS);
        try {
            workers.execute(() -> {
                try {
                    if (finished.get()) return;
                    ServerAlarms alarms = EngineServerProvider.osAlarms();
                    OsPendingStore.Snapshot snapshot = alarms.verifyService(intent);
                    AlarmDeliveryTicket accepted = new AlarmDeliveryTicket(snapshot.creator, deadline);
                    synchronized (ticket) {
                        ticket[0] = accepted;
                        if (finished.get()) { accepted.close(); return; }
                    }
                    alarms.deliverService(snapshot, accepted);
                    accepted.requireActive();
                } catch (Exception failure) {
                    android.util.Log.w("MyliteOsService", "OS guest Service admission failed: " + failure.getClass().getSimpleName());
                } finally { finish.run(); timeout.cancel(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            timeout.cancel(false); finish.run();
        }
        return START_NOT_STICKY;
    }
}
