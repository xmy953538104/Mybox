package io.github.xmy953538104.mybox.fixture.pending;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.json.JSONObject;

/** Real public notifications and the existing manifest receiver; never sends its own action PI. */
public final class NotificationBroadcastProbe {
    private static final int NOTICE = 7950, WORK = 7951;
    private static final String ACTION = "fixture.notification.broadcast.";
    static void post(Activity activity, String run, JSONObject request) {
        JSONObject result = new JSONObject();
        try {
            UUID.fromString(run);
            result.put("run", run); result.put("pid", android.os.Process.myPid());
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            String channel = "broadcast-" + run;
            manager.createNotificationChannel(new NotificationChannel(channel, "Broadcast fixture", NotificationManager.IMPORTANCE_DEFAULT));
            Intent base = NotificationMetadata.intent(new Intent(activity, PendingReceiver.class).putExtra("run", run).putExtra("value", 17), run);
            int flags = PendingIntent.FLAG_IMMUTABLE | (request.optBoolean("oneShot") ? PendingIntent.FLAG_ONE_SHOT : 0);
            PendingIntent action = PendingIntent.getBroadcast(activity, NOTICE,
                    new Intent(base).setAction(ACTION + run + ".cancel").putExtra("kind", "cancel"), flags);
            PendingIntent deleted = PendingIntent.getBroadcast(activity, NOTICE + 1,
                    new Intent(base).setAction(ACTION + run + ".delete").putExtra("kind", "delete"), PendingIntent.FLAG_IMMUTABLE);
            manager.notify("broadcast-work-" + run, WORK, builder(activity, channel, "Mylite work " + run).setOngoing(true).build());
            postNotice(activity, run, action, deleted, false);
            if (request.optBoolean("revoke")) action.cancel();
            // These candidate types must remain explicit unsupported, without posting a fake receiver.
            PendingIntent mutable = PendingIntent.getBroadcast(activity, NOTICE + 2,
                    new Intent(base).setAction(ACTION + run + ".mutable"), PendingIntent.FLAG_MUTABLE);
            try { reject(manager, builder(activity, channel, "Rejected mutable " + run).setDeleteIntent(mutable).build(), NOTICE + 2); result.put("mutableRejected", true); }
            finally { mutable.cancel(); }
            Intent service = new Intent(activity, ServicePendingProbe.PlainService.class).setAction(ACTION + run);
            PendingIntent plain = PendingIntent.getService(activity, NOTICE + 3, service, PendingIntent.FLAG_IMMUTABLE);
            try { reject(manager, builder(activity, channel, "Rejected service " + run).setDeleteIntent(plain).build(), NOTICE + 3); result.put("serviceRejected", true); }
            finally { plain.cancel(); }
            PendingIntent foreground = PendingIntent.getForegroundService(activity, NOTICE + 4, service, PendingIntent.FLAG_IMMUTABLE);
            try { reject(manager, builder(activity, channel, "Rejected foreground " + run).setDeleteIntent(foreground).build(), NOTICE + 4); result.put("foregroundRejected", true); }
            finally { foreground.cancel(); }
            result.put("posted", true); result.put("revoked", request.optBoolean("revoke"));
        } catch (Throwable failure) { failure(result, failure); }
        done(activity, file(activity, run, "posted"), result);
    }
    private static void reject(NotificationManager manager, Notification value, int id) {
        try { manager.notify(id, value); throw new AssertionError("Unsupported notification capability posted"); }
        catch (UnsupportedOperationException expected) { }
    }
    static void transferred(Activity activity, Intent input) {
        String run = UUID.fromString(input.getStringExtra("run")).toString(); JSONObject result = new JSONObject();
        try {
            PendingIntent action = input.getParcelableExtra("action"), deleted = input.getParcelableExtra("deleted");
            String operation = input.getStringExtra("notification_broadcast_operation");
            if ("repost".equals(operation)) {
                // info and normalize both have to recover the original physical broadcast Binder on a cold server.
                if (action == null || !activity.getPackageName().equals(action.getCreatorPackage()))
                    throw new AssertionError("Transferred capability lost its virtual creator");
                postNotice(activity, run, action, deleted, true); result.put("reposted", true);
            } else if ("cancel".equals(operation)) { action.cancel(); result.put("canceled", true); }
            else throw new IllegalArgumentException("Unknown notification transfer operation");
        } catch (Throwable failure) { failure(result, failure); }
        done(activity, file(activity, run, "transferred"), result);
    }
    private static void postNotice(Activity activity, String run, PendingIntent action, PendingIntent deleted, boolean restored) {
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        String channel = "broadcast-" + run;
        manager.createNotificationChannel(new NotificationChannel(channel, "Broadcast fixture", NotificationManager.IMPORTANCE_DEFAULT));
        manager.notify("broadcast-notice-" + run, NOTICE, builder(activity, channel,
                (restored ? "Mylite restored " : "Mylite broadcast ") + run).setDeleteIntent(deleted)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Mylite cancel " + run, action).build())
                .setStyle(new Notification.BigTextStyle().bigText("Touch cancel, then dismiss this real notification"))
                .build());
    }
    private static Notification.Builder builder(Context context, String channel, String title) {
        return new Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
                .setContentText("Actual OS broadcast notification").setAutoCancel(false);
    }
    static boolean receive(Context context, Intent intent) {
        if (intent.getAction() == null || !intent.getAction().startsWith(ACTION)) return false;
        try {
            String run = UUID.fromString(intent.getStringExtra("run")).toString(), kind = intent.getStringExtra("kind");
            NotificationMetadata.requireIntent(intent, run);
            if (!("cancel".equals(kind) || "delete".equals(kind)) || !intent.getAction().equals(ACTION + run + "." + kind))
                throw new IllegalArgumentException("Unexpected notification receiver payload");
            if (kind.equals("cancel")) context.getSystemService(NotificationManager.class).cancel("broadcast-work-" + run, WORK);
            File target = file(context, run, kind); int count = 0;
            if (target.isFile()) count = new JSONObject(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)).getInt("count");
            write(target, new JSONObject().put("run", run).put("kind", kind).put("value", intent.getIntExtra("value", -1))
                    .put("count", count + 1).put("package", context.getPackageName()).put("pid", android.os.Process.myPid()));
            return true;
        } catch (Exception failure) { throw new IllegalStateException("Actual notification broadcast failed", failure); }
    }
    private static File file(Context context, String run, String suffix) { return new File(context.getFilesDir(), "notification-broadcast-" + run + "-" + suffix + ".json"); }
    private static void failure(JSONObject result, Throwable failure) {
        try { result.put("failure", failure.getClass().getName() + ": " + failure.getMessage()); }
        catch (Exception ignored) { }
    }
    private static void done(Activity activity, File file, JSONObject result) {
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityDestroyed(Activity subject) {
                if (subject != activity) return;
                try { result.put("destroyed", true); write(file, result); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                finally { activity.getApplication().unregisterActivityLifecycleCallbacks(this); }
            }
            @Override public void onActivityCreated(Activity subject, Bundle state) { }
            @Override public void onActivityStarted(Activity subject) { }
            @Override public void onActivityResumed(Activity subject) { }
            @Override public void onActivityPaused(Activity subject) { }
            @Override public void onActivityStopped(Activity subject) { }
            @Override public void onActivitySaveInstanceState(Activity subject, Bundle state) { }
        };
        activity.getApplication().registerActivityLifecycleCallbacks(callbacks);
        try { result.put("destroyed", false); write(file, result); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        finally { activity.finish(); }
    }
    private static void write(File target, JSONObject report) throws Exception {
        File pending = new File(target.getPath() + ".tmp"); Files.write(pending.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private NotificationBroadcastProbe() { }
}
