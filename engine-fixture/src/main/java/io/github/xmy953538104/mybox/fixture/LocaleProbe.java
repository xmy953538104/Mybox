package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Uses the actual public LocaleManager and observes real Activity resource configuration. */
public final class LocaleProbe extends Activity {
    private volatile int changes;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        boolean reset = getIntent().getBooleanExtra("reset", false);
        LocaleList created = getResources().getConfiguration().getLocales();
        new Thread(() -> {
            JSONObject report = new JSONObject();
            try {
                LocaleManager manager = getSystemService(LocaleManager.class);
                LocaleList french = LocaleList.forLanguageTags("fr-FR");
                if (reset) {
                    require(french.equals(manager.getApplicationLocales()), "Preference did not survive guest process death");
                    require(french.equals(created), "Saved language was not applied before Activity.onCreate");
                }
                LocaleList selected = reset ? LocaleList.getEmptyLocaleList() : french;
                manager.setApplicationLocales(selected);
                require(selected.equals(manager.getApplicationLocales()), "Actual locale service did not retain selection");
                LocaleList effective = reset ? Resources.getSystem().getConfiguration().getLocales() : french;
                long deadline = SystemClock.elapsedRealtime() + 10000;
                while ((!effective.equals(getResources().getConfiguration().getLocales()) || changes == 0)
                        && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
                require(effective.equals(getResources().getConfiguration().getLocales()), "Activity resources did not change");
                require(effective.equals(getApplication().getResources().getConfiguration().getLocales()), "Application resources did not change");
                require(changes > 0, "Real Activity configuration callback was not delivered");
                report.put("pid", android.os.Process.myPid()); report.put("resources", true); report.put("callback", true);
                report.put("restoredBeforeCreate", reset); report.put("cleared", reset);
            } catch (Throwable failure) { try { report.put("failure", failure.toString()); } catch (Exception ignored) { } }
            try {
                File temporary = new File(getFilesDir(), "locale-probe.tmp");
                Files.write(temporary.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(temporary.toPath(), new File(getFilesDir(), "locale-" + (reset ? "reset" : "set") + ".json").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }, "locale-fixture").start();
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); changes++; }
    private static void require(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
