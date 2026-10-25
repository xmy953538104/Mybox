package io.github.xmy953538104.mybox.fixture.pending;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Posts through ordinary guest APIs; the host must touch the real Android notification. */
public final class NotificationPendingProbe {
    private NotificationPendingProbe() { }
    public static void run(Activity activity) {
        try {
            String run = new String(Files.readAllBytes(new File(activity.getFilesDir(), "notification-pending-run").toPath()), StandardCharsets.UTF_8);
            File broadcast = new File(activity.getFilesDir(), "notification-broadcast-request-" + run + ".json");
            if (broadcast.isFile()) {
                NotificationBroadcastProbe.post(activity, run, new JSONObject(new String(Files.readAllBytes(broadcast.toPath()), StandardCharsets.UTF_8)));
                return;
            }
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            String channel = "interactive-" + run;
            manager.createNotificationChannel(new NotificationChannel(channel, "Interactive fixture", NotificationManager.IMPORTANCE_DEFAULT));
            Intent target = new Intent(activity, Target.class).putExtra("run", run);
            PendingIntent content = PendingIntent.getActivity(activity, 7940, new Intent(target).setAction("content").putExtra("kind", "content"),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent action = PendingIntent.getActivity(activity, 7941, new Intent(target).setAction("action").putExtra("kind", "action"),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(activity, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Mylite notice " + run).setContentText("Open the guest from Android")
                    .setContentIntent(content).setAutoCancel(false)
                    .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_view, "Mylite action", action).build())
                    .setStyle(new Notification.BigTextStyle().bigText("Real notification content and action capabilities"))
                    .build();
            manager.notify("interactive", 7940, notification);
            publish(new File(activity.getFilesDir(), "notification-pending-posted-" + run + ".json"), new JSONObject().put("posted", true));
        } catch (Exception failure) { throw new IllegalStateException("Cannot post interactive guest notification", failure); }
    }
    public static final class Target extends Activity {
        @Override public void onCreate(Bundle state) { super.onCreate(state); receive(getIntent()); }
        @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); receive(intent); }
        private void receive(Intent intent) {
            if (intent.hasExtra("notification_broadcast_operation")) NotificationBroadcastProbe.transferred(this, intent);
            else record(intent);
        }
        private void record(Intent intent) {
            try {
                String run = intent.getStringExtra("run"), kind = intent.getStringExtra("kind");
                publish(new File(getFilesDir(), "notification-pending-event-" + run + "-" + kind + ".json"),
                        new JSONObject().put("kind", kind).put("package", getPackageName()).put("pid", android.os.Process.myPid()));
                android.widget.TextView view = new android.widget.TextView(this);
                view.setText("Mylite notification target " + kind); setContentView(view);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }
    private static void publish(File file, JSONObject value) throws Exception {
        File temporary = new File(file.getPath() + ".tmp");
        Files.write(temporary.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
        if (!temporary.renameTo(file)) throw new java.io.IOException("Cannot publish notification result");
    }
}
