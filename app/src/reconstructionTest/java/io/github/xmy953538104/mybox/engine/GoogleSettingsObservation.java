package io.github.xmy953538104.mybox.engine;

import android.os.SystemClock;
import android.util.Xml;
import java.io.File;
import java.io.FileInputStream;
import org.xmlpull.v1.XmlPullParser;

/** Read-only disk observation; never opens a guest SharedPreferences cache or prints its contents. */
public final class GoogleSettingsObservation {
    public static Boolean read(File file, String key) {
        if (!file.isFile() || file.length() > 256 * 1024 || new File(file.getPath() + ".bak").exists()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser(); parser.setInput(input, "UTF-8");
            Boolean value = null; boolean root = false;
            for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event != XmlPullParser.START_TAG) continue;
                if (parser.getDepth() == 1) {
                    if (root || !"map".equals(parser.getName())) return null;
                    root = true;
                } else if (parser.getDepth() == 2 && key.equals(parser.getAttributeValue(null, "name"))) {
                    if (value != null || !"boolean".equals(parser.getName())) return null;
                    String raw = parser.getAttributeValue(null, "value");
                    if (!"true".equals(raw) && !"false".equals(raw)) return null;
                    value = Boolean.valueOf(raw);
                }
            }
            return root && !new File(file.getPath() + ".bak").exists() ? value : null;
        } catch (Exception unreadable) { return null; }
    }

    public static void await(File file, String key, boolean expected) throws Exception {
        long until = SystemClock.elapsedRealtime() + 10_000;
        do {
            if (Boolean.valueOf(expected).equals(read(file, key))) return;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < until);
        throw new AssertionError("Actual Google settings flag was not durably " + expected + ": " + key);
    }

    private GoogleSettingsObservation() { }
}
