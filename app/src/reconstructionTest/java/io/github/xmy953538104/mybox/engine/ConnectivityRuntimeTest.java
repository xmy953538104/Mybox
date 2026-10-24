package io.github.xmy953538104.mybox.engine;

import android.net.ConnectivityManager;
import android.net.Network;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.json.JSONObject;

public final class ConnectivityRuntimeTest extends InstrumentationTestCase {
    public void testActualNetworkQueriesAndBothCallbackPathsKeepRealOwnership() throws Exception {
        Engine engine = Engine.get(); String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "connectivity-fixture.apk");
            try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) { Files.copy(input, apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            installed = engine.install(Collections.singletonList(apk));
        }
        Network physical = getInstrumentation().getTargetContext().getSystemService(ConnectivityManager.class).getActiveNetwork();
        assertNotNull("The disposable runner must have actual network connectivity", physical);
        File report = new File(installed.dataDir, "files/connectivity-result.json"); Files.deleteIfExists(report.toPath());
        FixtureRun.prepare(installed, "connectivity"); engine.launch(name);
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (!report.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertTrue("No guest connectivity report", report.isFile());
        JSONObject result = new JSONObject(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
        assertFalse(result.toString(), result.has("failure")); assertEquals(name, result.getString("package"));
        assertEquals(physical.getNetworkHandle(), result.getLong("activeHandle")); assertTrue(result.toString(), result.getInt("networkCount") > 0);
        for (String check : new String[]{"capabilitiesPresent", "foreignPackageDenied", "defaultCallback", "listenCallback"}) assertTrue(check + ": " + result, result.getBoolean(check));
    }
}
