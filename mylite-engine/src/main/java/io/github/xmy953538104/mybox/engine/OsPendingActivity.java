package io.github.xmy953538104.mybox.engine;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Real Activity entry point; a notification click never passes through a broadcast trampoline. */
public final class OsPendingActivity extends Activity {
    private static final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> new Thread(runnable, "MylitePendingActivity"));

    @Override public void onCreate(Bundle state) {
        super.onCreate(null);
        Intent delivery = getIntent() == null ? null : new Intent(getIntent());
        finish();
        try {
            workers.execute(() -> {
                try { EngineServerProvider.deliverPendingActivity(delivery); }
                catch (Exception failure) {
                    android.util.Log.e("MylitePendingActivity", "Activity capability delivery failed", failure);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            android.util.Log.e("MylitePendingActivity", "Activity capability queue is full", full);
        }
    }
}
