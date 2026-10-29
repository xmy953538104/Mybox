package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Ordinary framework operations; the host checks the real OS records independently. */
public final class ShortcutProbe {
    public static void run(Activity owner, String run) {
        new Thread(() -> {
            JSONObject report = new JSONObject();
            try {
                ShortcutManager manager = owner.getSystemService(ShortcutManager.class);
                android.os.Bundle connection = owner.getContentResolver().call(android.net.Uri.parse("content://io.github.xmy953538104.mybox.engine.server"), "connect", null, new Bundle());
                android.os.IBinder server = connection.getBinder("server");
                Bundle service = new Bundle(); service.putInt("protocol", 1); service.putString("service", "shortcuts");
                android.os.IBinder endpoint = exchange(server, "io.github.xmy953538104.mybox.engine.Server.v1", android.os.IBinder.FIRST_CALL_TRANSACTION + 7, service).getBinder("service");
                for (boolean foreignUser : new boolean[]{false, true}) {
                    Bundle forged = new Bundle(); forged.putInt("protocol", 1); forged.putInt("user", foreignUser ? 10 : 0);
                    forged.putString("package", foreignUser ? owner.getPackageName() : "com.android.settings"); forged.putString("operation", "removeAllDynamicShortcuts");
                    try { exchange(endpoint, "io.github.xmy953538104.mybox.engine.Shortcuts.v1", android.os.IBinder.FIRST_CALL_TRANSACTION, forged); throw new AssertionError("Foreign shortcut claim accepted"); }
                    catch (SecurityException expected) { }
                }
                Bundle publication = new Bundle(); publication.putInt("protocol", 1); publication.putBoolean("replacing", true);
                publication.putString("package", owner.getPackageName()); publication.putString("generation", new File(owner.getApplicationInfo().sourceDir).getParentFile().getName());
                Bundle denied = exchange(server, "io.github.xmy953538104.mybox.engine.Server.v1", android.os.IBinder.FIRST_CALL_TRANSACTION + 24, publication);
                require(SecurityException.class.getName().equals(denied.getString("error_type")), "Guest forged a package publication endpoint");
                report.put("protectedEndpointsRejected", true);
                manager.enableShortcuts(Collections.singletonList("missing." + run));
                require(manager.getManifestShortcuts().isEmpty(), "Unexpected fixture manifest shortcut");
                require(manager.getPinnedShortcuts().isEmpty(), "Unexpected fixture pinned shortcut");
                String first = "first." + run, second = "second." + run;
                ShortcutInfo one = entry(owner, first, run);
                require(manager.setDynamicShortcuts(Collections.singletonList(one)), "OS refused initial shortcut set");
                require(one.getPackage().equals(owner.getPackageName()) && one.getId().equals(first), "Publication mutated the caller's object");
                check(owner, manager.getDynamicShortcuts(), first, run);
                manager.disableShortcuts(Collections.singletonList(first), "Fixture disabled");
                require(manager.getDynamicShortcuts().isEmpty(), "Disabled non-pinned shortcut remained dynamic");
                manager.enableShortcuts(Collections.singletonList(first));
                require(manager.addDynamicShortcuts(Collections.singletonList(one)), "OS refused shortcut re-add");
                require(manager.setDynamicShortcuts(Collections.singletonList(entry(owner, second, run))), "OS refused replacement set");
                check(owner, manager.getDynamicShortcuts(), second, run);
                ShortcutInfo labelOnly = new ShortcutInfo.Builder(owner, second).setShortLabel("Updated shortcut").build();
                require(manager.updateShortcuts(Collections.singletonList(labelOnly)), "OS refused label update");
                List<ShortcutInfo> updated = manager.getDynamicShortcuts(); check(owner, updated, second, run);
                require("Updated shortcut".contentEquals(updated.get(0).getShortLabel()), "Partial update lost its label");
                if (android.os.Build.VERSION.SDK_INT >= 30) check(owner, manager.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC), second, run);
                manager.reportShortcutUsed(second);
                report.put("id", second); report.put("package", owner.getPackageName()); report.put("pid", android.os.Process.myPid());
                report.put("frameworkSequence", true); report.put("partialUpdatePreservedIntent", true);
            } catch (Throwable failure) { reportFailure(report, failure); }
            write(owner, "shortcut-" + run + "-ready.json", report);
        }, "FixtureShortcuts").start();
    }

    private static Bundle exchange(android.os.IBinder endpoint, String descriptor, int code, Bundle request) throws Exception {
        android.os.Parcel input = android.os.Parcel.obtain(), output = android.os.Parcel.obtain();
        try {
            input.writeInterfaceToken(descriptor); input.writeBundle(request);
            require(endpoint != null && endpoint.transact(code, input, output, 0), "Missing authenticated shortcut endpoint");
            output.readException(); Bundle result = output.readBundle(ShortcutProbe.class.getClassLoader());
            require(result != null && result.getInt("protocol") == 1, "Invalid shortcut protocol reply"); return result;
        } finally { input.recycle(); output.recycle(); }
    }

    private static ShortcutInfo entry(Activity owner, String id, String run) {
        return new ShortcutInfo.Builder(owner, id).setShortLabel("Fixture shortcut").setRank(0)
                .setActivity(new ComponentName(owner, FixtureActivity.class))
                .setIcon(Icon.createWithResource(owner, android.R.drawable.sym_def_app_icon))
                .setIntent(new Intent(owner, Target.class).setAction("fixture.shortcut.OPEN")
                        .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED).putExtra("run", run)).build();
    }
    private static void check(Activity owner, List<ShortcutInfo> values, String id, String run) {
        require(values.size() == 1, "Shortcut query leaked or lost records"); ShortcutInfo value = values.get(0);
        require(id.equals(value.getId()) && owner.getPackageName().equals(value.getPackage()), "Shortcut identity did not round trip");
        require(new ComponentName(owner, FixtureActivity.class).equals(value.getActivity()), "Shortcut Activity did not round trip");
        Intent[] intents = value.getIntents(); require(intents != null && intents.length == 1, "Shortcut Intent missing");
        require(new ComponentName(owner, Target.class).equals(intents[0].getComponent()) && run.equals(intents[0].getStringExtra("run")), "Shortcut launch data changed");
        require((intents[0].getFlags() & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0, "Shortcut publication changed the original Intent flags");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void reportFailure(JSONObject report, Throwable failure) {
        android.util.Log.e("FixtureShortcuts", "Shortcut probe failed", failure);
        try { report.put("failure", failure.getClass().getName()); } catch (Exception ignored) { throw new IllegalStateException(ignored); }
    }
    private static void write(Activity owner, String name, JSONObject report) {
        try {
            File temporary = new File(owner.getFilesDir(), name + ".tmp"), target = new File(owner.getFilesDir(), name);
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(report.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
            if (!temporary.renameTo(target)) throw new java.io.IOException("Cannot publish shortcut result");
        } catch (Exception failure) { throw new IllegalStateException("Cannot publish shortcut result", failure); }
    }
    public static final class Target extends Activity {
        private boolean reported;
        @Override public void onCreate(Bundle state) {
            super.onCreate(state); TextView text = new TextView(this); text.setText("Activated fixture shortcut"); setContentView(text);
        }
        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused); if (!focused || reported) return; reported = true;
            String run = getIntent().getStringExtra("run"); JSONObject report = new JSONObject();
            try {
                report.put("run", run); report.put("package", getPackageName()); report.put("pid", android.os.Process.myPid());
                report.put("focused", hasWindowFocus()); report.put("class", getClass().getName()); report.put("taskId", getTaskId());
                report.put("resetTaskFlagPresent", (getIntent().getFlags() & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0);
            }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            write(this, "shortcut-" + run + "-opened.json", report);
        }
    }
    private ShortcutProbe() { }
}
