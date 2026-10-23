// SPDX-License-Identifier: Apache-2.0
package io.github.xmy953538104.mybox;

import android.app.LocaleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.LocaleList;
import android.os.SystemClock;
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

public final class LocaleCompatibilityTest extends InstrumentationTestCase {
    private static final String PROBE = "io.github.xmy953538104.mybox.probe";

    public void testGuestLocalesPersistAndNeverChangeHostOrSystem() throws Exception {
        if (Build.VERSION.SDK_INT < 33) return;
        Context target = getInstrumentation().getTargetContext();
        ((MyboxApplication) target.getApplicationContext()).requireEngine();
        LocaleManager host = target.getSystemService(LocaleManager.class);
        LocaleList hostBefore = host.getApplicationLocales();
        LocaleList systemBefore = host.getSystemLocales();
        File apk = new File(target.getCacheDir(), "locale-probe.apk");
        BlackBoxCore.get().uninstallPackageAsUser(PROBE, 0);
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("probe.apk")) {
                MainActivity.copyApk(input, apk);
            }
            InstallResult install = BlackBoxCore.getBPackageManager().installPackageSetAsUser(
                    new String[]{apk.getAbsolutePath()}, InstallOption.installByStorage(), 0, target.getPackageName());
            assertNotNull(install);
            assertTrue(install.msg, install.success);
            JSONObject set = runStage("set");
            assertTrue(set.toString(), set.optBoolean("foreignPackageAndUserDenied"));
            assertEquals(hostBefore, host.getApplicationLocales());
            assertEquals(systemBefore, host.getSystemLocales());
            BlackBoxCore.get().stopPackage(PROBE, 0);
            JSONObject restored = runStage("restore");
            assertFalse("Restore did not start a new process", set.getInt("pid") == restored.getInt("pid"));
            JSONObject cleared = runStage("clear");
            assertEquals("Clearing the task must keep its guest process", restored.getInt("pid"), cleared.getInt("pid"));
            assertFalse("CLEAR_TASK reused the previous Activity instance",
                    restored.getString("instanceId").equals(cleared.getString("instanceId")));
            assertEquals(hostBefore, host.getApplicationLocales());
            assertEquals(systemBefore, host.getSystemLocales());
        } finally {
            apk.delete();
            BlackBoxCore.get().uninstallPackageAsUser(PROBE, 0);
        }
    }

    private JSONObject runStage(String stage) throws Exception {
        File report = new File(BEnvironment.getDataFilesDir(PROBE, 0), "locale-" + stage + ".json");
        report.delete();
        Intent launch = new Intent().setClassName(PROBE, PROBE + ".LocaleProbeActivity")
                .putExtra("localeStage", stage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        BlackBoxCore.get().startActivity(launch, 0);
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (!report.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        assertTrue("No guest locale report for " + stage, report.isFile());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(report)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        JSONObject result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        assertEquals("New Activity received stale launch extras", stage, result.getString("stage"));
        assertTrue(result.toString(), result.optBoolean("passed"));
        return result;
    }
}
