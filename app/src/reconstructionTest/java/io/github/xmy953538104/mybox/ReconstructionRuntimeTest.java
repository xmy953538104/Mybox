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
    public void testHostCannotForgeMicrogIdentity() {
        Context context = getInstrumentation().getTargetContext();
        android.os.Bundle request = new android.os.Bundle();
        request.putInt("originalUid", android.os.Process.myUid());
        request.putInt("originalPid", android.os.Process.myPid());
        try {
            context.getContentResolver().call(android.net.Uri.parse("content://" + context.getPackageName() + ".identity"),
                    "resolveCallingIdentity", null, request);
            fail("Unregistered host process obtained a privileged microG identity");
        } catch (SecurityException expected) { }
    }

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
            InstalledPackage installed = Engine.get().packages().get(name);
            if (installed == null) installed = Engine.get().install(Collections.singletonList(apk));
            assertEquals(name, installed.packageName);
            File reportFile = new File(installed.dataDir, "files/engine-fixture.json");
            if (reportFile.exists() && !reportFile.delete()) throw new java.io.IOException("Cannot clear fixture report");
            File componentsFile = new File(installed.dataDir, "files/components-result.json");
            if (componentsFile.exists() && !componentsFile.delete()) throw new java.io.IOException("Cannot clear components report");
            File secondFile = new File(installed.dataDir, "files/second-activity.json");
            if (secondFile.exists() && !secondFile.delete()) throw new java.io.IOException("Cannot clear second Activity report");
            FixtureRun.prepare(installed, "components");
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
            android.util.Log.i("MyboxFixture", "MYBOX_GUEST_REPORT=" + report);
            assertFalse(report.toString(), report.has("failure"));
            assertEquals(PackageManager.SIGNATURE_MATCH, report.getInt("signatureSelf"));
            assertEquals(PackageManager.SIGNATURE_UNKNOWN_PACKAGE, report.getInt("signatureUnknown"));
            assertEquals(PackageManager.SIGNATURE_NO_MATCH, report.getInt("signatureFramework"));
            assertEquals(PackageManager.SIGNATURE_MATCH, report.getInt("signatureUidSelf"));
            assertEquals(PackageManager.SIGNATURE_UNKNOWN_PACKAGE, report.getInt("signatureUidUnknown"));
            assertTrue(report.getBoolean("signatureOtherUserRejected"));
            assertTrue(report.getBoolean("invalidQueryAbsent"));
            android.content.pm.PackageInfo hostSigning = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            java.util.List<String> hostHashes = new java.util.ArrayList<>();
            for (android.content.pm.Signature signer : hostSigning.signatures) {
                StringBuilder hex = new StringBuilder();
                for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                hostHashes.add(hex.toString());
            }
            assertEquals(hostHashes.size() == installed.certificateSha256.size()
                    && new java.util.HashSet<>(hostHashes).equals(new java.util.HashSet<>(installed.certificateSha256))
                    ? PackageManager.SIGNATURE_MATCH : PackageManager.SIGNATURE_NO_MATCH, report.getInt("signaturePhysicalHost"));
            assertEquals(report.toString(), name, report.getString("package"));
            assertTrue(report.toString(), report.getBoolean("applicationCreated"));
            assertTrue(report.toString(), report.getBoolean("providerCreated"));
            assertTrue(report.toString(), report.getBoolean("contextFile"));
            assertTrue(report.toString(), report.getBoolean("sqlite"));
            assertTrue("Native path redirection did not execute: " + report, report.getBoolean("hardcodedPath"));
            boolean physicalBatteryPolicy = context.getSystemService(android.os.PowerManager.class)
                    .isIgnoringBatteryOptimizations(context.getPackageName());
            assertEquals("Guest self policy differs from actual host policy", physicalBatteryPolicy, report.getBoolean("batteryQueryOwn"));
            assertEquals("New guest Context bypassed host policy mapping", physicalBatteryPolicy, report.getBoolean("batteryQueryNewContext"));
            java.util.List<String> virtualNames = new java.util.ArrayList<>();
            for (InstalledPackage entry : Engine.get().packages().list()) virtualNames.add(entry.packageName);
            java.util.Collections.sort(virtualNames);
            assertTrue(virtualNames.contains(name)); assertFalse(virtualNames.contains(context.getPackageName()));
            String expectedInventory = new org.json.JSONArray(virtualNames).toString();
            for (String field : new String[]{"installedPackages", "installedApplications", "installedPackagesLongFlags", "installedApplicationsLongFlags"}) {
                if (field.endsWith("LongFlags") && android.os.Build.VERSION.SDK_INT < 33) continue;
                assertEquals("Public guest inventory differs from committed virtual packages: " + field,
                        expectedInventory, report.getJSONArray(field).toString());
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.hardware.biometrics.BiometricManager biometric = context.getSystemService(android.hardware.biometrics.BiometricManager.class);
                assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission("android.permission.USE_BIOMETRIC"));
                assertEquals("Guest biometric capability differs from actual Android", biometric.canAuthenticate(), report.getInt("biometricOwn"));
                assertEquals("New Context bypassed biometric identity mapping", biometric.canAuthenticate(), report.getInt("biometricNewContext"));
                assertEquals("Credential mask changed", biometric.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL), report.getInt("biometricCredential"));
            }
            java.util.List<String> libraries = new java.util.ArrayList<>();
            assertNull("This probe does not publish a virtual static library",
                    Engine.get().packages().get("com.google.android.trichromelibrary"));
            for (android.content.pm.SharedLibraryInfo library : context.getPackageManager().getSharedLibraries(0)) {
                android.content.pm.VersionedPackage declaring = library.getDeclaringPackage();
                String owner = declaring == null ? null : declaring.getPackageName();
                if ("com.google.android.gms".equals(owner) || "com.google.android.gsf".equals(owner)
                        || "com.android.vending".equals(owner) || "com.google.android.trichromelibrary".equals(library.getName())) continue;
                libraries.add(library.getName() + ":" + library.getType() + ":" + library.getLongVersion());
            }
            java.util.Collections.sort(libraries);
            String platformLibraries = new org.json.JSONArray(libraries).toString();
            for (String field : new String[]{"sharedLibraries", "sharedLibrariesStaticFlag", "sharedLibrariesLongFlags"}) {
                if (field.equals("sharedLibrariesLongFlags") && android.os.Build.VERSION.SDK_INT < 33) continue;
                assertEquals("Guest library metadata differs from actual platform inventory: " + field,
                        platformLibraries, report.getJSONArray(field).toString());
            }
            deadline = SystemClock.elapsedRealtime() + 40000;
            while (!componentsFile.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            assertTrue("Guest components did not create a report", componentsFile.isFile());
            JSONObject components = new JSONObject(new String(java.nio.file.Files.readAllBytes(componentsFile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            android.util.Log.i("MyboxFixture", "MYBOX_COMPONENT_REPORT=" + components);
            assertFalse(components.toString(), components.has("failure"));
            assertEquals(components.toString(), name, components.getString("servicePackage"));
            assertEquals(components.toString(), name, components.getString("providerPackage"));
            assertTrue(components.toString(), components.getInt("servicePid") != components.getInt("mainPid"));
            assertEquals(components.toString(), components.getInt("servicePid"), components.getInt("providerPid"));
            assertTrue(components.toString(), components.getBoolean("stopped"));
            assertTrue(components.toString(), components.getBoolean("startBeforeBindObserved"));
            assertEquals(components.toString(), "create\nstart\nbind\nunbind\ndestroy\n", components.getString("lifecycle"));
            deadline = SystemClock.elapsedRealtime() + 10000;
            while (!secondFile.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            assertTrue("Guest did not start its second Activity", secondFile.isFile());
            JSONObject second = new JSONObject(new String(java.nio.file.Files.readAllBytes(secondFile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            android.util.Log.i("MyboxFixture", "MYBOX_ACTIVITY_REPORT=" + second);
            assertEquals(second.toString(), name, second.getString("package"));
            assertEquals(second.toString(), name + ".SecondActivity", second.getString("class"));
            assertEquals(second.toString(), report.getInt("pid"), second.getInt("pid"));
            assertEquals(second.toString(), 42, second.getInt("probe"));
            android.os.Bundle runtime = io.github.xmy953538104.mybox.engine.GuestProbe.describe(name);
            assertEquals(name, runtime.getString("package"));
            assertEquals("BOUND", runtime.getString("state"));
            assertEquals(report.getInt("pid"), runtime.getInt("pid"));
        } finally { apk.delete(); }
    }
}
