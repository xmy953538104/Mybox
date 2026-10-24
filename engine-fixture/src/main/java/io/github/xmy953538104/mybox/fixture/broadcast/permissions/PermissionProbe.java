package io.github.xmy953538104.mybox.fixture.broadcast.permissions;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Only public Context/BroadcastReceiver APIs; the independent receiver APK owns its code. */
public final class PermissionProbe {
    private static final String TARGET = "io.github.xmy953538104.mybox.broadcasttarget";
    private static final String RECEIVER = TARGET + ".EventReceiver";
    private static final String ACTION = TARGET + ".EVENT";
    private static final String REQUIRED = "io.github.xmy953538104.mybox.fixture.permission.BROADCAST";
    private static final String NORMAL = "io.github.xmy953538104.mybox.fixture.permission.NORMAL_BROADCAST";

    public static void run(Activity activity) {
        try {
            File request = new File(activity.getFilesDir(), "broadcast-permission-run");
            String run = new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8);
            if (!request.delete()) throw new java.io.IOException("Cannot consume permission probe request");
            new Thread(() -> execute(activity, run), "broadcast-permissions").start();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static void execute(Activity activity, String run) {
        JSONObject report = new JSONObject();
        try {
            Bundle received = ordered(activity, new Intent(ACTION).setPackage(TARGET + ".same"), REQUIRED);
            delivered(activity, received, TARGET + ".same", RECEIVER);
            report.put("sameSignerBothDirections", true);
            report.put("recipientPid", received.getInt("pid"));
            report.put("senderUid", received.getInt("sender_uid"));

            denied(activity, explicit("different", "$Open"), REQUIRED);
            delivered(activity, ordered(activity, explicit("different", "$Open"), NORMAL), TARGET + ".different", RECEIVER + "$Open");
            report.put("actualDifferentSignerDeniedButNormalGranted", true);

            denied(activity, explicit("unrequested", "$Open"), REQUIRED);
            delivered(activity, ordered(activity, explicit("unrequested", "$Open"), NORMAL), TARGET + ".unrequested", RECEIVER + "$Open");
            report.put("unrequestedPermissionDeniedButNormalGranted", true);

            denied(activity, explicit("same", "$MissingSender"), REQUIRED);
            report.put("receiverPermissionConstrainsSender", true);
            denied(activity, explicit("same", "$Private"), REQUIRED);
            denied(activity, explicit("same", "$Disabled"), REQUIRED);
            report.put("privateAndDisabledNotDelivered", true);
            denied(activity, new Intent(ACTION + ".NO_MATCH").setPackage(TARGET + ".same"), REQUIRED);
            denied(activity, new Intent(ACTION).setPackage(TARGET + ".absent"), REQUIRED);
            denied(activity, new Intent().setComponent(new ComponentName(TARGET + ".absent", RECEIVER)), REQUIRED);
            report.put("absentAndNoMatchAcceptedWithoutDelivery", true);

            CountDownLatch unordered = new CountDownLatch(1);
            Bundle[] actual = new Bundle[1];
            Binder observer = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
                    actual[0] = data.readBundle(getClass().getClassLoader());
                    reply.writeNoException(); unordered.countDown(); return true;
                }
            };
            Intent plain = new Intent(ACTION).setPackage(TARGET + ".same");
            Bundle extra = new Bundle(); extra.putBinder("observer", observer); plain.putExtras(extra);
            activity.sendBroadcast(plain, REQUIRED);
            require(unordered.await(12, TimeUnit.SECONDS), "Unordered protected broadcast was not received");
            require(TARGET.concat(".same").equals(actual[0].getString("package")) && actual[0].getBoolean("async"), "Incorrect unordered receiver");
            report.put("unorderedActualReceipt", true);

            boolean spoofDenied = false;
            try {
                activity.sendBroadcast(new Intent("com.google.android.gms.auth.GOOGLE_ACCOUNT_CHANGE").setPackage("com.android.vending"),
                        "com.google.android.gms.auth.permission.GOOGLE_ACCOUNT_CHANGE");
            } catch (RuntimeException expected) { spoofDenied = true; }
            require(spoofDenied, "A regular guest acquired the pinned Google event exception");
            report.put("googleEventSpoofRejected", true);

            generationBoundary(activity, run);
            report.put("queuedOldGenerationNotDelivered", true);
        } catch (Throwable failure) {
            try { report.put("failure", failure.getClass().getName() + ": " + failure.getMessage()); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        try {
            report.put("run", run);
            File temporary = new File(activity.getFilesDir(), "broadcast-permission-result-" + run + ".tmp");
            Files.write(temporary.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), new File(activity.getFilesDir(), "broadcast-permission-result-" + run + ".json").toPath());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static void generationBoundary(Activity activity, String run) throws Exception {
        String action = ACTION + ".HOLD";
        CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        BroadcastReceiver blocker = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                PendingResult pending = goAsync();
                entered.countDown();
                new Thread(() -> {
                    try {
                        File release = new File(activity.getFilesDir(), "broadcast-permission-release-" + run);
                        long deadline = android.os.SystemClock.elapsedRealtime() + 8_000;
                        while (!release.isFile() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
                        require(release.isFile(), "Test did not complete the package update before release");
                    } catch (Throwable problem) { failure[0] = problem; }
                    finally { pending.finish(); released.countDown(); }
                }, "permission-generation-blocker").start();
            }
        };
        activity.registerReceiver(blocker, new IntentFilter(action));
        try {
            activity.sendBroadcast(new Intent(action).setPackage(activity.getPackageName()));
            require(entered.await(8, TimeUnit.SECONDS), "Broadcast queue barrier did not execute");
            Result next = new Result();
            activity.sendOrderedBroadcast(new Intent(ACTION).setPackage(TARGET + ".same"), REQUIRED,
                    next, null, 17, "not-delivered", new Bundle());
            Files.write(new File(activity.getFilesDir(), "broadcast-permission-queued-" + run).toPath(), new byte[]{1});
            Bundle result = next.await();
            require(released.await(1, TimeUnit.SECONDS), "Generation barrier did not finish");
            if (failure[0] != null) throw new IllegalStateException("Generation barrier failed", failure[0]);
            notDelivered(result);
        } finally { activity.unregisterReceiver(blocker); }
    }

    private static Intent explicit(String variant, String suffix) {
        return new Intent(ACTION).setComponent(new ComponentName(TARGET + "." + variant, RECEIVER + suffix));
    }

    private static Bundle ordered(Activity activity, Intent intent, String permission) throws Exception {
        Result result = new Result();
        activity.sendOrderedBroadcast(intent, permission, result, null, 17, "not-delivered", new Bundle());
        return result.await();
    }

    private static void denied(Activity activity, Intent intent, String permission) throws Exception {
        notDelivered(ordered(activity, intent, permission));
    }

    private static void notDelivered(Bundle result) {
        require(result.getInt("code") == 17 && "not-delivered".equals(result.getString("data"))
                && !result.containsKey("receiver"), "An excluded receiver executed: " + result);
    }

    private static void delivered(Activity activity, Bundle result, String name, String receiver) throws Exception {
        require(result.getInt("code") == 18 && "received".equals(result.getString("data")) && result.getBoolean("async"), "Receiver completion missing: " + result);
        require(name.equals(result.getString("package")) && receiver.equals(result.getString("receiver"))
                && result.getInt("pid") != android.os.Process.myPid(), "Wrong receiver identity: " + result);
        if (android.os.Build.VERSION.SDK_INT >= 34) require(activity.getPackageName().equals(result.getString("sender_package"))
                && activity.getPackageManager().getPackageUid(activity.getPackageName(), 0) == result.getInt("sender_uid"), "Wrong sender identity: " + result);
    }

    private static final class Result extends BroadcastReceiver {
        final CountDownLatch done = new CountDownLatch(1);
        Bundle result;
        @Override public void onReceive(Context context, Intent intent) {
            result = new Bundle(getResultExtras(true)); result.putInt("code", getResultCode()); result.putString("data", getResultData()); done.countDown();
        }
        Bundle await() throws Exception { require(done.await(15, TimeUnit.SECONDS), "Protected broadcast final callback timed out"); return result; }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private PermissionProbe() { }
}
