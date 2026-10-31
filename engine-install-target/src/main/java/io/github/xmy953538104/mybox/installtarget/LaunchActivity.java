package io.github.xmy953538104.mybox.installtarget;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** Installed APK code reporting real window focus and its compiled flavor, never a host-side stand-in. */
public final class LaunchActivity extends Activity {
    private boolean published;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView text = new TextView(this); text.setText("Installed target version " + BuildConfig.VERSION_CODE);
        setContentView(text);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || published) return;
        published = true;
        try {
            String nonce = new String(Files.readAllBytes(new File(getFilesDir(), "installer-launch-request").toPath()), StandardCharsets.UTF_8);
            PackageManager manager = getPackageManager(); ComponentName launcher = getComponentName();
            int previous = manager.getComponentEnabledSetting(launcher);
            boolean disabled;
            try {
                manager.setComponentEnabledSetting(launcher, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                disabled = manager.getLaunchIntentForPackage(getPackageName()) == null;
            } finally { manager.setComponentEnabledSetting(launcher, previous, PackageManager.DONT_KILL_APP); }
            JSONObject report = new JSONObject(); report.put("nonce", nonce); report.put("codeVersion", BuildConfig.VERSION_CODE);
            report.put("package", getPackageName()); report.put("pid", android.os.Process.myPid()); report.put("focused", hasWindowFocus());
            report.put("launcherComponent", getComponentName().getClassName());
            report.put("codeClass", getClass().getName());
            report.put("intentComponent", getIntent().getComponent().getClassName());
            report.put("uid", getApplicationInfo().uid); report.put("source", getApplicationInfo().sourceDir);
            report.put("processUid", android.os.Process.myUid());
            report.put("packageInfoUid", manager.getApplicationInfo(getPackageName(), 0).uid);
            report.put("packageUid", manager.getPackageUid(getPackageName(), 0));
            report.put("taskId", getTaskId());
            report.put("launcherDisabledAndRestored", disabled && manager.getLaunchIntentForPackage(getPackageName()) != null
                    && manager.getComponentEnabledSetting(launcher) == previous);
            finish(); report.put("finishRequested", isFinishing());
            File result = new File(getFilesDir(), "installer-launched.json"), pending = new File(getFilesDir(), "installer-launched.json.tmp");
            Files.write(pending.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(pending.toPath(), result.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { throw new IllegalStateException("Installed target launch probe failed", failure); }
        finally { if (!isFinishing()) finish(); }
    }
}
