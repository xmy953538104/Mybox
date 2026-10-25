package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.SystemClock;
import android.telephony.SubscriptionManager;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** The test process stimulates the OS registry; this guest observes only real public callbacks. */
final class TelephonyRegistryProbe {
    static void run(Activity activity, String nonce) { new Session(activity, nonce).start(); }

    private static final class Session implements Application.ActivityLifecycleCallbacks {
        final Activity activity;
        final Handler handler;
        final SubscriptionManager manager;
        final String nonce;
        final File reportFile, commandFile;
        final JSONObject report = new JSONObject();
        final int[] callbacks = {0, 0};
        final boolean[] registered = {false, false};
        final SubscriptionManager.OnSubscriptionsChangedListener ordinary;
        final SubscriptionManager.OnOpportunisticSubscriptionsChangedListener opportunistic;
        final long deadline = SystemClock.elapsedRealtime() + 45000;
        String phase = "starting";
        boolean terminal, destroyed;
        Session(Activity activity, String nonce) {
            if (!java.util.UUID.fromString(nonce).toString().equals(nonce)) throw new IllegalArgumentException("Invalid registry fixture nonce");
            this.nonce = nonce;
            this.activity = activity; handler = new Handler(activity.getMainLooper());
            manager = activity.getSystemService(SubscriptionManager.class);
            String prefix = "telephony-registry-" + nonce;
            reportFile = new File(activity.getFilesDir(), prefix + "-result.json");
            commandFile = new File(activity.getFilesDir(), prefix + "-command");
            ordinary = new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override public void onSubscriptionsChanged() { if (!terminal) { callbacks[0]++; publish(); } }
            };
            opportunistic = new SubscriptionManager.OnOpportunisticSubscriptionsChangedListener() {
                @Override public void onOpportunisticSubscriptionsChanged() { if (!terminal) { callbacks[1]++; publish(); } }
            };
        }
        void start() {
            activity.getApplication().registerActivityLifecycleCallbacks(this);
            try {
                report.put("package", activity.getPackageName());
                Class<?> services = Class.forName("android.os.ServiceManager");
                IBinder service = (IBinder) services.getDeclaredMethod("getService", String.class).invoke(null, "telephony.registry");
                report.put("servicePresent", service != null);
                if (service == null) { terminate("absent", null); return; }
                if (android.os.Build.VERSION.SDK_INT < 29) throw new UnsupportedOperationException("Combined opportunistic fixture requires API 29");
                if (manager == null) throw new IllegalStateException("SubscriptionManager is unavailable with a live registry");
                registered[0] = true; manager.addOnSubscriptionsChangedListener(ordinary);
                registered[1] = true; manager.addOnOpportunisticSubscriptionsChangedListener(command -> handler.post(command), opportunistic);
                int checked = 0;
                for (String owner : new String[]{"android.telephony.TelephonyManager", "android.telephony.TelephonyRegistryManager"}) {
                    Field field;
                    try { field = Class.forName(owner).getDeclaredField("sRegistry"); }
                    catch (ClassNotFoundException | NoSuchFieldException absent) { continue; }
                    field.setAccessible(true);
                    if (((IInterface) field.get(null)).asBinder() != service) throw new IllegalStateException("Registry cache bypasses adapted service");
                    checked++;
                }
                report.put("cacheCount", checked); phase = "listening"; publish(); handler.post(this::poll);
            } catch (Throwable failure) { terminate("failed", failure); }
        }
        void poll() {
            if (terminal) return;
            try {
                if (commandFile.isFile()) {
                    String value = new String(Files.readAllBytes(commandFile.toPath()), StandardCharsets.UTF_8);
                    Files.delete(commandFile.toPath());
                    if (value.equals("remove")) { remove(); phase = "removed"; publish(); }
                    else if (value.equals("finish")) { terminate("finished", null); return; }
                    else throw new IllegalStateException("Unknown registry fixture command");
                }
                if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("Registry fixture control expired");
                handler.postDelayed(this::poll, 40);
            } catch (Throwable failure) { terminate("failed", failure); }
        }
        void remove() {
            RuntimeException failure = null;
            try { if (registered[0]) { manager.removeOnSubscriptionsChangedListener(ordinary); registered[0] = false; } }
            catch (RuntimeException cleanup) { failure = cleanup; }
            try { if (registered[1]) { manager.removeOnOpportunisticSubscriptionsChangedListener(opportunistic); registered[1] = false; } }
            catch (RuntimeException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            if (failure != null) throw failure;
        }
        void terminate(String outcome, Throwable failure) {
            if (terminal) return;
            terminal = true; handler.removeCallbacksAndMessages(null);
            try { remove(); } catch (Throwable cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            phase = failure == null ? outcome : "failed";
            if (failure != null) try { report.put("failure", failure.toString()); } catch (Exception ignored) { }
            try { publish(); }
            finally { if (!destroyed) activity.finish(); }
        }
        @Override public void onActivityDestroyed(Activity subject) {
            if (subject != activity) return;
            destroyed = true;
            try {
                if (!terminal) terminate("failed", new IllegalStateException("Registry fixture Activity destroyed before completion"));
                publish();
            } finally { activity.getApplication().unregisterActivityLifecycleCallbacks(this); }
        }
        @Override public void onActivityCreated(Activity subject, Bundle state) { }
        @Override public void onActivityStarted(Activity subject) { }
        @Override public void onActivityResumed(Activity subject) { }
        @Override public void onActivityPaused(Activity subject) { }
        @Override public void onActivityStopped(Activity subject) { }
        @Override public void onActivitySaveInstanceState(Activity subject, Bundle state) { }
        void publish() {
            try {
                report.put("nonce", nonce); report.put("cleanupComplete", terminal && !registered[0] && !registered[1]);
                report.put("activityDestroyed", destroyed);
                report.put("phase", phase); report.put("ordinary", callbacks[0]); report.put("opportunistic", callbacks[1]);
                File pending = new File(reportFile.getParentFile(), reportFile.getName() + ".tmp");
                Files.write(pending.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(pending.toPath(), reportFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }
    private TelephonyRegistryProbe() { }
}
