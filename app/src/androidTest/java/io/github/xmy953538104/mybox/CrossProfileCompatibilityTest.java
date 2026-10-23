// SPDX-License-Identifier: Apache-2.0
package io.github.xmy953538104.mybox;

import android.content.Context;
import android.content.Intent;
import android.content.pm.CrossProfileApps;
import android.os.Build;
import android.os.SystemClock;
import android.os.UserHandle;
import android.test.InstrumentationTestCase;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;

public final class CrossProfileCompatibilityTest extends InstrumentationTestCase {
    public void testGuestCannotReachOrAdministerAndroidProfiles() throws Exception {
        if (Build.VERSION.SDK_INT < 28) return;
        String probe = "io.github.xmy953538104.mybox.probe";
        Context target = getInstrumentation().getTargetContext();
        ((MyboxApplication) target.getApplicationContext()).requireEngine();
        CrossProfileApps host = target.getSystemService(CrossProfileApps.class);
        List<UserHandle> hostBefore = new ArrayList<>(host.getTargetUserProfiles());
        boolean hostCanInteract = Build.VERSION.SDK_INT >= 30 && host.canInteractAcrossProfiles();
        boolean hostCanRequest = Build.VERSION.SDK_INT >= 30 && host.canRequestInteractAcrossProfiles();
        File apk = new File(target.getCacheDir(), "cross-profile-probe.apk");
        BlackBoxCore.get().uninstallPackageAsUser(probe, 0);
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("probe.apk")) {
                MainActivity.copyApk(input, apk);
            }
            InstallResult installed = BlackBoxCore.getBPackageManager().installPackageSetAsUser(
                    new String[]{apk.getAbsolutePath()}, InstallOption.installByStorage(), 0, target.getPackageName());
            assertNotNull(installed);
            assertTrue(installed.msg, installed.success);
            File report = new File(BEnvironment.getDataFilesDir(probe, 0), "cross-profile-result.json");
            Intent launch = new Intent().setClassName(probe, probe + ".CrossProfileProbeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            BlackBoxCore.get().startActivity(launch, 0);
            long deadline = SystemClock.elapsedRealtime() + 30000;
            while (!report.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            assertTrue("Guest cross-profile probe did not report", report.isFile());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(report)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            assertTrue(result.toString(), result.optBoolean("passed"));
            assertTrue(result.toString(), result.optBoolean("publicLaunchDenied"));
            assertTrue(result.toString(), result.optBoolean("rawTransactionsDenied"));
            assertEquals(hostBefore, host.getTargetUserProfiles());
            if (Build.VERSION.SDK_INT >= 30) {
                assertEquals(5, result.getInt("checkedQueries"));
                assertEquals(5, result.getInt("deniedMutations"));
                assertEquals(hostCanInteract, host.canInteractAcrossProfiles());
                assertEquals(hostCanRequest, host.canRequestInteractAcrossProfiles());
            }
        } finally {
            apk.delete();
            BlackBoxCore.get().uninstallPackageAsUser(probe, 0);
        }
    }
}
