package io.github.xmy953538104.mybox.fixture.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** Ordinary APK receiver. Reports actual onReceive/goAsync completion and never calls engine APIs. */
public final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String run = intent.getStringExtra("run"), label = intent.getStringExtra("label");
        int value = intent.getIntExtra("value", -1);
        try {
            JSONObject report = new JSONObject().put("pid", Process.myPid()).put("package", context.getPackageName())
                    .put("run", run).put("label", label).put("value", value).put("ordered", isOrderedBroadcast())
                    .put("hasAlarmCount", intent.hasExtra(Intent.EXTRA_ALARM_COUNT)).put("enteredAt", SystemClock.elapsedRealtime());
            File count = file(context, run, label + "-count");
            int previous = count.isFile() ? Integer.parseInt(new String(Files.readAllBytes(count.toPath()), StandardCharsets.UTF_8)) : 0;
            Files.write(count.toPath(), Integer.toString(previous + 1).getBytes(StandardCharsets.UTF_8));
            publish(context, run, label + "-entered-" + value, report);
            if (intent.getBooleanExtra("throw", false)) throw new IllegalStateException("Expected receiver failure");
            long block = intent.getLongExtra("blockMain", 0);
            if (block != 0) Thread.sleep(block);
            PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    if (intent.getBooleanExtra("hold", false)) waitFile(file(context, run, label + "-release-" + value), 6_000);
                    long delay = intent.getLongExtra("asyncDelay", 0);
                    if (delay != 0) Thread.sleep(delay);
                    report.put("finishedAt", SystemClock.elapsedRealtime());
                    publish(context, run, label + "-done-" + value, report);
                    if (report.optBoolean("ordered")) pending.setResult(83, "actual-receiver", new android.os.Bundle());
                    pending.finish();
                } catch (Exception failure) {
                    try { publish(context, run, label + "-failure", new JSONObject().put("failure", failure.getClass().getName())); }
                    catch (Exception ignored) { }
                    pending.finish();
                }
            }, "fixture-alarm-async").start();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    static File file(Context context, String run, String name) { return new File(context.getFilesDir(), "alarm-" + run + "-" + name); }
    static void publish(Context context, String run, String name, JSONObject value) throws Exception {
        File target = file(context, run, name), temporary = new File(target.getPath() + ".tmp");
        Files.write(temporary.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    static JSONObject read(File file) throws Exception { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
    static void waitFile(File file, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!file.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
        if (!file.isFile()) throw new AssertionError("Alarm fixture marker missing: " + file.getName());
    }
}
