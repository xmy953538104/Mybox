package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.json.JSONObject;

public final class ComponentStateRuntimeTest extends InstrumentationTestCase {
    public void testSelfChangesAffectRealAliasLaunchesAndSurviveServerRestart() throws Exception {
        Context context = getInstrumentation().getTargetContext(); Engine engine = Engine.get();
        String name = "io.github.xmy953538104.mybox.fixture", alias = name + ".StateAlias";
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            File apk = new File(context.getCacheDir(), "component-state-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk"); FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] bytes = new byte[16384]; for (int count; (count = input.read(bytes)) != -1;) output.write(bytes, 0, count);
                }
                installed = engine.install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        // Test setup uses only its owned fixture, preserving all other package overrides.
        engine.packages().setComponentEnabledSettings(name, installed.baseApk.getAbsolutePath(), Collections.singletonMap(alias, 0));
        for (String phase : new String[]{"initial", "resume"}) {
            File file = new File(installed.dataDir, "files/component-state-" + phase + ".json");
            if (file.exists() && !file.delete()) throw new java.io.IOException("Cannot clear component state report");
            FixtureRun.prepare(installed, phase.equals("initial") ? "component-state" : "component-state-resume"); engine.launch(name);
            long end = SystemClock.elapsedRealtime() + 30000;
            while (!file.isFile() && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
            assertTrue("Missing component state report " + phase, file.isFile());
            JSONObject result = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            assertFalse(result.toString(), result.has("failure"));
            for (String check : new String[]{"enabledQueries", "actualAliasActivity", "disabledQueries", "negativeBoundaries", "manifestDefault", "liveServiceTransitions", "liveReceiverTransitions"}) assertTrue(check + ": " + result, result.getBoolean(check));
            if (android.os.Build.VERSION.SDK_INT >= 33) assertTrue(result.toString(), result.getBoolean("atomicBatchApi"));
            assertEquals(phase.equals("initial") ? 1 : 0, new PackageStore(context).componentEnabledSetting(name, alias));
            if (phase.equals("initial")) {
                int serverPid = -1;
                for (android.app.ActivityManager.RunningAppProcessInfo process : context.getSystemService(android.app.ActivityManager.class).getRunningAppProcesses()) {
                    if (process.uid == android.os.Process.myUid() && (context.getPackageName() + ":engine_server").equals(process.processName)) serverPid = process.pid;
                }
                assertTrue(serverPid > 0 && serverPid != android.os.Process.myPid());
                android.os.IBinder old = engine.server(); android.os.Process.killProcess(serverPid);
                long dead = SystemClock.elapsedRealtime() + 10000;
                while (old.isBinderAlive() && SystemClock.elapsedRealtime() < dead) Thread.sleep(25);
                assertFalse("Original state server did not exit", old.isBinderAlive());
            }
        }
        java.util.Map<String, Integer> invalid = new java.util.LinkedHashMap<>(); invalid.put(alias, 1); invalid.put(name + ".Missing", 2);
        try { engine.packages().setComponentEnabledSettings(name, installed.baseApk.getAbsolutePath(), invalid); fail("Invalid batch was published"); }
        catch (IllegalArgumentException expected) { assertEquals(0, engine.packages().componentEnabledSetting(name, alias)); }
        try { engine.packages().setComponentEnabledSettings(name, "stale-generation", Collections.singletonMap(alias, 1)); fail("Stale generation changed state"); }
        catch (SecurityException expected) { assertEquals(0, engine.packages().componentEnabledSetting(name, alias)); }
        try { engine.packages().setComponentEnabledSettings(name, installed.baseApk.getAbsolutePath(), Collections.singletonMap(alias, 3)); fail("Unsupported state was published"); }
        catch (IllegalArgumentException expected) { assertEquals(0, engine.packages().componentEnabledSetting(name, alias)); }
    }
}
