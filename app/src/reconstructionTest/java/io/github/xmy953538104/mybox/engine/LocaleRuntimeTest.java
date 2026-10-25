package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.LocaleList;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

public final class LocaleRuntimeTest extends InstrumentationTestCase {
    public void testRealGuestLanguageChangesAndColdRestorationLeaveHostUntouched() throws Exception {
        if (android.os.Build.VERSION.SDK_INT != 34) return;
        Context host = getInstrumentation().getTargetContext(); Engine engine = Engine.get();
        LocaleList before = host.getResources().getConfiguration().getLocales();
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            File apk = new File(host.getCacheDir(), "locale-runtime-fixture.apk");
            try (java.io.InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                Files.copy(input, apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try { installed = engine.install(java.util.Collections.singletonList(apk)); }
            finally { Files.deleteIfExists(apk.toPath()); }
        }
        for (String phase : new String[]{"set", "reset"}) {
            File file = new File(installed.dataDir, "files/locale-" + phase + ".json"); Files.deleteIfExists(file.toPath());
            FixtureRun.prepare(installed, "locales-" + phase); engine.launch(name);
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (!file.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            assertTrue("Real locale probe did not complete " + phase, file.isFile());
            JSONObject report = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            assertFalse(report.toString(), report.has("failure")); assertTrue(report.getBoolean("resources")); assertTrue(report.getBoolean("callback"));
            assertEquals(before, host.getResources().getConfiguration().getLocales());
            if (phase.equals("set")) {
                int pid = report.getInt("pid"); boolean owned = false;
                for (android.app.ActivityManager.RunningAppProcessInfo process : host.getSystemService(android.app.ActivityManager.class).getRunningAppProcesses()) {
                    if (process.pid == pid && process.uid == android.os.Process.myUid() && process.processName.startsWith(host.getPackageName() + ":engine_p")) owned = true;
                }
                assertTrue("Refusing to terminate a process outside the owned guest pool", owned);
                android.os.Process.killProcess(pid);
                long dead = SystemClock.elapsedRealtime() + 10000;
                while (new File("/proc/" + pid).exists() && SystemClock.elapsedRealtime() < dead) Thread.sleep(25);
                assertFalse("Original guest process did not exit", new File("/proc/" + pid).exists());
            } else { assertTrue(report.getBoolean("restoredBeforeCreate")); assertTrue(report.getBoolean("cleared")); }
        }
    }
}
