package io.github.xmy953538104.mybox;

import android.content.Context;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.json.JSONObject;

public final class PendingIntentRuntimeTest extends InstrumentationTestCase {
    public void testRealBroadcastCapabilitiesAndCompletion() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = Engine.get().packages().get(name);
        if (installed == null) {
            File apk = new File(context.getCacheDir(), "pending-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) { MainActivity.copyApk(input, apk); }
                installed = Engine.get().install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        File report = new File(installed.dataDir, "files/pending-result.json");
        if (report.exists() && !report.delete()) throw new java.io.IOException("Cannot clear PendingIntent report");
        Engine.get().launch(name);
        long deadline = android.os.SystemClock.elapsedRealtime() + 45_000;
        while (!report.isFile() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        assertTrue("PendingIntent fixture did not report", report.isFile());
        JSONObject result = new JSONObject(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
        assertFalse(result.toString(), result.has("failure"));
        for (String check : new String[]{"manifestAsync", "oneShot", "creator", "dynamicAsync", "updateCurrent", "cancel", "immutable", "scope", "unsupportedType"}) {
            assertTrue(check + ": " + result, result.getBoolean(check));
        }
    }
}
