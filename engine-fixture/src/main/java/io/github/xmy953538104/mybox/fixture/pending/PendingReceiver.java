package io.github.xmy953538104.mybox.fixture.pending;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Ordinary manifest receiver; its delayed completion must precede PendingIntent.OnFinished. */
public final class PendingReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String runId = UUID.fromString(intent.getStringExtra("runId")).toString();
        PendingResult pending = goAsync();
        if (pending == null) throw new IllegalStateException("Manifest receiver has no real PendingResult");
        Context application = context.getApplicationContext();
        new Thread(() -> {
            try {
                Thread.sleep(150);
                JSONObject report = new JSONObject();
                report.put("runId", runId);
                report.put("package", application.getPackageName());
                report.put("class", getClass().getName());
                report.put("pid", android.os.Process.myPid());
                report.put("value", intent.getIntExtra("value", -1));
                report.put("status", intent.getIntExtra("status", -1));
                report.put("async", true);
                publish(application, "pending-manifest-" + runId + ".json", report);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            finally { pending.finish(); }
        }, "fixture-manifest-receiver").start();
    }

    static void publish(Context context, String name, JSONObject report) throws Exception {
        File temporary = new File(context.getFilesDir(), name + "." + UUID.randomUUID() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(report.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(new File(context.getFilesDir(), name))) throw new java.io.IOException("Cannot publish receiver result");
    }
}
