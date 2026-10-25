package io.github.xmy953538104.mybox.engine;

import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class GoogleComponentsBoundaryTest extends InstrumentationTestCase {
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
