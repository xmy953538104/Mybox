package io.github.xmy953538104.mybox;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/** Explicit bootstrap test; preserves the Google guests for subsequent interactive validation. */
public final class BootstrapGoogleTest extends InstrumentationTestCase {
    public void testInstallPinnedMicroGAndOriginalStore() throws Exception {
        Context target = getInstrumentation().getTargetContext();
        ((MyboxApplication) target.getApplicationContext()).requireEngine();
        String storePath = ((android.test.InstrumentationTestRunner) getInstrumentation()).getArguments().getString("playPath");
        assertNotNull("Pass -e playPath with a user-provided APK staged in the host cache", storePath);
        File store = new File(storePath).getCanonicalFile();
        assertTrue("Store must be staged in the private host cache",
                store.getPath().startsWith(target.getCacheDir().getCanonicalPath() + File.separator));
        assertTrue("Store input is absent", store.isFile());
        File microg = new File(target.getCacheDir(), "bootstrap-microg.apk");
        try {
            try (InputStream input = target.getAssets().open("mybox/microg-nohms.apk")) {
                MainActivity.copyApk(input, microg);
            }
            install(microg, target.getPackageName(), "com.google.android.gms");
            assertNotNull("microG settings must remain available when its launcher icon is hidden",
                    BlackBoxCore.getBPackageManager().resolveActivity(
                            new Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage("com.google.android.gms"),
                            0, null, 0));
            PackageInfo info = BlackBoxCore.getBPackageManager().getPackageInfo("com.google.android.gms",
                    PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES, 0);
            assertNotNull(info);
            assertEquals("38918a453d07199354f8b19af05ec6562ced5788", sha1(info.signatures[0].toByteArray()));
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                assertNotNull(info.signingInfo);
                assertEquals("38918a453d07199354f8b19af05ec6562ced5788",
                        sha1(info.signingInfo.getApkContentsSigners()[0].toByteArray()));
            }
            install(store, target.getPackageName(), "com.android.vending");
        } finally { microg.delete(); }
    }

    private void install(File apk, String installer, String expectedPackage) {
        InstallResult result = BlackBoxCore.getBPackageManager().installPackageSetAsUser(
                new String[]{apk.getAbsolutePath()}, InstallOption.installByStorage(), 0, installer);
        assertNotNull(result);
        assertTrue(result.msg, result.success);
        assertEquals(expectedPackage, result.packageName);
    }

    private static String sha1(byte[] certificate) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-1").digest(certificate)) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        }
        return hex.toString();
    }
}
