package io.github.xmy953538104.mybox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.EngineDiagnostics;
import io.github.xmy953538104.mybox.engine.WebInputRuntimeTest;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/** Real framework PFD/PackageInstaller calls in a guest, with host-side persistence assertions. */
public final class InstallerRuntimeTest extends InstrumentationTestCase {
    public void testVirtualPackageInstallerPublishesVerifiedUpdateAndStatus() throws Exception {
        boolean before = EngineDiagnostics.enabled(); EngineDiagnostics.setEnabled(true);
        Throwable primary = null;
        Context host = getInstrumentation().getTargetContext();
        try {
            exerciseInstallation();
        } catch (Throwable failure) {
            primary = failure;
            try { WebInputRuntimeTest.captureWindowFailure(getInstrumentation().getUiAutomation(), host, "installer-failure"); }
            catch (Throwable capture) { failure.addSuppressed(capture); }
            throw failure;
        } finally {
            try {
                Engine.get().captureDiagnostics();
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                EngineDiagnostics.export(host, bytes);
                Files.write(new File(host.getFilesDir(), "installer-diagnostic-export.zip").toPath(), bytes.toByteArray());
                if (primary == null) {
                    String diagnostics = DiagnosticExportTest.readZip(bytes.toByteArray());
                    for (String event : new String[]{"persisted_COMMITTING", "commit_outcome", "status_delivered", "released", "validate.failed"})
                        assertTrue("Real installer did not retain diagnostic phase " + event, diagnostics.contains("\"operation\":\"" + event + "\""));
                    assertTrue(diagnostics.contains("\"role\":\"engine_p"));
                }
            } catch (Throwable capture) { if (primary != null) primary.addSuppressed(capture); else throw capture; }
            finally { EngineDiagnostics.setEnabled(before); }
        }
    }
    private void exerciseInstallation() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ((MyboxApplication) context.getApplicationContext()).requireEngine();
        Class<?> wire = Class.forName("io.github.xmy953538104.mybox.engine.installer.InstallerWire");
        java.lang.reflect.Method iconValidator = wire.getDeclaredMethod("checkedIcon", byte[].class); iconValidator.setAccessible(true);
        for (byte[] invalidIcon : new byte[][]{new byte[]{1, 2, 3}, new byte[48 * 1024 + 1]}) {
            try { iconValidator.invoke(null, (Object) invalidIcon); fail("Malformed icon accepted at installer wire boundary"); }
            catch (java.lang.reflect.InvocationTargetException rejected) { assertTrue(rejected.getCause() instanceof IllegalArgumentException); }
        }
        String fixtureName = "io.github.xmy953538104.mybox.fixture";
        String targetName = "io.github.xmy953538104.mybox.installtarget";
        InstalledPackage fixture = Engine.get().packages().get(fixtureName);
        if (fixture == null) {
            File apk = new File(context.getCacheDir(), "engine-fixture-installer.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) { MainActivity.copyApk(input, apk); }
                fixture = Engine.get().install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        InstalledPackage original = Engine.get().packages().get(targetName);
        boolean firstInstall = original == null;
        if (firstInstall) {
            File apk = new File(context.getCacheDir(), "installer-target-v1.apk");
            try {
                try (ZipFile archive = new ZipFile(fixture.baseApk); InputStream input = archive.getInputStream(archive.getEntry("assets/installer-target-v1.apk"))) {
                    MainActivity.copyApk(input, apk);
                }
                original = Engine.get().install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        if (firstInstall) {
            JSONObject added = awaitPackageEvent(original, 1, "PACKAGE_ADDED");
            assertFalse("Initial manifest event was marked as an update", added.getBoolean("replacing"));
        }
        File sentinel = new File(original.dataDir, "installer-preserved-data");
        Files.write(sentinel.toPath(), "fixture-data-must-survive".getBytes(StandardCharsets.UTF_8));
        File targetFiles = new File(original.dataDir, "files");
        if (!targetFiles.isDirectory() && !targetFiles.mkdirs()) throw new java.io.IOException("Cannot prepare target launch probe");
        String launchNonce = java.util.UUID.randomUUID().toString();
        Files.write(new File(targetFiles, "installer-launch-request").toPath(), launchNonce.getBytes(StandardCharsets.UTF_8));
        File guestFiles = new File(fixture.dataDir, "files");
        if (!guestFiles.isDirectory() && !guestFiles.mkdirs()) throw new java.io.IOException("Cannot prepare fixture data");
        File reportFile = new File(guestFiles, "installer-result.json");
        if (reportFile.exists() && !reportFile.delete()) throw new java.io.IOException("Cannot clear installer fixture report");
        Files.write(new File(guestFiles, "installer-run").toPath(), new byte[]{1});
        FixtureRun.prepare(fixture, "installer");
        Engine.get().launch(fixtureName);
        long deadline = SystemClock.elapsedRealtime() + 110_000;
        while (!reportFile.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        assertTrue("Virtual installer fixture did not produce a report", reportFile.isFile());
        JSONObject report = new JSONObject(new String(Files.readAllBytes(reportFile.toPath()), StandardCharsets.UTF_8));
        assertFalse(report.toString(), report.has("failure"));
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(report.toString(), report.getBoolean("checksumHintsAccepted"));
        if (android.os.Build.VERSION.SDK_INT >= 30) assertTrue(report.toString(), report.getBoolean("nonStreamingDataLoaderAbsent"));
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertTrue(report.toString(), report.getBoolean("appMetadataRoundTrip"));
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission("android.permission.ENFORCE_UPDATE_OWNERSHIP"));
            assertTrue(report.toString(), report.getBoolean("updateOwnershipDeclined"));
        }
        for (String check : new String[]{"inheritRejected", "explicitConstraintsRejected", "pfdRoundTrip", "sealed", "realCommit", "statusBroadcast", "sessionListener",
                "downgradeRejected", "targetMismatchRejected", "abandonRevokesPfd", "pathRejected", "dexMetadataStaged", "abandonAfterClose", "sessionIconUpdated", "publishedInventoryFresh", "pendingTargetIndexed", "closedSessionRecovery", "categoryHintPersisted", "packageLifecycleEvents", "packageEventForgeryRejected"}) assertTrue(check + ": " + report, report.getBoolean(check));
        assertEquals(android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY,
                Engine.get().packages().getPackageInfo(targetName, 0).applicationInfo.category);
        InstalledPackage updated = Engine.get().packages().get(targetName);
        for (String action : new String[]{"PACKAGE_ADDED", "PACKAGE_REPLACED", "MY_PACKAGE_REPLACED"}) {
            JSONObject event = awaitPackageEvent(updated, 2, action);
            assertTrue("Update manifest event did not carry replacing", event.getBoolean("replacing"));
            assertEquals(!"MY_PACKAGE_REPLACED".equals(action), event.getBoolean("dataPresent"));
        }
        assertEquals(fixtureName, updated.installerPackageName);
        assertEquals(2L, updated.versionCode); assertEquals(original.appId, updated.appId); assertEquals(original.dataDir, updated.dataDir);
        assertTrue(report.getBoolean("launchRequested"));
        assertTrue(report.getBoolean("crossPackageGuards"));
        File launchFile = new File(targetFiles, "installer-launched.json");
        JSONObject launched = awaitLaunch(launchFile, launchNonce, updated);
        String applicationNonce = java.util.UUID.randomUUID().toString();
        Files.write(new File(targetFiles, "installer-launch-request").toPath(), applicationNonce.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(fixture, "installer-application-open"); Engine.get().launch(fixtureName);
        JSONObject applicationLaunch = awaitLaunch(launchFile, applicationNonce, updated);
        assertTrue("Application reopen reused a finishing or removed task", launched.getInt("taskId") != applicationLaunch.getInt("taskId"));
        System.out.println("MYLITE_INSTALLER " + report + " launched=" + launched + " applicationLaunch=" + applicationLaunch);
        assertEquals("fixture-data-must-survive", new String(Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
        assertNull(Engine.get().packages().get("org.mybox.fixture.wrongtarget"));
        try { context.getPackageManager().getPackageInfo(targetName, 0); fail("Virtual installer leaked into the physical package manager"); }
        catch (PackageManager.NameNotFoundException expected) { }
    }

    private JSONObject awaitPackageEvent(InstalledPackage installed, int version, String action) throws Exception {
        File file = new File(installed.dataDir, "files/package-event-" + version + "-" + action + ".json");
        long until = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < until) {
            if (file.isFile()) {
                JSONObject current = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                if (installed.baseApk.getAbsolutePath().equals(current.optString("source"))) break;
            }
            Thread.sleep(25);
        }
        assertTrue("Missing actual manifest package event: " + action, file.isFile());
        JSONObject report = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertEquals(installed.baseApk.getAbsolutePath(), report.getString("source"));
        assertEquals("android.intent.action." + action, report.getString("action"));
        assertEquals(installed.packageName, report.getString("package")); assertEquals(version, report.getInt("codeVersion"));
        assertEquals(version, report.getInt("publishedVersion")); assertTrue(report.getInt("pid") != android.os.Process.myPid());
        return report;
    }

    private JSONObject awaitLaunch(File launchFile, String launchNonce, InstalledPackage updated) throws Exception {
        long launchDeadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < launchDeadline) {
            if (launchFile.isFile() && launchNonce.equals(new JSONObject(new String(Files.readAllBytes(launchFile.toPath()), StandardCharsets.UTF_8)).optString("nonce"))) break;
            Thread.sleep(25);
        }
        assertTrue("Installed target never published its focused-window report", launchFile.isFile());
        JSONObject launched = new JSONObject(new String(Files.readAllBytes(launchFile.toPath()), StandardCharsets.UTF_8));
        assertEquals(launched.toString(), launchNonce, launched.getString("nonce")); assertEquals(2, launched.getInt("codeVersion"));
        assertEquals(updated.packageName, launched.getString("package"));
        // shortcut: Context/metadata retain the real host UID until the full guest identity boundary is reconstructed.
        assertEquals(android.os.Process.myUid(), launched.getInt("uid"));
        assertEquals(android.os.Process.myUid(), launched.getInt("processUid"));
        assertEquals(android.os.Process.myUid(), launched.getInt("packageInfoUid"));
        assertEquals(updated.appId, launched.getInt("packageUid"));
        assertEquals(updated.baseApk.getPath(), launched.getString("source"));
        assertTrue(launched.getInt("pid") != android.os.Process.myPid());
        assertTrue(launched.toString(), launched.getInt("taskId") >= 0 && launched.getBoolean("finishRequested"));
        assertTrue(launched.toString(), launched.getBoolean("focused")); assertTrue(launched.toString(), launched.getBoolean("launcherDisabledAndRestored"));
        return launched;
    }
}
