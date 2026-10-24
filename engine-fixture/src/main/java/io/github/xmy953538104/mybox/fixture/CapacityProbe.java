package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

public final class CapacityProbe extends Service {
    public static void run(Activity activity) {
        write(activity, "activity");
        activity.startService(new Intent(activity, CapacityProbe.class));
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        write(this, "service"); stopSelf(startId); return START_NOT_STICKY;
    }
    private static void write(Context context, String kind) {
        try {
            JSONObject result = new JSONObject().put("package", context.getPackageName()).put("pid", Process.myPid());
            File pending = new File(context.getFilesDir(), "capacity-" + kind + ".tmp");
            Files.write(pending.toPath(), result.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(pending.toPath(), new File(context.getFilesDir(), "capacity-" + kind + ".json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
