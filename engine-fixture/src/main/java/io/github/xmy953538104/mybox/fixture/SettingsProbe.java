package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** Real Settings APIs in two guest processes, without touching the tablet's system settings. */
public final class SettingsProbe {
    public static void run(Activity activity) {
        new Thread(() -> {
            JSONObject report = new JSONObject(); String run = "invalid";
            try {
                run = java.util.UUID.fromString(new String(Files.readAllBytes(new File(activity.getFilesDir(), "settings-run").toPath()), StandardCharsets.UTF_8)).toString();
                report.put("run", run).put("pid", android.os.Process.myPid());
                String name = "mylite_settings_" + run;
                for (String domain : new String[]{"secure", "global"}) {
                    require(read(activity, domain, name) == null, "Fresh virtual key is not empty");
                    for (String value : new String[]{"first", "second", null}) {
                        boolean accepted = domain.equals("secure") ? Settings.Secure.putString(activity.getContentResolver(), name, value)
                                : Settings.Global.putString(activity.getContentResolver(), name, value);
                        require(accepted, "Virtual write failed");
                        require(java.util.Objects.equals(value, read(activity, domain, name)), "Local Settings cache returned stale data");
                        Bundle request = new Bundle(); request.putString("domain", domain);
                        Bundle remote = activity.getContentResolver().call(Uri.parse("content://" + activity.getPackageName() + ".settings"), "read", name, request);
                        require(remote != null && remote.getInt("pid") != android.os.Process.myPid(), "Settings read did not cross processes");
                        require(java.util.Objects.equals(value, remote.getString("value")), "Cross-process Settings cache returned stale data");
                        report.put("readerPid", remote.getInt("pid"));
                    }
                }
                Bundle otherUser = new Bundle(); otherUser.putInt("_user", 10); otherUser.putString("value", "foreign");
                try { activity.getContentResolver().call(Uri.parse("content://settings"), "PUT_secure", name, otherUser); throw new AssertionError("Foreign settings user accepted"); }
                catch (SecurityException expected) { }
                report.put("crossProcessReadWriteDelete", true).put("foreignUserRejected", true);
            } catch (Throwable failure) {
                try { report.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage()); } catch (Exception ignored) { }
            }
            try {
                File target = new File(activity.getFilesDir(), "settings-result-" + run + ".json"), pending = new File(target.getPath() + ".tmp");
                Files.write(pending.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception failure) { throw new IllegalStateException("Cannot publish Settings result", failure); }
        }, "fixture-settings").start();
    }

    private static String read(android.content.Context context, String domain, String name) {
        return domain.equals("secure") ? Settings.Secure.getString(context.getContentResolver(), name) : Settings.Global.getString(context.getContentResolver(), name);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    public static final class Reader extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String name, Bundle extras) {
            if (!"read".equals(method) || name == null || !name.startsWith("mylite_settings_") || extras == null
                    || (!"secure".equals(extras.getString("domain")) && !"global".equals(extras.getString("domain"))))
                throw new SecurityException("Invalid fixture settings request");
            Bundle result = new Bundle(); result.putString("value", read(getContext(), extras.getString("domain"), name));
            result.putInt("pid", android.os.Process.myPid()); return result;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    }
    private SettingsProbe() { }
}
