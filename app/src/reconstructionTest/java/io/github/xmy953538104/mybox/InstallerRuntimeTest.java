package io.github.xmy953538104.mybox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
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
        if (original == null) {
            File apk = new File(context.getCacheDir(), "installer-target-v1.apk");
            try {
                try (ZipFile archive = new ZipFile(fixture.baseApk); InputStream input = archive.getInputStream(archive.getEntry("assets/installer-target-v1.apk"))) {
                    MainActivity.copyApk(input, apk);
                }
                original = Engine.get().install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        File sentinel = new File(original.dataDir, "installer-preserved-data");
        Files.write(sentinel.toPath(), "fixture-data-must-survive".getBytes(StandardCharsets.UTF_8));
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
                "downgradeRejected", "targetMismatchRejected", "abandonRevokesPfd", "pathRejected", "dexMetadataStaged", "abandonAfterClose", "sessionIconUpdated", "publishedInventoryFresh"}) assertTrue(check + ": " + report, report.getBoolean(check));
        InstalledPackage updated = Engine.get().packages().get(targetName);
        assertEquals(fixtureName, updated.installerPackageName);
        assertEquals(2L, updated.versionCode); assertEquals(original.appId, updated.appId); assertEquals(original.dataDir, updated.dataDir);
        assertEquals("fixture-data-must-survive", new String(Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
        assertNull(Engine.get().packages().get("org.mybox.fixture.wrongtarget"));
        try { context.getPackageManager().getPackageInfo(targetName, 0); fail("Virtual installer leaked into the physical package manager"); }
        catch (PackageManager.NameNotFoundException expected) { }
    }
}
