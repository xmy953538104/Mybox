package io.github.xmy953538104.mybox.engine;

import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class GoogleComponentsBoundaryTest extends InstrumentationTestCase {
    public void testStagedAssetSetStillRequiresActualPublisherBeforePublication() throws Exception {
        android.content.Context host = getInstrumentation().getTargetContext();
        java.util.List<File> paths;
        try (GoogleStoreAssets.Copied staged = GoogleStoreAssets.fromAssets(new GoogleStoreAssets.Assets() {
            @Override public String[] list(String path) {
                return path.equals("mybox") ? new String[]{"google-play"} : new String[]{"base.apk"};
            }
            @Override public InputStream open(String path) throws java.io.IOException {
                return getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
            }
        }, host.getCacheDir())) {
            paths = staged.files();
            assertEquals(1, paths.size());
            File previous = Engine.get().packages().get("com.android.vending") == null ? null
                    : Engine.get().packages().get("com.android.vending").baseApk;
            try {
                Engine.get().packages().installWithPublisher(paths, "com.android.vending", InstallerPolicy::isGoogleStore);
                fail("Staging granted the non-Google fixture a Store role");
            } catch (java.io.IOException | SecurityException expected) { }
            assertEquals(previous, Engine.get().packages().get("com.android.vending") == null ? null
                    : Engine.get().packages().get("com.android.vending").baseApk);
        }
        for (File path : paths) { assertFalse(path.exists()); assertFalse(path.getParentFile().exists()); }
    }

    public void testSignedNonGoogleApkCannotBeAcceptedAsStore() throws Exception {
        File apk = File.createTempFile("not-google-play-", ".apk", getInstrumentation().getTargetContext().getCacheDir());
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                Files.copy(input, apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try { GoogleComponents.verifyStoreSigner(apk); fail("A valid APK with a different signer was accepted as Google Play"); }
            catch (SecurityException expected) { }
        } finally { apk.delete(); }
    }
}
