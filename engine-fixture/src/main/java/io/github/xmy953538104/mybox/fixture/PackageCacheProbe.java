package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** Ordinary Android framework messages and Activity creation; no container implementation imports. */
public final class PackageCacheProbe {
    static void run(Activity activity, String nonce) { new Session(activity, nonce).start(); }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field value = current.getDeclaredField(name); value.setAccessible(true); return value; }
            catch (NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object owner, String name) throws Exception { return field(owner.getClass(), name).get(owner); }
    private static Object thread() throws Exception { return Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null); }
    private static Object referent(Object reference) { return reference == null ? null : ((WeakReference<?>) reference).get(); }
    private static String prefix(String nonce) {
        if (!UUID.fromString(nonce).toString().equals(nonce)) throw new IllegalArgumentException("Invalid package-cache nonce");
        return "package-cache-" + nonce;
    }
    private static void write(File file, JSONObject report) throws Exception {
        File pending = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.write(pending.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    private static JSONObject read(File file) throws Exception { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }

    private static final class Session implements Application.ActivityLifecycleCallbacks {
        final Activity activity;
        final String nonce, probeKey;
        final File reportFile, secondFile, control;
        final Handler main;
        final JSONObject report = new JSONObject();
        final long deadline = SystemClock.elapsedRealtime() + 20000;
        Object framework, lock, loaded, oldCode, oldResource, seededResource, probeReference;
        Map<String, Object> code, resources;
        Application application;
        boolean terminal, destroyed, secondStarted, cachePrepared, cleanupComplete;
        String phase = "starting";
        Session(Activity activity, String nonce) {
            this.activity = activity; this.nonce = nonce; String stem = prefix(nonce);
            probeKey = "io.github.mybox.cacheprobe." + nonce.replace("-", "");
            reportFile = new File(activity.getFilesDir(), stem + "-result.json");
            secondFile = new File(activity.getFilesDir(), stem + "-second.json");
            control = new File(activity.getFilesDir(), stem + "-finish");
            main = new Handler(activity.getMainLooper());
        }
        @SuppressWarnings("unchecked") void start() {
            activity.getApplication().registerActivityLifecycleCallbacks(this);
            try {
                application = activity.getApplication(); framework = thread(); lock = get(framework, "mResourcesManager");
                code = (Map<String, Object>) get(framework, "mPackages");
                resources = (Map<String, Object>) get(framework, "mResourcePackages");
                synchronized (lock) {
                    oldCode = code.get(activity.getPackageName()); loaded = referent(oldCode);
                    if (loaded == null || get(loaded, "mApplication") != application || get(framework, "mInitialApplication") != application
                            || FixtureApplication.createdInstance != application || FixtureApplication.onCreateCount != 1)
                        throw new IllegalStateException("Fixture did not begin with one bound Application and LoadedApk");
                    oldResource = resources.get(activity.getPackageName());
                    if (code.containsKey(probeKey) || resources.containsKey(probeKey)) throw new IllegalStateException("Unique probe cache key already exists");
                    cachePrepared = true;
                    if (referent(oldResource) == null) {
                        seededResource = new WeakReference<>(loaded); resources.put(activity.getPackageName(), seededResource);
                    } else if (referent(oldResource) != loaded) throw new IllegalStateException("Fixture resource cache already uses another LoadedApk");
                    probeReference = new WeakReference<>(loaded); code.put(probeKey, probeReference); resources.put(probeKey, probeReference);
                }
                report.put("resourceSeeded", seededResource != null); report.put("baselineApplication", System.identityHashCode(application));
                report.put("baselineLoadedApk", System.identityHashCode(loaded)); report.put("baselineOnCreateCount", FixtureApplication.onCreateCount);
                Object appThread = framework.getClass().getMethod("getApplicationThread").invoke(framework);
                Method dispatch = appThread.getClass().getDeclaredMethod("dispatchPackageBroadcast", int.class, String[].class); dispatch.setAccessible(true);
                // This calls the real ApplicationThread implementation, which enqueues H.DISPATCH_PACKAGE_BROADCAST.
                dispatch.invoke(appThread, 0, new String[]{activity.getPackageName(), probeKey, activity.getPackageName()});
                main.post(this::afterDispatch);
            } catch (Throwable failure) { terminate("failed", failure); }
        }
        void afterDispatch() {
            if (terminal) return;
            try {
                synchronized (lock) {
                    report.put("codeCacheSame", referent(code.get(activity.getPackageName())) == loaded);
                    report.put("resourceCacheSame", referent(resources.get(activity.getPackageName())) == loaded);
                    report.put("applicationSame", get(loaded, "mApplication") == application && activity.getApplication() == application);
                    report.put("nonGuestCodeRemoved", !code.containsKey(probeKey));
                    report.put("nonGuestResourceRemoved", !resources.containsKey(probeKey));
                }
                for (String check : new String[]{"codeCacheSame", "resourceCacheSame", "applicationSame", "nonGuestCodeRemoved", "nonGuestResourceRemoved"})
                    if (!report.getBoolean(check)) throw new IllegalStateException("Real package dispatch failed: " + check);
                secondStarted = true; phase = "second"; publish();
                activity.startActivity(new Intent(activity, Second.class).putExtra("nonce", nonce)
                        .putExtra("expected_application", System.identityHashCode(application))
                        .putExtra("expected_loaded_apk", System.identityHashCode(loaded)));
                main.post(this::poll);
            } catch (Throwable failure) { terminate("failed", failure); }
        }
        void poll() {
            if (terminal) return;
            try {
                if (control.isFile()) { terminate("cancelled", null); return; }
                if (secondFile.isFile()) {
                    JSONObject second = read(secondFile);
                    if (!nonce.equals(second.getString("nonce"))) throw new IllegalStateException("Another run wrote the second Activity report");
                    if (second.has("failure")) throw new IllegalStateException(second.getString("failure"));
                    if (second.getBoolean("destroyed")) { terminate("complete", null); return; }
                }
                if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("Second Activity did not complete");
                main.postDelayed(this::poll, 40);
            } catch (Throwable failure) { terminate("failed", failure); }
        }
        void restoreTestEntries() {
            if (!cachePrepared) return;
            synchronized (lock) {
                if (code.get(probeKey) == probeReference) code.remove(probeKey);
                if (resources.get(probeKey) == probeReference) resources.remove(probeKey);
                // Restore only entries removed by this test message on a failing implementation.
                if (!code.containsKey(activity.getPackageName())) code.put(activity.getPackageName(), oldCode);
                if (resources.get(activity.getPackageName()) == seededResource || !resources.containsKey(activity.getPackageName())) {
                    if (oldResource == null) resources.remove(activity.getPackageName()); else resources.put(activity.getPackageName(), oldResource);
                }
            }
        }
        void terminate(String outcome, Throwable failure) {
            if (terminal) return;
            terminal = true; main.removeCallbacksAndMessages(null);
            try { restoreTestEntries(); cleanupComplete = true; }
            catch (Throwable cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            phase = failure == null ? outcome : "failed";
            try {
                if (failure != null) report.put("failure", failure.toString());
                report.put("cleanupComplete", cleanupComplete); publish();
            } catch (Exception output) { throw new IllegalStateException(output); }
            finally { if (!destroyed) activity.finish(); }
        }
        void publish() {
            try {
                report.put("nonce", nonce); report.put("package", activity.getPackageName()); report.put("pid", android.os.Process.myPid());
                report.put("phase", phase); report.put("secondStarted", secondStarted); report.put("activityDestroyed", destroyed);
                write(reportFile, report);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public void onActivityDestroyed(Activity subject) {
            if (subject != activity) return;
            destroyed = true;
            try { if (!terminal) terminate("failed", new IllegalStateException("Root fixture destroyed before completion")); publish(); }
            finally { activity.getApplication().unregisterActivityLifecycleCallbacks(this); }
        }
        @Override public void onActivityCreated(Activity subject, Bundle state) { }
        @Override public void onActivityStarted(Activity subject) { }
        @Override public void onActivityResumed(Activity subject) { }
        @Override public void onActivityPaused(Activity subject) { }
        @Override public void onActivityStopped(Activity subject) { }
        @Override public void onActivitySaveInstanceState(Activity subject, Bundle state) { }
    }

    public static final class Second extends Activity {
        private final JSONObject report = new JSONObject();
        private final Handler main = new Handler(android.os.Looper.getMainLooper());
        private File file, control;
        private final long deadline = SystemClock.elapsedRealtime() + 10000;
        @Override @SuppressWarnings("unchecked") public void onCreate(Bundle saved) {
            super.onCreate(saved);
            String nonce = getIntent().getStringExtra("nonce"), stem = prefix(nonce);
            file = new File(getFilesDir(), stem + "-second.json"); control = new File(getFilesDir(), stem + "-finish");
            try {
                report.put("nonce", nonce); report.put("pid", android.os.Process.myPid()); report.put("destroyed", false); report.put("resumed", false);
                Object framework = thread(), lock = get(framework, "mResourcesManager"); Application app = getApplication();
                synchronized (lock) {
                    Object loaded = referent(((Map<String, Object>) get(framework, "mPackages")).get(getPackageName()));
                    report.put("sameApplication", app == get(framework, "mInitialApplication") && app == FixtureApplication.createdInstance
                            && System.identityHashCode(app) == getIntent().getIntExtra("expected_application", 0));
                    report.put("sameLoadedApk", loaded != null && get(loaded, "mApplication") == app
                            && System.identityHashCode(loaded) == getIntent().getIntExtra("expected_loaded_apk", 0));
                    report.put("resourceCacheSame", referent(((Map<String, Object>) get(framework, "mResourcePackages")).get(getPackageName())) == loaded);
                }
                report.put("onCreateCount", FixtureApplication.onCreateCount);
                if (!report.getBoolean("sameApplication") || !report.getBoolean("sameLoadedApk") || !report.getBoolean("resourceCacheSame") || FixtureApplication.onCreateCount != 1)
                    throw new IllegalStateException("Second Activity rebound Application or LoadedApk");
                publish(); main.post(this::watchCleanup);
            } catch (Throwable failure) { fail(failure); }
        }
        @Override public void onResume() {
            super.onResume();
            try { report.put("resumed", true); publish(); }
            catch (Exception failure) { fail(failure); return; }
            // Finish on the next turn so the framework can complete the real RESUMED acknowledgement.
            main.post(this::finish);
        }
        private void watchCleanup() {
            if (isFinishing() || isDestroyed()) return;
            if (control.isFile()) { finish(); return; }
            if (SystemClock.elapsedRealtime() >= deadline) { fail(new IllegalStateException("Second Activity never resumed")); return; }
            main.postDelayed(this::watchCleanup, 40);
        }
        @Override public void onDestroy() {
            main.removeCallbacksAndMessages(null);
            try { super.onDestroy(); }
            finally {
                try { report.put("destroyed", true); publish(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        }
        private void fail(Throwable failure) {
            try { report.put("failure", failure.toString()); publish(); }
            catch (Exception output) { failure.addSuppressed(output); android.util.Log.e("PackageCacheProbe", "Cannot publish second Activity failure", failure); }
            finally { finish(); }
        }
        private void publish() throws Exception { write(file, report); }
    }
    private PackageCacheProbe() { }
}
