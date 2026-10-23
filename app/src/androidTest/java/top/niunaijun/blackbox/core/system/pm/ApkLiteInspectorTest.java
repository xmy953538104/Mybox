package top.niunaijun.blackbox.core.system.pm;

import android.test.AndroidTestCase;
import java.io.File;
import java.io.FileOutputStream;

public final class ApkLiteInspectorTest extends AndroidTestCase {
    public void testCorruptApkProducesAnExplicitValidationError() throws Exception {
        File apk = File.createTempFile("invalid-fixture-", ".apk", getContext().getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(apk)) { output.write(new byte[]{1, 2, 3}); }
            try { new ApkLiteInspector(apk); fail("Corrupt APK was accepted"); }
            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().startsWith("Invalid APK:"));
                assertNotNull(expected.getCause());
            }
        } finally { apk.delete(); }
    }
}
