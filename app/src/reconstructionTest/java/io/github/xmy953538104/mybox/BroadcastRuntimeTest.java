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

/** Public Context/BroadcastReceiver APIs, including a real cross-process manifest receiver. */
public final class BroadcastRuntimeTest extends InstrumentationTestCase {
    public void testOrderedManifestResultsAbortAndExplicitDelivery() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = Engine.get().packages().get(name);
        if (installed == null) {
            File apk = new File(context.getCacheDir(), "broadcast-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) { MainActivity.copyApk(input, apk); }
                installed = Engine.get().install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        File files = new File(installed.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare broadcast probe");
        String run = java.util.UUID.randomUUID().toString();
        Files.write(new File(files, "broadcast-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(installed, "broadcast");
        Engine.get().launch(name);
        File output = new File(files, "broadcast-result-" + run + ".json");
        long deadline = android.os.SystemClock.elapsedRealtime() + 100_000;
        while (!output.isFile() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertTrue("Broadcast probe did not complete", output.isFile());
        JSONObject report = new JSONObject(new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8));
        assertEquals(run, report.getString("run"));
        assertFalse(report.toString(), report.has("failure"));
        for (String check : new String[]{"priorityAndAsyncResults", "abortAndFinalCallback", "noAbortFlag",
                "registeredOnly", "explicitWithoutAction", "emptyFinalCallback", "unorderedManifest", "permissionFiltersReceiver",
                "timeoutAdvancesAndLateFinishIsolated"}) {
            assertTrue(check + ": " + report, report.getBoolean(check));
        }
        android.util.Log.i("MyboxFixture", "MYLITE_BROADCAST_REPORT=" + report);
    }
}
