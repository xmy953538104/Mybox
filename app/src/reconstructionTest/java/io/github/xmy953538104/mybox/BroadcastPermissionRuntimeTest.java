package io.github.xmy953538104.mybox;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;

/** Independent APKs, actual signer comparisons, and a real queued update boundary. */
public final class BroadcastPermissionRuntimeTest extends InstrumentationTestCase {
    public void testProtectedBroadcastAcrossRealPackagesAndGeneration() throws Exception {
        InstalledPackage sender = Engine.get().packages().get("io.github.xmy953538104.mybox.fixture");
        if (sender == null) sender = install("engine-fixture.apk");
        InstalledPackage same = install("broadcast-permission-same.apk");
        InstalledPackage different = install("broadcast-permission-different.apk");
        InstalledPackage unrequested = install("broadcast-permission-unrequested.apk");
        assertEquals(sender.certificateSha256, same.certificateSha256);
        assertEquals(sender.certificateSha256, unrequested.certificateSha256);
        assertFalse("Alternate APK must have a genuinely different signer", sender.certificateSha256.equals(different.certificateSha256));
        assertTrue(sender.appId != same.appId && same.appId != different.appId && different.appId != unrequested.appId);
        String permission = sender.packageName + ".permission.BROADCAST";
        PackageInfo missing = Engine.get().packages().getPackageInfo(unrequested.packageName, PackageManager.GET_PERMISSIONS);
        assertFalse(Arrays.asList(missing.requestedPermissions).contains(permission));
        PackageInfo requesting = Engine.get().packages().getPackageInfo(different.packageName, PackageManager.GET_PERMISSIONS);
        assertTrue(Arrays.asList(requesting.requestedPermissions).contains(permission));
        File files = new File(sender.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare permission probe");
        String run = UUID.randomUUID().toString();
        Files.write(new File(files, "broadcast-permission-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(sender, "broadcast-permissions");
        Engine.get().launch(sender.packageName);
        File queued = new File(files, "broadcast-permission-queued-" + run);
        File output = new File(files, "broadcast-permission-result-" + run + ".json");
        long deadline = SystemClock.elapsedRealtime() + 90_000;
        while (!queued.isFile() && !output.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
        if (output.isFile()) fail("Probe failed before generation boundary: " + read(output));
        assertTrue("No package-generation boundary was reached", queued.isFile());
        InstalledPackage updated = install("broadcast-permission-same.apk");
        assertEquals(same.versionCode, updated.versionCode);
        assertEquals(same.certificateSha256, updated.certificateSha256);
        assertFalse("A committed reinstall must change generation", same.baseApk.equals(updated.baseApk));
        Files.write(new File(files, "broadcast-permission-release-" + run).toPath(), new byte[]{1});
        while (!output.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
        assertTrue("Permission probe did not complete", output.isFile());
        JSONObject report = new JSONObject(read(output));
        assertEquals(run, report.getString("run"));
        assertFalse(report.toString(), report.has("failure"));
        for (String check : new String[]{"sameSignerBothDirections", "nullRecipientPermissionPreservesReceiverAuthorization", "actualDifferentSignerDeniedButNormalGranted",
                "unrequestedPermissionDeniedButNormalGranted", "receiverPermissionConstrainsSender", "privateAndDisabledNotDelivered",
                "absentAndNoMatchAcceptedWithoutDelivery", "unorderedActualReceipt", "googleEventSpoofRejected", "queuedOldGenerationNotDelivered"}) {
            assertTrue(check + ": " + report, report.getBoolean(check));
        }
        assertEquals(sender.appId, report.getInt("senderUid"));
        android.util.Log.i("MyboxFixture", "MYLITE_BROADCAST_PERMISSION_REPORT=" + report);
    }

    private InstalledPackage install(String asset) throws Exception {
        Context context = getInstrumentation().getTargetContext();
        File apk = new File(context.getCacheDir(), asset);
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open(asset)) { MainActivity.copyApk(input, apk); }
            return Engine.get().install(Collections.singletonList(apk));
        } finally { apk.delete(); }
    }

    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
}
