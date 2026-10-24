package io.github.xmy953538104.mybox.fixture.providers;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Ordinary Provider APIs: the high-initOrder provider needs a later provider during onCreate. */
public final class ProviderProbe {
    private static final StringBuilder EVENTS = new StringBuilder();
    private static int dependentConstructed, dependentCreated, settingsConstructed, settingsCreated;
    private static String firstInstance, secondInstance;
    private static boolean dependencyReady, applicationSawReady;

    public static final class Dependent extends Base {
        public Dependent() { dependentConstructed++; }
        @Override public boolean onCreate() {
            dependentCreated++;
            EVENTS.append("dependent.enter\n");
            require(settingsCreated == 0, "Settings was created before the dependency probe");
            Bundle first = ProviderProbe.query(getContext(), "settings");
            Bundle second = ProviderProbe.query(getContext(), "settings_alias");
            firstInstance = first.getString("instance");
            secondInstance = second.getString("instance");
            require(firstInstance != null && firstInstance.equals(secondInstance), "Aliases resolved different providers");
            require(first.getInt("value") == 42 && first.getBoolean("ready"), "Dependency returned before onCreate");
            require(first.getInt("pid") == Process.myPid(), "Dependency ran in another process");
            dependencyReady = true;
            EVENTS.append("dependent.exit\n");
            return true;
        }
    }

    public static final class Settings extends Base {
        private final String instance = UUID.randomUUID().toString();
        private boolean ready;
        public Settings() { settingsConstructed++; }
        @Override public boolean onCreate() {
            settingsCreated++;
            EVENTS.append("settings.create\n");
            ready = true;
            return true;
        }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            require("identity".equals(method), "Unexpected probe method");
            require(ready, "Provider was exposed before initialization");
            Bundle result = new Bundle();
            result.putString("instance", instance);
            result.putBoolean("ready", ready);
            result.putInt("value", 42);
            result.putInt("pid", Process.myPid());
            return result;
        }
    }

    public abstract static class Base extends ContentProvider {
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }

    public static void applicationCreated(Context context) {
        // Other fixture processes use the same Application but do not declare these providers.
        if (dependentCreated == 0) return;
        EVENTS.append("application.create\n");
        applicationSawReady = dependencyReady && settingsCreated == 1 && settingsConstructed == 1;
        new Thread(() -> {
            JSONObject report = new JSONObject();
            try {
                Bundle background = query(context, "settings_alias");
                require(firstInstance.equals(background.getString("instance")), "Background lookup changed instance");
                report.put("package", context.getPackageName()).put("pid", Process.myPid());
                report.put("firstInstance", firstInstance).put("secondInstance", secondInstance)
                        .put("backgroundInstance", background.getString("instance"));
                report.put("applicationSawReady", applicationSawReady).put("dependencyReady", dependencyReady);
                report.put("dependentConstructed", dependentConstructed).put("dependentCreated", dependentCreated);
                report.put("settingsConstructed", settingsConstructed).put("settingsCreated", settingsCreated);
                report.put("events", EVENTS.toString());
            } catch (Throwable failure) {
                try { report.put("failure", android.util.Log.getStackTraceString(failure)); }
                catch (Exception ignored) { }
            }
            try {
                File temporary = new File(context.getFilesDir(), "local-providers.json.tmp");
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(report.toString().getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                if (!temporary.renameTo(new File(context.getFilesDir(), "local-providers.json"))) {
                    throw new java.io.IOException("Cannot publish Provider initialization report");
                }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }, "fixture-provider-reacquire").start();
    }

    private static Bundle query(Context context, String suffix) {
        Bundle result = context.getContentResolver().call(Uri.parse("content://" + context.getPackageName()
                + ".local." + suffix), "identity", null, null);
        if (result == null) throw new AssertionError("Local Provider returned no result");
        return result;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private ProviderProbe() { }
}
