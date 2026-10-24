package io.github.xmy953538104.mybox.fixture.pending;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Exercises public Android APIs only; no container-specific success bypass. */
public final class PendingProbe {
    private static final ExecutorService RUNS = Executors.newSingleThreadExecutor(r -> new Thread(r, "fixture-pending-probe"));

    public static void run(Activity activity, Runnable after) {
        final String runId;
        try {
            File request = new File(activity.getFilesDir(), "pending-run");
            if (request.isFile()) {
                runId = UUID.fromString(new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8)).toString();
                if (!request.delete()) throw new java.io.IOException("Cannot consume PendingIntent probe request");
            } else runId = UUID.randomUUID().toString();
        } catch (Exception failure) { throw new IllegalStateException("Cannot identify PendingIntent probe run", failure); }
        RUNS.execute(() -> {
            JSONObject result = new JSONObject();
            try { result.put("runId", runId); probe(activity, result, runId); }
            catch (Throwable failure) {
                try { result.put("failure", failure.getClass().getName() + ": " + failure.getMessage()); }
                catch (Exception ignored) { }
            }
            try {
                PendingReceiver.publish(activity, "pending-result-" + runId + ".json", result);
                PendingReceiver.publish(activity, "pending-result.json", result);
            }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            activity.runOnUiThread(after);
        });
    }

    private static void probe(Context context, JSONObject result, String runId) throws Exception {
        Handler main = new Handler(Looper.getMainLooper());
        String pkg = context.getPackageName();
        File manifestFile = new File(context.getFilesDir(), "pending-manifest-" + runId + ".json");
        Intent explicit = new Intent(context, PendingReceiver.class).setAction(pkg + ".PENDING_MANIFEST." + runId)
                .putExtra("runId", runId).putExtra("value", 7);
        PendingIntent once = PendingIntent.getBroadcast(context, 711, explicit,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
        require(pkg.equals(once.getCreatorPackage()), "Incorrect PendingIntent creator package");
        require(once.getCreatorUid() == context.getPackageManager().getPackageUid(pkg, 0), "Incorrect virtual creator UID");
        if (android.os.Build.VERSION.SDK_INT >= 31) require(once.isBroadcast() && !once.isActivity(), "Incorrect PendingIntent type");
        CountDownLatch manifestDone = new CountDownLatch(1);
        AtomicReference<String> manifestFailure = new AtomicReference<>();
        once.getIntentSender().sendIntent(context, 47, new Intent().putExtra("status", 73), (sender, intent, code, data, extras) -> {
            if (code != 47 || !manifestFile.isFile()) manifestFailure.set("OnFinished preceded manifest goAsync completion: " + code);
            manifestDone.countDown();
        }, main);
        require(manifestDone.await(15, TimeUnit.SECONDS), "Manifest PendingIntent did not finish");
        require(manifestFailure.get() == null, String.valueOf(manifestFailure.get()));
        JSONObject manifest = new JSONObject(new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8));
        require(runId.equals(manifest.getString("runId")), "Manifest completion belongs to another probe run");
        require(pkg.equals(manifest.getString("package")) && PendingReceiver.class.getName().equals(manifest.getString("class")), "Wrong manifest receiver");
        require(manifest.getInt("pid") != android.os.Process.myPid(), "Manifest receiver did not run in :worker");
        require(manifest.getInt("value") == 7 && manifest.getInt("status") == 73 && manifest.getBoolean("async"), "Manifest extras or goAsync failed");
        try { once.send(); throw new AssertionError("One-shot PendingIntent sent twice"); }
        catch (PendingIntent.CanceledException expected) { }
        result.put("manifestAsync", true);
        result.put("oneShot", true);
        result.put("creator", true);
        result.put("manifestPid", manifest.getInt("pid"));

        String action = pkg + ".PENDING_DYNAMIC." + runId;
        AtomicInteger receivedValue = new AtomicInteger(-1), receivedStatus = new AtomicInteger(-1), count = new AtomicInteger();
        AtomicBoolean asyncDone = new AtomicBoolean();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                PendingResult pending = goAsync();
                require(pending != null, "Registered receiver has no real PendingResult");
                new Thread(() -> {
                    try {
                        Thread.sleep(150);
                        receivedValue.set(intent.getIntExtra("value", -1));
                        receivedStatus.set(intent.getIntExtra("status", -1));
                        count.incrementAndGet();
                        asyncDone.set(true);
                    } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                    finally { pending.finish(); }
                }, "fixture-dynamic-receiver").start();
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, new IntentFilter(action));
        PendingIntent mutable = null, immutable = null;
        try {
            Intent base = new Intent(action).setPackage(pkg).putExtra("value", 1);
            mutable = PendingIntent.getBroadcast(context, 712, base, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
            PendingIntent updated = PendingIntent.getBroadcast(context, 712, new Intent(base).putExtra("value", 2),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            require(mutable.equals(updated), "FLAG_UPDATE_CURRENT changed the capability");
            PendingIntent found = PendingIntent.getBroadcast(context, 712, base, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_MUTABLE);
            require(mutable.equals(found), "FLAG_NO_CREATE lost the capability");
            sendAndAwait(context, mutable, new Intent().putExtra("status", 37), main, asyncDone);
            require(receivedValue.get() == 2 && receivedStatus.get() == 37 && count.get() == 1, "Updated or fill-in extras were not delivered");
            result.put("dynamicAsync", true);
            result.put("updateCurrent", true);
            mutable.cancel();
            require(PendingIntent.getBroadcast(context, 712, base, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_MUTABLE) == null, "Canceled capability remained active");
            try { mutable.send(); throw new AssertionError("Canceled PendingIntent sent"); }
            catch (PendingIntent.CanceledException expected) { }
            result.put("cancel", true);

            immutable = PendingIntent.getBroadcast(context, 713, new Intent(base).putExtra("value", 9),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            asyncDone.set(false);
            sendAndAwait(context, immutable, new Intent().putExtra("status", 88), main, asyncDone);
            require(receivedValue.get() == 9 && receivedStatus.get() == -1 && count.get() == 2, "Immutable PendingIntent accepted fill-in extras");
            result.put("immutable", true);

            PendingIntent scoped = PendingIntent.getBroadcast(context, 714, explicit, PendingIntent.FLAG_CANCEL_CURRENT
                    | PendingIntent.FLAG_MUTABLE | Intent.FILL_IN_COMPONENT);
            try {
                scoped.send(context, 0, new Intent().setComponent(new ComponentName("com.google.android.gms", "ForgedReceiver")));
                throw new AssertionError("Mutable capability escaped its creator package");
            } catch (RuntimeException rejected) {
                require(String.valueOf(rejected.getMessage()).contains("Cross-package"), "Unexpected scope rejection: " + rejected);
            } finally { scoped.cancel(); }
            result.put("scope", true);
            try {
                PendingIntent.getService(context, 715, explicit, PendingIntent.FLAG_IMMUTABLE);
                throw new AssertionError("Unsupported Service PendingIntent reached the physical system");
            } catch (RuntimeException rejected) {
                require(String.valueOf(rejected.getMessage()).contains("Only broadcast"), "Unexpected unsupported-type result: " + rejected);
            }
            result.put("unsupportedType", true);
        } finally {
            context.unregisterReceiver(receiver);
            if (mutable != null) mutable.cancel();
            if (immutable != null) immutable.cancel();
        }
    }

    private static void sendAndAwait(Context context, PendingIntent sender, Intent fillIn, Handler handler, AtomicBoolean asyncDone) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        sender.send(context, 19, fillIn, (pending, intent, code, data, extras) -> {
            if (!asyncDone.get() || code != 19) failure.set("OnFinished preceded registered goAsync completion: " + code);
            done.countDown();
        }, handler);
        require(done.await(15, TimeUnit.SECONDS), "Registered PendingIntent did not finish");
        require(failure.get() == null, String.valueOf(failure.get()));
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private PendingProbe() { }
}
