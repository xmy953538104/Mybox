package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;

/** Real self-directed PM changes and a launcher alias that starts disabled in the manifest. */
public final class ComponentStateProbe {
    public static void run(Activity context, boolean resume) { new Thread(() -> probe(context, resume), "component-state-fixture").start(); }
    private static void probe(Activity context, boolean resume) {
        JSONObject report = new JSONObject(); String phase = resume ? "resume" : "initial";
        try {
            PackageManager pm = context.getPackageManager(); ComponentName alias = new ComponentName(context, context.getPackageName() + ".StateAlias");
            ComponentName missing = new ComponentName(context, context.getPackageName() + ".AbsentStateAlias");
            int pid = Process.myPid();
            require(pm.getComponentEnabledSetting(alias) == (resume ? 1 : 0), "Component override did not survive its expected phase");
            if (!resume) {
                require(!pm.getActivityInfo(alias, PackageManager.MATCH_DISABLED_COMPONENTS).enabled, "Disabled manifest alias was altered");
                absent(pm, alias);
                pm.setComponentEnabledSetting(alias, 1, PackageManager.DONT_KILL_APP);
            }
            require(pm.getComponentEnabledSetting(alias) == 1 && pm.getActivityInfo(alias, 0).enabled, "Enabled component metadata is stale");
            require(pm.resolveActivity(new Intent().setComponent(alias), 0) != null, "Enabled component resolution is stale");
            report.put("enabledQueries", true);
            String run = UUID.randomUUID().toString(); File activityReport = new File(context.getFilesDir(), "component-state-activity.json");
            if (activityReport.exists() && !activityReport.delete()) throw new IllegalStateException("Cannot clear Activity report");
            context.runOnUiThread(() -> context.startActivity(new Intent().setComponent(alias).putExtra("run", run)));
            long end = SystemClock.elapsedRealtime() + 10000;
            while (!activityReport.isFile() && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
            require(activityReport.isFile(), "Enabled alias never created its real Activity");
            JSONObject activity = new JSONObject(new String(Files.readAllBytes(activityReport.toPath()), StandardCharsets.UTF_8));
            require(run.equals(activity.getString("run")) && alias.flattenToString().equals(activity.getString("component"))
                    && pid == activity.getInt("pid"), "Alias Activity did not preserve identity or Intent");
            report.put("actualAliasActivity", true);
            pm.setComponentEnabledSetting(alias, 2, PackageManager.DONT_KILL_APP);
            require(Process.myPid() == pid && pm.getComponentEnabledSetting(alias) == 2, "DONT_KILL_APP did not preserve its owner");
            absent(pm, alias); require(pm.resolveActivity(new Intent().setComponent(alias), 0) == null, "Disabled component still resolves");
            require(!pm.getActivityInfo(alias, PackageManager.MATCH_DISABLED_COMPONENTS).enabled, "Disabled override was absent from explicit metadata");
            report.put("disabledQueries", true);
            try { pm.setComponentEnabledSetting(alias, 1, 0); throw new AssertionError("Unsupported termination flags were accepted"); }
            catch (RuntimeException expected) { }
            try { pm.setComponentEnabledSetting(missing, 1, PackageManager.DONT_KILL_APP); throw new AssertionError("Unknown component was enabled"); }
            catch (RuntimeException expected) { }
            try { pm.setComponentEnabledSetting(new ComponentName("com.google.android.gms", "foreign.Component"), 1, 1); throw new AssertionError("Foreign package state was changed"); }
            catch (RuntimeException expected) { }
            require(pm.getComponentEnabledSetting(alias) == 2, "Rejected change had a side effect"); report.put("negativeBoundaries", true);
            pm.setComponentEnabledSetting(alias, 0, PackageManager.DONT_KILL_APP);
            require(pm.getComponentEnabledSetting(alias) == 0, "DEFAULT override did not restore manifest state"); absent(pm, alias);
            report.put("manifestDefault", true);
            ComponentName service = new ComponentName(context, DynamicService.class);
            require(pm.getComponentEnabledSetting(service) == 0, "Service override leaked between runs");
            pm.setComponentEnabledSetting(service, 1, PackageManager.DONT_KILL_APP);
            require(pm.getServiceInfo(service, 0).enabled, "Enabled Service metadata was stale");
            String serviceRun = UUID.randomUUID().toString();
            java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
            android.content.ServiceConnection connection = new android.content.ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, android.os.IBinder binder) {
                    try { if (service.equals(name) && "mylite.dynamic.service".equals(binder.getInterfaceDescriptor())) connected.countDown(); }
                    catch (android.os.RemoteException failure) { throw new IllegalStateException(failure); }
                }
                @Override public void onServiceDisconnected(ComponentName name) { }
            };
            boolean bound = context.bindService(new Intent().setComponent(service).putExtra("run", serviceRun), connection, Context.BIND_AUTO_CREATE);
            require(bound, "Enabled Service was not bound");
            try {
                require(connected.await(10, java.util.concurrent.TimeUnit.SECONDS), "Enabled Service returned no real Binder");
                pm.setComponentEnabledSetting(service, 2, PackageManager.DONT_KILL_APP);
                require(!pm.getServiceInfo(service, PackageManager.MATCH_DISABLED_COMPONENTS).enabled, "Service disable was not visible");
            } finally { context.unbindService(connection); }
            File destroyed = new File(context.getFilesDir(), "component-state-service-" + serviceRun + ".json");
            long stopped = SystemClock.elapsedRealtime() + 10000;
            while (!destroyed.isFile() && SystemClock.elapsedRealtime() < stopped) Thread.sleep(25);
            require(destroyed.isFile(), "Disabled live Service could not unbind and destroy");
            pm.setComponentEnabledSetting(service, 0, PackageManager.DONT_KILL_APP);
            report.put("liveServiceTransitions", true);
            try { pm.setComponentEnabledSetting(new ComponentName(context, FixtureProvider.class), 1, PackageManager.DONT_KILL_APP); throw new AssertionError("Unimplemented Provider transition was accepted"); }
            catch (RuntimeException expected) { }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                Class<?> item = Class.forName("android.content.pm.PackageManager$ComponentEnabledSetting");
                java.lang.reflect.Constructor<?> constructor = item.getConstructor(ComponentName.class, int.class, int.class);
                java.lang.reflect.Method setter = PackageManager.class.getMethod("setComponentEnabledSettings", java.util.List.class);
                setter.invoke(pm, java.util.Arrays.asList(constructor.newInstance(alias, 1, 3), constructor.newInstance(service, 1, 3)));
                require(pm.getComponentEnabledSetting(alias) == 1 && pm.getComponentEnabledSetting(service) == 1, "Batch did not publish both valid targets");
                try {
                    setter.invoke(pm, java.util.Arrays.asList(constructor.newInstance(alias, 2, 1), constructor.newInstance(missing, 1, 1)));
                    throw new AssertionError("Invalid component batch was accepted");
                } catch (java.lang.reflect.InvocationTargetException expected) {
                    require(expected.getCause() instanceof RuntimeException, "Batch failed for an unrelated reason");
                }
                require(pm.getComponentEnabledSetting(alias) == 1 && pm.getComponentEnabledSetting(service) == 1, "Invalid batch partially changed state");
                setter.invoke(pm, java.util.Arrays.asList(constructor.newInstance(alias, 0, 1), constructor.newInstance(service, 0, 1)));
                require(pm.getComponentEnabledSetting(alias) == 0 && pm.getComponentEnabledSetting(service) == 0, "Batch did not restore defaults");
                report.put("atomicBatchApi", true);
            }
            if (!resume) pm.setComponentEnabledSetting(alias, 1, PackageManager.DONT_KILL_APP);
            report.put("pid", pid); report.put("persistedState", resume ? 0 : 1);
            One.close(context);
        } catch (Throwable failure) { try { report.put("failure", failure.toString()); } catch (Exception ignored) { } }
        publish(context, "component-state-" + phase + ".json", report);
    }
    private static void absent(PackageManager pm, ComponentName alias) throws Exception {
        try { pm.getActivityInfo(alias, 0); throw new AssertionError("Disabled component metadata was returned without MATCH_DISABLED_COMPONENTS"); }
        catch (PackageManager.NameNotFoundException expected) { }
    }
    public static final class One extends Activity {
        private static volatile One current;
        @Override protected void onCreate(Bundle saved) {
            super.onCreate(saved); current = this;
            try {
                JSONObject result = new JSONObject(); result.put("run", getIntent().getStringExtra("run"));
                result.put("component", getIntent().getComponent().flattenToString()); result.put("pid", Process.myPid());
                publish(this, "component-state-activity.json", result);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        static void close(Activity context) { context.runOnUiThread(() -> { One activity = current; if (activity != null) { current = null; activity.finish(); } }); }
    }
    public static final class DynamicService extends android.app.Service {
        private String run;
        @Override public android.os.IBinder onBind(Intent intent) {
            run = intent.getStringExtra("run"); android.os.Binder binder = new android.os.Binder(); binder.attachInterface(null, "mylite.dynamic.service"); return binder;
        }
        @Override public void onDestroy() {
            super.onDestroy(); publish(this, "component-state-service-" + run + ".json", new JSONObject());
        }
    }
    private static void publish(Context context, String name, JSONObject report) {
        try {
            File temporary = new File(context.getFilesDir(), name + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(report.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
            if (!temporary.renameTo(new File(context.getFilesDir(), name))) throw new IllegalStateException("Cannot publish component state report");
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private ComponentStateProbe() { }
}
