package io.github.xmy953538104.mybox;

import android.content.Context;
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
            assertTrue(report.toString(), report.optBoolean("nativeIo"));
        } finally {
            apk.delete();
            BlackBoxCore.get().uninstallPackageAsUser(probe, 0);
        }
    }
}
