package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.SystemClock;
import android.security.NetworkSecurityPolicy;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import org.json.JSONObject;

public final class NetworkSecurityRuntimeTest extends InstrumentationTestCase {
    public void testGuestXmlTrustPinsTlsAndCleartextAreIndependentOfHost() throws Exception {
        Context host = getInstrumentation().getTargetContext();
        boolean hostCleartext = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted();
        assertFalse("Host manifest policy changed", hostCleartext);
        int firstPid = -1;
        for (String policy : new String[]{"allowed", "restricted"}) {
            String name = "io.github.xmy953538104.mybox.networkfixture." + policy;
            try {
                host.getPackageManager().getPackageInfo(name, 0);
                fail("Network fixture must not be installed in Android PM");
            } catch (android.content.pm.PackageManager.NameNotFoundException expected) { }
            File apk = new File(host.getCacheDir(), "network-policy-" + policy + ".apk");
            InstalledPackage installed;
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open(apk.getName());
                        FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                installed = Engine.get().packages().get(name);
                if (installed == null) installed = Engine.get().install(Collections.singletonList(apk));
            } finally { Files.deleteIfExists(apk.toPath()); }
            assertEquals("Fixture factory resolved against the flavor package",
                    "io.github.xmy953538104.mybox.networkfixture.PolicyFactory",
                    Engine.get().packages().getPackageInfo(name, 0).applicationInfo.appComponentFactory);
            File report = new File(installed.dataDir, "files/network-result.json");
            File activity = new File(installed.dataDir, "files/activity-created");
            Files.deleteIfExists(report.toPath()); Files.deleteIfExists(activity.toPath());
            Engine.get().launch(name);
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while ((!report.isFile() || !activity.isFile()) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            assertTrue(policy + " Application did not produce a network report", report.isFile());
            assertTrue(policy + " real Activity did not start", activity.isFile());
            Files.copy(report.toPath(), new File(host.getFilesDir(), "network-policy-" + policy + ".json").toPath(), StandardCopyOption.REPLACE_EXISTING);
            JSONObject actual = new JSONObject(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
            assertFalse(actual.toString(), actual.has("error"));
            assertEquals(name, actual.getString("package"));
            assertEquals("AndroidNSSP", actual.getString("trustProvider"));
            assertEquals("allowed".equals(policy), actual.getBoolean("cleartextAll"));
            assertEquals("allowed".equals(policy), actual.getBoolean("cleartextOther"));
            for (String check : new String[]{"cleartextAllowed", "customAnchorAccepted", "pinRejected",
                    "untrustedRejected", "defaultTlsHandshake", "httpsDefaultTlsHandshake", "hostPolicyWarmed",
                    "cachedContextReplaced", "cachedHttpsReplaced", "frameworkTrustMatchesTls"})
                assertTrue(policy + ": " + check + " " + actual, actual.getBoolean(check));
            assertTrue(actual.getInt("pid") != android.os.Process.myPid());
            if (firstPid < 0) firstPid = actual.getInt("pid");
            else assertTrue("Different guest policies shared an executor", firstPid != actual.getInt("pid"));
            assertEquals("Guest policy leaked into host process", hostCleartext,
                    NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted());
            System.out.println("MYLITE_NETWORK_POLICY " + actual);
        }
    }
}
