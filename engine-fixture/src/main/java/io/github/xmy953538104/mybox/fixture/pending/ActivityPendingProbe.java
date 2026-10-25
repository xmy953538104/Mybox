package io.github.xmy953538104.mybox.fixture.pending;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Exercises ordinary Android APIs; the fixture has no dependency on the container. */
public final class ActivityPendingProbe {
    private ActivityPendingProbe() { }

    public static void run(Activity activity) {
        new Thread(() -> {
            JSONObject report = new JSONObject();
            String run = null;
            try {
                run = new String(Files.readAllBytes(new File(activity.getFilesDir(), "activity-pending-run").toPath()), StandardCharsets.UTF_8);
                Intent base = new Intent(activity, Target.class).setAction(run).putExtra("run", run).putExtra("value", "original");
                PendingIntent mutable = PendingIntent.getActivity(activity, 7910, base, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
                require(!event(activity, run, 1).exists(), "Creation launched the target");
                require(mutable.isActivity(), "Activity sender type was lost");
                String creator = mutable.getCreatorPackage(); int creatorUid = mutable.getCreatorUid();
                require(activity.getPackageName().equals(creator), "Virtual creator identity was lost");
                report.put("creationDidNotLaunch", true);
                mutable.send(activity, 0, new Intent().putExtra("value", "cannot-overwrite-base").putExtra("fill", "filled"));
                JSONObject first = await(activity, run, 1);
                require("original".equals(first.getString("value")), "Fill-in overwrote an existing base extra");
                require("filled".equals(first.getString("fill")), "A new mutable extra was lost");
                require("onCreate".equals(first.getString("callback")), "First dispatch was not onCreate");
                require(activity.getWindowManager().getDefaultDisplay().getDisplayId() == first.getInt("displayId"),
                        "Activity send lost its caller display");
                report.put("callerDisplay", true);
                report.put("mutable", true);
                Bundle wrongType = new Bundle(); wrongType.putString("android.activity.callerDisplayId", "0");
                rejectOptions(mutable, wrongType, null);
                Bundle negative = new Bundle(); negative.putInt("android.activity.callerDisplayId", -1);
                rejectOptions(mutable, negative, null);
                Bundle absent = new Bundle(); absent.putInt("android.activity.callerDisplayId", Integer.MAX_VALUE);
                rejectOptions(mutable, absent, null);
                android.hardware.display.DisplayManager displays = activity.getSystemService(android.hardware.display.DisplayManager.class);
                if (displays != null) for (android.view.Display display : displays.getDisplays()) {
                    if (!display.isValid() || display.getDisplayId() == android.view.Display.DEFAULT_DISPLAY) continue;
                    Bundle secondary = new Bundle(); secondary.putInt("android.activity.callerDisplayId", display.getDisplayId());
                    rejectOptions(mutable, secondary, null);
                }
                Bundle unknown = new Bundle(); unknown.putBoolean("unsupported.option", true);
                rejectOptions(mutable, unknown, null);
                Bundle privileged = new Bundle(); privileged.putInt("android.activity.launchTaskId", 12345);
                rejectOptions(mutable, privileged, null);
                Bundle baseline = android.app.ActivityOptions.makeBasic().toBundle();
                for (String key : baseline.keySet()) {
                    if ("android.activity.callerDisplayId".equals(key)) continue;
                    Object value = baseline.get(key);
                    Bundle changed = new Bundle(baseline);
                    if (value instanceof Integer) changed.putInt(key, ((Integer) value) ^ 1);
                    else if (value instanceof Boolean) changed.putBoolean(key, !((Boolean) value));
                    else changed.putString(key, String.valueOf(value) + "-changed");
                    rejectOptions(mutable, changed, null);
                }
                rejectOptions(mutable, null, "android.permission.INTERNET");
                report.put("sendOptionsRejected", true);
                PendingIntent updated = PendingIntent.getActivity(activity, 7910, new Intent(base).putExtra("value", "updated"),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                require(mutable.equals(updated), "UPDATE_CURRENT changed the virtual capability");
                updated.send();
                JSONObject second = await(activity, run, 2);
                require("updated".equals(second.getString("value")), "Updated extras were not delivered");
                require("onNewIntent".equals(second.getString("callback")), "singleTop was not reused");
                require(first.getString("instance").equals(second.getString("instance")), "singleTop instance changed");
                report.put("updateAndNewIntent", true);
                PendingIntent immutable = PendingIntent.getActivity(activity, 7911, base,
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                immutable.send(activity, 0, new Intent().putExtra("value", "forged").putExtra("fill", "forged"));
                JSONObject third = await(activity, run, 3);
                require("original".equals(third.getString("value")) && !third.getBoolean("hasFill"), "Immutable fill-in changed payload");
                immutable.cancel(); report.put("immutable", true);
                PendingIntent one = PendingIntent.getActivity(activity, 7912, base,
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
                one.send(); await(activity, run, 4);
                try { one.send(); throw new AssertionError("One-shot sent twice"); }
                catch (PendingIntent.CanceledException expected) { }
                require(creator.equals(one.getCreatorPackage()) && creatorUid == one.getCreatorUid() && one.isActivity(),
                        "Consumed one-shot lost its creator/type metadata");
                report.put("oneShot", true);
                mutable.cancel();
                try { updated.send(); throw new AssertionError("Canceled real sender survived"); }
                catch (PendingIntent.CanceledException expected) { }
                require(creator.equals(updated.getCreatorPackage()) && creatorUid == updated.getCreatorUid() && updated.isActivity(),
                        "Canceled capability lost its creator/type metadata");
                try {
                    updated.getIntentSender().sendIntent(activity, 0, null, null, null);
                    throw new AssertionError("Canceled IntentSender did not reject the send");
                } catch (android.content.IntentSender.SendIntentException expected) { }
                android.os.Parcel parcel = android.os.Parcel.obtain();
                try {
                    updated.writeToParcel(parcel, 0); parcel.setDataPosition(0);
                    PendingIntent transferred = PendingIntent.CREATOR.createFromParcel(parcel);
                    require(creator.equals(transferred.getCreatorPackage()), "Parcel transfer changed canceled creator metadata");
                    try { transferred.send(); throw new AssertionError("Transferred canceled capability sent"); }
                    catch (PendingIntent.CanceledException expected) { }
                } finally { parcel.recycle(); }
                report.put("canceledCapabilityMetadata", true);
                require(PendingIntent.getActivity(activity, 7910, base, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_MUTABLE) == null,
                        "NO_CREATE resurrected cancellation");
                report.put("cancelAndNoCreate", true);
                PendingIntent acceptedThenCanceled = PendingIntent.getActivity(activity, 7940, base,
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                acceptedThenCanceled.send(); acceptedThenCanceled.cancel();
                require("original".equals(await(activity, run, 5).getString("value")), "Cancellation lost an OS-accepted dispatch");
                report.put("cancelInflight", true);
                PendingIntent oldOne = PendingIntent.getActivity(activity, 7941, new Intent(base).putExtra("value", "old-cap"),
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
                oldOne.send();
                PendingIntent replacement = PendingIntent.getActivity(activity, 7941, new Intent(base).putExtra("value", "new-cap"),
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
                require(!replacement.equals(oldOne), "Replacement retained the consumed OS sender");
                require("old-cap".equals(await(activity, run, 6).getString("value")), "Key replacement erased the captured old cap");
                replacement.send();
                require("new-cap".equals(await(activity, run, 7).getString("value")), "Replacement cap did not dispatch");
                report.put("oneShotReplacementInflight", true);
                PendingIntent oldRevision = PendingIntent.getActivity(activity, 7942, new Intent(base).putExtra("value", "before-update"),
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                oldRevision.send();
                PendingIntent newRevision = PendingIntent.getActivity(activity, 7942, new Intent(base).putExtra("value", "after-update"),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                require(oldRevision.equals(newRevision), "Update replaced the OS sender");
                require("before-update".equals(await(activity, run, 8).getString("value")), "Update rewrote an OS-captured revision");
                newRevision.send();
                require("after-update".equals(await(activity, run, 9).getString("value")), "Updated revision was not dispatched");
                newRevision.cancel(); report.put("updateInflight", true);
                // The host will send this exact OS record after terminating the engine server.
                PendingIntent.getActivity(activity, 7913, new Intent(base).putExtra("value", "cold"),
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                PendingIntent.getActivity(activity, 7914, new Intent(base).putExtra("value", "cold-once"),
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
                report.put("coldReady", true);
            } catch (Throwable failure) {
                try { report.put("failure", failure.toString()); } catch (Exception ignored) { }
            }
            try {
                if (run != null) publish(new File(activity.getFilesDir(), "activity-pending-result-" + run + ".json"), report);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }, "ActivityPendingProbe").start();
    }

    public static final class Target extends Activity {
        private final String instance = java.util.UUID.randomUUID().toString();
        private int sequence;
        @Override public void onCreate(Bundle state) { super.onCreate(state); record(getIntent(), "onCreate"); }
        @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); record(intent, "onNewIntent"); }
        private void record(Intent intent, String callback) {
            try {
                String run = intent.getStringExtra("run");
                // Cold process recovery starts a new instance; use an independent durable event name.
                String value = intent.getStringExtra("value");
                String suffix = value != null && value.startsWith("cold") ? value : String.valueOf(++sequence);
                JSONObject event = new JSONObject().put("instance", instance).put("callback", callback)
                        .put("value", intent.getStringExtra("value")).put("pid", android.os.Process.myPid())
                        .put("hasFill", intent.hasExtra("fill")).put("fill", intent.getStringExtra("fill"))
                        .put("displayId", getWindowManager().getDefaultDisplay().getDisplayId());
                publish(new File(getFilesDir(), "activity-pending-event-" + run + "-" + suffix + ".json"), event);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }

    private static File event(Activity activity, String run, int sequence) {
        return new File(activity.getFilesDir(), "activity-pending-event-" + run + "-" + sequence + ".json");
    }
    private static void rejectOptions(PendingIntent sender, Bundle options, String permission) throws Exception {
        try {
            // A null context/intent avoids framework preprocessing; the raw options reach the server boundary.
            sender.send(null, 0, null, null, null, permission, options);
            throw new AssertionError("Unsupported send options were accepted: " + options);
        } catch (RuntimeException rejected) {
            String message = String.valueOf(rejected.getMessage());
            require(message.contains("options") || message.contains("callerDisplayId") || message.contains("display is unavailable")
                            || message.contains("non-default caller display"),
                    "Unexpected send-option rejection: " + rejected);
        }
    }
    private static JSONObject await(Activity activity, String run, int sequence) throws Exception {
        File event = event(activity, run, sequence);
        long deadline = android.os.SystemClock.elapsedRealtime() + 15_000;
        while (!event.isFile() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
        require(event.isFile(), "No actual target callback " + sequence);
        return new JSONObject(new String(Files.readAllBytes(event.toPath()), StandardCharsets.UTF_8));
    }
    private static void publish(File file, JSONObject result) throws Exception {
        File temporary = new File(file.getPath() + ".tmp");
        Files.write(temporary.toPath(), result.toString().getBytes(StandardCharsets.UTF_8));
        if (!temporary.renameTo(file)) throw new java.io.IOException("Cannot publish Activity event");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
