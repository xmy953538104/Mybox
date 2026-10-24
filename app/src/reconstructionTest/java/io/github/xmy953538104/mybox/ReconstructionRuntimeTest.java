package io.github.xmy953538104.mybox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import org.json.JSONObject;

/** Requires an emulator or explicitly selected test device; never installs the guest in Android PM. */
public final class ReconstructionRuntimeTest extends InstrumentationTestCase {
    public void testGuestFilesSqliteAndNativePath() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ((MyboxApplication) context.getApplicationContext()).requireEngine();
        String name = "io.github.xmy953538104.mybox.fixture";
        try {
            context.getPackageManager().getPackageInfo(name, 0);
            fail("Fixture is installed in the system; container-only proof would be ambiguous");
        } catch (PackageManager.NameNotFoundException expected) { }
        File apk = new File(context.getCacheDir(), "engine-fixture.apk");
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                MainActivity.copyApk(input, apk);
            }
            InstalledPackage installed = Engine.get().install(Collections.singletonList(apk));
            assertEquals(name, installed.packageName);
            File reportFile = new File(installed.dataDir, "files/engine-fixture.json");
            if (reportFile.exists() && !reportFile.delete()) throw new java.io.IOException("Cannot clear fixture report");
            Engine.get().launch(name);
            long deadline = SystemClock.elapsedRealtime() + 30000;
            while (!reportFile.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            assertTrue("Guest did not create a report", reportFile.isFile());
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(reportFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            }
            JSONObject report = new JSONObject(bytes.toString("UTF-8"));
            assertFalse(report.toString(), report.has("failure"));
            assertEquals(report.toString(), name, report.getString("package"));
            assertTrue(report.toString(), report.getBoolean("applicationCreated"));
            assertTrue(report.toString(), report.getBoolean("providerCreated"));
            assertTrue(report.toString(), report.getBoolean("contextFile"));
            assertTrue(report.toString(), report.getBoolean("sqlite"));
            assertTrue("Native path redirection did not execute: " + report, report.getBoolean("hardcodedPath"));
        } finally { apk.delete(); }
    }
}
