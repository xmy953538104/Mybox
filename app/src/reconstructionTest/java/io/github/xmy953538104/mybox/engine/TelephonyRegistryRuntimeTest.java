package io.github.xmy953538104.mybox.engine;

import android.app.UiAutomation;
import android.os.IBinder;
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

public final class TelephonyRegistryRuntimeTest extends InstrumentationTestCase {
    public void testActualSubscriptionCallbacksAndRemovalKeepTheOsRegistry() throws Throwable {
        // Both public listener types are present from API 29; the production ordinary adapter also supports API 26.
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        Engine engine = Engine.get(); String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "telephony-registry-fixture.apk");
            Throwable installFailure = null;
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                    Files.copy(input, apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                installed = engine.install(Collections.singletonList(apk));
            } catch (Throwable failure) { installFailure = failure; throw failure; }
            finally {
                try { Files.deleteIfExists(apk.toPath()); }
                catch (Exception cleanup) { if (installFailure != null) installFailure.addSuppressed(cleanup); else throw cleanup; }
            }
        }
        IBinder physical = (IBinder) RuntimeReflection.invoke(null,
                RuntimeReflection.method(Class.forName("android.os.ServiceManager"), "getService", String.class), "telephony.registry");
        File files = new File(installed.dataDir, "files");
        String nonce = UUID.randomUUID().toString(), prefix = "telephony-registry-" + nonce;
        File report = new File(files, prefix + "-result.json"), control = new File(files, prefix + "-command");
        Throwable primary = null;
        try {
            FixtureRun.prepare(installed, "telephony-registry:" + nonce); engine.launch(name);
            JSONObject ready = await(report, physical == null ? "absent" : "listening", -1, -1);
            assertEquals(nonce, ready.getString("nonce"));
            assertEquals(name, ready.getString("package"));
            assertEquals(physical != null, ready.getBoolean("servicePresent"));
            if (physical == null) return; // Observe absence, without claiming callback coverage on such a runner.
            assertTrue(ready.toString(), ready.getInt("cacheCount") > 0);
            Object service = RuntimeReflection.invoke(null, RuntimeReflection.method(
                    Class.forName("com.android.internal.telephony.ITelephonyRegistry$Stub"), "asInterface", IBinder.class), physical);
            // Initial registration can itself deliver one callback; two separate real OS notifications
            // ensure the assertions cannot be satisfied solely by that initial delivery.
            for (int round = 0; round < 2; round++) {
                JSONObject before = read(report);
                notifyOs(service);
                await(report, "listening", before.getInt("ordinary"), before.getInt("opportunistic"));
            }
            command(control, "remove"); await(report, "removed", -1, -1);
            // Drain callbacks already posted before removal, then verify new OS events do not arrive.
            Thread.sleep(250);
            JSONObject removed = read(report);
            notifyOs(service); Thread.sleep(700);
            JSONObject after = read(report);
            assertFalse(after.toString(), after.has("failure"));
            assertEquals("removed", after.getString("phase"));
            assertEquals(removed.getInt("ordinary"), after.getInt("ordinary"));
            assertEquals(removed.getInt("opportunistic"), after.getInt("opportunistic"));
            command(control, "finish"); await(report, "finished", -1, -1);
        } catch (Throwable failure) {
            primary = failure; throw failure;
        } finally {
            try { awaitCleanup(report, control, nonce); }
            catch (Throwable cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
        }
    }

    private void notifyOs(Object service) throws Exception {
        UiAutomation automation = getInstrumentation().getUiAutomation();
        automation.adoptShellPermissionIdentity("android.permission.MODIFY_PHONE_STATE");
        try {
            Class<?> contract = Class.forName("com.android.internal.telephony.ITelephonyRegistry");
            RuntimeReflection.invoke(service, contract.getMethod("notifySubscriptionInfoChanged"));
            RuntimeReflection.invoke(service, contract.getMethod("notifyOpportunisticSubscriptionInfoChanged"));
        } finally { automation.dropShellPermissionIdentity(); }
    }
    private JSONObject await(File report, String phase, int ordinary, int opportunistic) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 12000; JSONObject result = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (report.isFile()) {
                result = read(report); assertFalse(result.toString(), result.has("failure"));
                if (phase.equals(result.getString("phase")) && (ordinary < 0
                        || result.getInt("ordinary") > ordinary && result.getInt("opportunistic") > opportunistic)) return result;
            }
            Thread.sleep(40);
        }
        fail("No actual registry phase/callback: " + phase + " " + result); return null;
    }
    private void awaitCleanup(File report, File control, String nonce) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000; boolean requested = false;
        JSONObject result = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean terminal = false;
            if (report.isFile()) {
                result = read(report); assertEquals("Cleanup acknowledgement from another session", nonce, result.getString("nonce"));
                String phase = result.getString("phase");
                terminal = phase.equals("finished") || phase.equals("failed") || phase.equals("absent");
                if (terminal && result.getBoolean("activityDestroyed")) {
                    assertTrue("Actual listener unregister failed: " + result, result.getBoolean("cleanupComplete"));
                    return;
                }
            }
            if (!terminal && !requested) { command(control, "finish"); requested = true; }
            Thread.sleep(40);
        }
        fail("No actual registry cleanup and Activity destruction for " + nonce + ": " + result);
    }
    private static JSONObject read(File report) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
    }
    private static void command(File file, String value) throws Exception {
        File pending = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.write(pending.toPath(), value.getBytes(StandardCharsets.UTF_8));
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
