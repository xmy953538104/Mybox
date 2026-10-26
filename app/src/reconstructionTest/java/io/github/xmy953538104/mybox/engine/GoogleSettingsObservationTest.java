package io.github.xmy953538104.mybox.engine;

import android.test.InstrumentationTestCase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class GoogleSettingsObservationTest extends InstrumentationTestCase {
    public void testOnlyCompleteTypedUnbackedSettingsAreAccepted() throws Exception {
        File file = File.createTempFile("settings-observation-", ".xml", getInstrumentation().getTargetContext().getCacheDir());
        File backup = new File(file.getPath() + ".bak");
        try {
            write(file, "<map><boolean name='enabled' value='false'/></map>");
            assertEquals(Boolean.FALSE, GoogleSettingsObservation.read(file, "enabled"));
            assertNull(GoogleSettingsObservation.read(file, "absent"));
            write(file, "<map><boolean name='enabled' value='true'/></map>");
            assertEquals(Boolean.TRUE, GoogleSettingsObservation.read(file, "enabled"));
            assertTrue(backup.createNewFile());
            assertNull(GoogleSettingsObservation.read(file, "enabled"));
            assertTrue(backup.delete());
            for (String invalid : new String[]{"<map>", "<wrong/>",
                    "<map><boolean name='enabled' value='1'/></map>",
                    "<map><string name='enabled'>true</string></map>",
                    "<map><boolean name='enabled' value='true'/><boolean name='enabled' value='false'/></map>"}) {
                write(file, invalid); assertNull(GoogleSettingsObservation.read(file, "enabled"));
            }
        } finally {
            if (backup.exists()) assertTrue(backup.delete());
            assertTrue(file.delete());
        }
        assertNull(GoogleSettingsObservation.read(file, "enabled"));
    }

    private static void write(File file, String xml) throws Exception {
        Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));
    }
}
