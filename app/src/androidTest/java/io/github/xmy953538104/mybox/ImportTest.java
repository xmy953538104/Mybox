package io.github.xmy953538104.mybox;

import android.test.AndroidTestCase;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

public final class ImportTest extends AndroidTestCase {
    public void testCopyRejectsEmptyAndPreservesBytes() throws Exception {
        File target = File.createTempFile("import-test", ".apk", getContext().getCacheDir());
        try {
            byte[] data = {0x50, 0x4b, 3, 4};
            MainActivity.copyApk(new ByteArrayInputStream(data), target);
            assertEquals(4, target.length());
            try {
                MainActivity.copyApk(new ByteArrayInputStream(new byte[0]), target);
                fail("Empty imports must fail");
            } catch (IOException expected) {
                assertEquals("文件为空", expected.getMessage());
            }
        } finally { target.delete(); }
    }
}
