// SPDX-License-Identifier: Apache-2.0
package io.github.xmy953538104.mybox.probe;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Uses the real framework LocaleManager API and translated APK resources inside the guest. */
public final class LocaleProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONObject report = new JSONObject();
    private final String instanceId = UUID.randomUUID().toString();
    private int configurationCallbacks;
    private String stage;
    private LocaleManager manager;
    private LocaleList initialSystem;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        stage = getIntent().getStringExtra("localeStage");
        if (stage == null) stage = "set";
        try {
            manager = getSystemService(LocaleManager.class);
            initialSystem = manager.getSystemLocales();
            report.put("pid", android.os.Process.myPid());
            report.put("instanceId", instanceId);
            report.put("stage", stage);
            if ("set".equals(stage)) {
                // Start the provider process first: it must observe a subsequent preference change.
                provider();
                checkOwnerBoundary();
                manager.setApplicationLocales(LocaleList.forLanguageTags("fr-FR"));
            } else if ("clear".equals(stage)) {
                manager.setApplicationLocales(LocaleList.getEmptyLocaleList());
            } else if (!"restore".equals(stage)) {
                throw new IllegalArgumentException("Unknown locale probe stage");
            }
            handler.post(() -> awaitResources(SystemClock.elapsedRealtime() + 10000));
        } catch (Throwable failure) { finishReport(failure); }
    }

    private Bundle provider() {
        return getContentResolver().call(Uri.parse("content://" + getPackageName() + ".locales"), "locales", null, null);
    }

    private void checkOwnerBoundary() throws Exception {
        Field field = LocaleManager.class.getDeclaredField("mService");
        field.setAccessible(true);
        Object service = field.get(manager);
        int user = (Integer) android.content.Context.class.getMethod("getUserId").invoke(this);
        Method getter = service.getClass().getMethod("getApplicationLocales", String.class, int.class);
        requireDenied(getter, service, "android", user);
        requireDenied(getter, service, getPackageName(), user + 1);
        Method setter = null;
        for (Method method : service.getClass().getMethods()) {
            if ("setApplicationLocales".equals(method.getName())) setter = method;
        }
        if (setter == null) throw new AssertionError("No platform locale setter");
        Object[] args = setter.getParameterTypes().length == 3
                ? new Object[]{"android", user, LocaleList.forLanguageTags("fr-FR")}
                : new Object[]{"android", user, LocaleList.forLanguageTags("fr-FR"), false};
        requireDenied(setter, service, args);
        args[0] = getPackageName();
        args[1] = user + 1;
        requireDenied(setter, service, args);
        report.put("foreignPackageAndUserDenied", true);
    }

    private static void requireDenied(Method method, Object service, Object... args) throws Exception {
        try { method.invoke(service, args); }
        catch (InvocationTargetException expected) {
            if (expected.getCause() instanceof SecurityException) return;
            throw expected;
        }
        throw new AssertionError("Foreign locale access was accepted");
    }

    private void awaitResources(long deadline) {
        try {
            boolean clear = "clear".equals(stage);
            String expectedTags = clear ? "" : "fr-FR";
            LocaleList expectedResource = clear ? initialSystem : LocaleList.forLanguageTags("fr-FR");
            Bundle remote = provider();
            boolean local = manager.getApplicationLocales().toLanguageTags().equals(expectedTags)
                    && !getResources().getConfiguration().getLocales().isEmpty()
                    && getResources().getConfiguration().getLocales().get(0).equals(expectedResource.get(0));
            boolean translated = clear || "french-resource".equals(getString(R.string.locale_probe));
            boolean crossProcess = remote != null && remote.getInt("pid") != android.os.Process.myPid()
                    && expectedTags.equals(remote.getString("preferences"))
                    && expectedResource.get(0).toLanguageTag().equals(remote.getString("firstResourceLocale"))
                    && (clear || "french-resource".equals(remote.getString("translated")));
            boolean callback = !"set".equals(stage) || configurationCallbacks > 0
                    || "fr-FR".equals(initialSystem.get(0).toLanguageTag());
            boolean providerRestoredBeforeCreate = !"restore".equals(stage)
                    || (remote != null && "fr-FR".equals(remote.getString("initialResourceLocale")));
            if ((!local || !translated || !crossProcess || !callback || !providerRestoredBeforeCreate)
                    && SystemClock.elapsedRealtime() < deadline) {
                handler.postDelayed(() -> awaitResources(deadline), 100);
                return;
            }
            report.put("getterAndResources", local);
            report.put("translatedResource", translated);
            report.put("crossProcess", crossProcess);
            report.put("configurationCallback", callback);
            report.put("providerRestoredBeforeCreate", providerRestoredBeforeCreate);
            report.put("systemUnchanged", initialSystem.equals(manager.getSystemLocales()));
            report.put("passed", local && translated && crossProcess && callback && providerRestoredBeforeCreate
                    && initialSystem.equals(manager.getSystemLocales()));
            finishReport(null);
        } catch (Throwable failure) { finishReport(failure); }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        configurationCallbacks++;
    }

    private void finishReport(Throwable failure) {
        try {
            if (failure != null) {
                report.put("passed", false);
                report.put("failure", failure.getClass().getName() + ": " + failure.getMessage());
            }
            AtomicFile file = new AtomicFile(new File(getFilesDir(), "locale-" + stage + ".json"));
            FileOutputStream output = file.startWrite();
            try {
                output.write(report.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
                file.finishWrite(output);
            } catch (Exception writeFailure) {
                file.failWrite(output);
                throw writeFailure;
            }
            TextView text = new TextView(this);
            text.setText(report.toString());
            setContentView(text);
        } catch (Exception fatal) { throw new IllegalStateException(fatal); }
    }
}
