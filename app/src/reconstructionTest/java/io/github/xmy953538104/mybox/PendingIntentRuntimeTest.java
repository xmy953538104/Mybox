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
import java.util.UUID;
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
        File files = new File(installed.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare PendingIntent probe request");
        String[] runs = {UUID.randomUUID().toString(), UUID.randomUUID().toString()};
        File request = new File(files, "pending-run");
        for (String runId : runs) {
            Files.write(request.toPath(), runId.getBytes(StandardCharsets.UTF_8));
            Engine.get().launch(name);
            long acceptedBy = android.os.SystemClock.elapsedRealtime() + 10_000;
            while (request.exists() && android.os.SystemClock.elapsedRealtime() < acceptedBy) Thread.sleep(10);
            assertFalse("Guest did not consume probe run " + runId, request.exists());
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + 45_000;
        for (String runId : runs) {
            File report = new File(files, "pending-result-" + runId + ".json");
            while (!report.isFile() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            assertTrue("PendingIntent fixture did not report run " + runId, report.isFile());
            JSONObject result = new JSONObject(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
            assertEquals(runId, result.getString("runId"));
            assertFalse(result.toString(), result.has("failure"));
            for (String check : new String[]{"manifestAsync", "oneShot", "creator", "dynamicAsync", "updateCurrent", "cancel", "immutable", "scope", "unsupportedType"}) {
                assertTrue(check + ": " + result, result.getBoolean(check));
            }
        }
    }
}
