package io.github.xmy953538104.mybox;

import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.json.JSONObject;

public final class AccountRuntimeTest extends InstrumentationTestCase {
    public void testRealVirtualAuthenticatorAndAccountCache() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ((MyboxApplication) context.getApplicationContext()).requireEngine();
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = installIfAbsent(name, "engine-fixture.apk");
        installIfAbsent("io.github.xmy953538104.mybox.uiconsumer", "account-ui-consumer.apk");
        File reportFile = new File(installed.dataDir, "files/account-result.json");
        if (reportFile.exists() && !reportFile.delete()) throw new java.io.IOException("Cannot clear account fixture report");
        FixtureRun.prepare(installed, "accounts");
        Engine.get().launch(name);
        long deadline = SystemClock.elapsedRealtime() + 90_000;
        while (!reportFile.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        assertTrue("Virtual account fixture did not produce a report", reportFile.isFile());
        JSONObject report = new JSONObject(new String(Files.readAllBytes(reportFile.toPath()), StandardCharsets.UTF_8));
        assertFalse(report.toString(), report.has("failure"));
        for (String check : new String[]{"trustedDirectory", "sameInstanceCache", "accountListener", "noAmbientPermission", "authenticatorBinder",
                "authenticatorFeatures", "authenticatorError", "ownerAcl", "userAndPackageAcl", "asyncAdd", "invalidUiIntent", "interactiveAdd", "removed",
                "ownerTargetProjection", "legacyVisibilityDefaults"}) {
            assertTrue(check + ": " + report, report.getBoolean(check));
        }
    }

    private InstalledPackage installIfAbsent(String name, String asset) throws Exception {
        InstalledPackage installed = Engine.get().packages().get(name);
        if (installed != null) return installed;
        File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "account-probe-" + asset);
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open(asset)) { MainActivity.copyApk(input, apk); }
            return Engine.get().install(Collections.singletonList(apk));
        } finally { apk.delete(); }
    }
}
