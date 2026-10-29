package io.github.xmy953538104.mybox.installtarget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** Cold manifest delivery observes the APK version already published by the installer. */
public final class PackageReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean mine = Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction());
        if (!mine && (intent.getData() == null || !context.getPackageName().equals(intent.getData().getSchemeSpecificPart()))) return;
        try {
            int version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
            JSONObject report = new JSONObject(); report.put("action", intent.getAction()); report.put("package", context.getPackageName());
            report.put("codeVersion", BuildConfig.VERSION_CODE); report.put("publishedVersion", version);
            report.put("source", context.getApplicationInfo().sourceDir);
            report.put("pid", android.os.Process.myPid()); report.put("replacing", intent.getBooleanExtra(Intent.EXTRA_REPLACING, false));
            report.put("dataPresent", intent.getData() != null);
            String name = "package-event-" + version + "-" + intent.getAction().substring(intent.getAction().lastIndexOf('.') + 1) + ".json";
            File target = new File(context.getFilesDir(), name), pending = new File(context.getFilesDir(), name + ".tmp");
            Files.write(pending.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { throw new IllegalStateException("Package publication delivery failed", failure); }
    }
}
