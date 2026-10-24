package io.github.xmy953538104.mybox.fixture.broadcast;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class BroadcastProbe {
    private static final String ACTION = "io.github.xmy953538104.mybox.fixture.ORDERED";

    public static void run(Activity activity) {
        File request = new File(activity.getFilesDir(), "broadcast-run");
        if (!request.isFile()) return;
        final String run;
        try {
            run = new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8);
            if (!request.delete()) throw new java.io.IOException("Cannot consume broadcast request");
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        new Thread(() -> execute(activity, run), "broadcast-probe").start();
    }

    private static void execute(Activity activity, String run) {
        JSONObject report = new JSONObject();
        BroadcastReceiver high = receiver("H", true), low = receiver("L", false);
        boolean highRegistered = false, lowRegistered = false;
        try {
            IntentFilter first = new IntentFilter(ACTION); first.setPriority(10);
            IntentFilter last = new IntentFilter(ACTION); last.setPriority(-5);
            activity.registerReceiver(high, first); highRegistered = true;
            activity.registerReceiver(low, last); lowRegistered = true;
            Intent broadcast = new Intent(ACTION).setPackage(activity.getPackageName());
            Bundle normal = ordered(activity, broadcast, 7);
            require(normal.getInt("code") == 10 && "HML".equals(normal.getString("data"))
                    && "HML".equals(normal.getString("chain")), "ordered result chain " + normal);
            require(normal.getInt("manifest_pid") != android.os.Process.myPid(), "manifest must execute in worker");
            report.put("priorityAndAsyncResults", true);
            Bundle aborted = ordered(activity, new Intent(broadcast).putExtra("abort", true), 7);
            require(aborted.getInt("code") == 8 && "H".equals(aborted.getString("chain")), "abort/final result " + aborted);
            report.put("abortAndFinalCallback", true);
            Bundle noAbort = ordered(activity, new Intent(broadcast).putExtra("abort", true)
                    .addFlags(Intent.FLAG_RECEIVER_NO_ABORT), 7);
            require(noAbort.getInt("code") == 10 && "HML".equals(noAbort.getString("chain")), "NO_ABORT " + noAbort);
            report.put("noAbortFlag", true);
            Bundle registeredOnly = ordered(activity, new Intent(broadcast).addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY), 7);
            require(registeredOnly.getInt("code") == 9 && "HL".equals(registeredOnly.getString("chain")), "registered-only " + registeredOnly);
            report.put("registeredOnly", true);
            Intent explicit = new Intent().setComponent(new ComponentName(activity, OrderedManifestReceiver.class));
            Bundle direct = ordered(activity, explicit, 2);
            require(direct.getInt("code") == 3 && "M".equals(direct.getString("chain")), "explicit receiver " + direct);
            report.put("explicitWithoutAction", true);
            Bundle absent = ordered(activity, new Intent(ACTION + ".NONE").setPackage(activity.getPackageName()), 4);
            require(absent.getInt("code") == 4 && "".equals(absent.getString("chain")), "empty delivery final callback " + absent);
            report.put("emptyFinalCallback", true);
            activity.sendBroadcast(new Intent(explicit).putExtra("run", run));
            File unordered = new File(activity.getFilesDir(), "broadcast-unordered-" + run);
            long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while (!unordered.isFile() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
            require(unordered.isFile(), "unordered explicit delivery");
            report.put("unorderedManifest", true);
            boolean rejected = false;
            try { activity.sendOrderedBroadcast(new Intent(explicit), "fixture.permission.UNSUPPORTED"); }
            catch (RuntimeException expected) { rejected = true; }
            require(rejected, "unsupported permission list was accepted");
            report.put("permissionRejected", true);
        } catch (Throwable failure) {
            try { report.put("failure", failure.getClass().getName() + ": " + failure.getMessage()); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        } finally {
            if (highRegistered) activity.unregisterReceiver(high);
            if (lowRegistered) activity.unregisterReceiver(low);
        }
        try {
            report.put("run", run);
            File temporary = new File(activity.getFilesDir(), "broadcast-result-" + run + ".tmp");
            Files.write(temporary.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), new File(activity.getFilesDir(), "broadcast-result-" + run + ".json").toPath());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static BroadcastReceiver receiver(String mark, boolean mayAbort) {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                require(isOrderedBroadcast(), "dynamic receiver lost ordered state");
                setResultCode(getResultCode() + 1);
                setResultData((getResultData() == null ? "" : getResultData()) + mark);
                Bundle extras = getResultExtras(true);
                extras.putString("chain", extras.getString("chain", "") + mark);
                if (mayAbort && intent.getBooleanExtra("abort", false)) abortBroadcast();
            }
        };
    }

    private static Bundle ordered(Activity context, Intent intent, int code) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Bundle[] answer = new Bundle[1];
        Bundle initial = new Bundle(); initial.putString("chain", "");
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent delivered) {
                Bundle result = getResultExtras(true);
                answer[0] = new Bundle(result);
                answer[0].putInt("code", getResultCode());
                answer[0].putString("data", getResultData());
                done.countDown();
            }
        }, null, code, "", initial);
        require(done.await(15, TimeUnit.SECONDS), "ordered final callback timed out");
        return answer[0];
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private BroadcastProbe() { }
}
