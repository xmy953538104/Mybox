package io.github.xmy953538104.mybox.engine;

import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;

public final class PackageCacheRuntimeTest extends InstrumentationTestCase {
    public void testRealMixedPackageRemovalPreservesBoundApplicationForNextActivity() throws Throwable {
        Engine engine = Engine.get(); String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "package-cache-fixture.apk");
            Throwable primary = null;
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                    Files.copy(input, apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                installed = engine.install(Collections.singletonList(apk));
            } catch (Throwable failure) { primary = failure; throw failure; }
            finally {
                try { Files.deleteIfExists(apk.toPath()); }
                catch (Throwable cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
            }
        }
        String nonce = UUID.randomUUID().toString(), prefix = "package-cache-" + nonce;
        File files = new File(installed.dataDir, "files");
        File report = new File(files, prefix + "-result.json"), secondFile = new File(files, prefix + "-second.json");
        File control = new File(files, prefix + "-finish");
        Throwable primary = null;
        try {
            FixtureRun.prepare(installed, "package-cache:" + nonce); engine.launch(name);
            JSONObject result = awaitComplete(report, nonce), second = read(secondFile);
            assertEquals(nonce, second.getString("nonce")); assertEquals(name, result.getString("package"));
            assertEquals(result.getInt("pid"), second.getInt("pid"));
            assertEquals(1, result.getInt("baselineOnCreateCount")); assertEquals(1, second.getInt("onCreateCount"));
            for (String check : new String[]{"codeCacheSame", "resourceCacheSame", "applicationSame", "nonGuestCodeRemoved", "nonGuestResourceRemoved"})
                assertTrue(check + ": " + result, result.getBoolean(check));
            assertFalse(second.toString(), second.has("failure"));
            for (String check : new String[]{"sameApplication", "sameLoadedApk", "resourceCacheSame", "resumed", "destroyed"})
                assertTrue(check + ": " + second, second.getBoolean(check));
        } catch (Throwable failure) { primary = failure; throw failure; }
        finally {
            try { awaitCleanup(report, secondFile, control, nonce); }
            catch (Throwable cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
        }
    }
    private JSONObject awaitComplete(File file, String nonce) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000; JSONObject result = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile()) {
                result = read(file); assertEquals(nonce, result.getString("nonce"));
                assertFalse(result.toString(), result.has("failure"));
                if (result.getString("phase").equals("complete")) return result;
            }
            Thread.sleep(40);
        }
        fail("Real package-cache/second Activity chain did not complete: " + result); return null;
    }
    private void awaitCleanup(File report, File secondFile, File control, String nonce) throws Exception {
        File pending = new File(control.getParentFile(), control.getName() + ".tmp");
        Files.write(pending.toPath(), new byte[]{1});
        Files.move(pending.toPath(), control.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        long deadline = SystemClock.elapsedRealtime() + 10000; JSONObject root = null, second = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (report.isFile()) {
                root = read(report); assertEquals(nonce, root.getString("nonce"));
                if (root.getBoolean("activityDestroyed") && root.getBoolean("cleanupComplete")) {
                    if (!root.getBoolean("secondStarted")) return;
                    if (secondFile.isFile()) {
                        second = read(secondFile); assertEquals(nonce, second.getString("nonce"));
                        if (second.getBoolean("destroyed")) return;
                    }
                }
            }
            Thread.sleep(40);
        }
        fail("No actual cache cleanup and both Activity destruction acknowledgements: " + root + "; " + second);
    }
    private static JSONObject read(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}
