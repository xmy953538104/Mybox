package io.github.xmy953538104.mybox;

import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;

public final class ContainerSmokeTest extends InstrumentationTestCase {
    public void testPackageInstallerSessionsAndUpdate() throws Exception {
        String probe = "io.github.xmy953538104.mybox.probe";
        String fixture = "io.github.xmy953538104.mybox.sessiontarget";
        Context target = getInstrumentation().getTargetContext();
        ((MyboxApplication) target.getApplicationContext()).requireEngine();
        BlackBoxCore.get().uninstallPackageAsUser(fixture, 0);
        File apk = new File(target.getCacheDir(), "session-probe.apk");
        File report = new File(BEnvironment.getDataFilesDir(probe, 0), "session-result.json");
        report.delete();
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("probe.apk")) {
                MainActivity.copyApk(input, apk);
            }
            InstallResult installed = BlackBoxCore.getBPackageManager().installPackageSetAsUser(
                    new String[]{apk.getAbsolutePath()}, InstallOption.installByStorage(), 0, target.getPackageName());
            assertNotNull(installed);
            assertTrue("Session probe install failed: " + installed.msg, installed.success);
            Intent launch = BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(probe, 0);
            assertNotNull(launch);
            launch.putExtra("runSessionProbe", true);
            BlackBoxCore.get().startActivity(launch, 0);
            long deadline = android.os.SystemClock.elapsedRealtime() + 240000;
            while (!report.isFile() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200);
            assertTrue("Session fixture did not report; inspect guest crash log", report.isFile());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(report)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            assertTrue(result.toString(), result.optBoolean("passed"));
        } finally {
            apk.delete();
            BlackBoxCore.get().uninstallPackageAsUser(fixture, 0);
            BlackBoxCore.get().uninstallPackageAsUser(probe, 0);
        }
    }

    public void testGuestProviderSqliteAndNativeIo() throws Exception {
        String probe = "io.github.xmy953538104.mybox.probe";
        Context target = getInstrumentation().getTargetContext();
        ((MyboxApplication) target.getApplicationContext()).requireEngine();
        File apk = new File(target.getCacheDir(), "smoke-probe.apk");
        try (InputStream input = getInstrumentation().getContext().getAssets().open("probe.apk")) {
            MainActivity.copyApk(input, apk);
        }
        File result = new File(BEnvironment.getDataFilesDir(probe, 0), "probe-result.json");
        result.delete();
        try {
            InstallResult installed = BlackBoxCore.getBPackageManager().installPackageSetAsUser(
                    new String[]{apk.getAbsolutePath()}, InstallOption.installByStorage(), 0, target.getPackageName());
            assertNotNull(installed);
            assertTrue("Guest install failed: " + installed.msg, installed.success);
            assertTrue("No launch intent", BlackBoxCore.get().launchApk(probe, 0));
            long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
            while (!result.isFile() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200);
            assertTrue("Guest did not report; inspect crash log", result.isFile());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(result)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject report = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            assertEquals(report.toString(), probe, report.optString("contextPackage"));
            assertTrue(report.toString(), report.optBoolean("sqlite"));
            assertTrue(report.toString(), report.optBoolean("provider"));
            assertTrue(report.toString(), report.optBoolean("providerProcessBoundary"));
            assertTrue(report.toString(), report.optBoolean("platformHttpLibrary"));
            assertTrue(report.toString(), report.optBoolean("webViewProvider"));
            assertTrue(report.toString(), report.optBoolean("noSystemGoogleAccountFallback"));
            assertTrue(report.toString(), report.optBoolean("unauthorizedPasswordDenied"));
            assertTrue(report.toString(), report.optBoolean("unauthorizedTokenReadDenied"));
            assertTrue(report.toString(), report.optBoolean("unauthorizedTokenWriteDenied"));
            assertTrue(report.toString(), report.optBoolean("nativeIo"));
        } finally {
            apk.delete();
            BlackBoxCore.get().uninstallPackageAsUser(probe, 0);
        }
    }
}
