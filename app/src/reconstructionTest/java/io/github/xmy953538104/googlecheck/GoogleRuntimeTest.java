package io.github.xmy953538104.googlecheck;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.BundledMicrog;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Properties;

/** Explicitly run only by the workflow that bundles the matching source-built microG artifact. */
public final class GoogleRuntimeTest extends InstrumentationTestCase {
    public void testPinnedMicrogLocalSelfAndRemoteBinderBoundary() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        BundledMicrog.install();
        java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
        android.os.IBinder[] endpoint = new android.os.IBinder[1];
        android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                endpoint[0] = binder; connected.countDown();
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) { }
        };
        android.content.Intent intent = new android.content.Intent().setClassName(context.getPackageName(),
                "io.github.xmy953538104.mybox.engine.MicrogIdentityProbeService");
        assertTrue("Debug identity helper service was not bound", context.bindService(intent, connection, Context.BIND_AUTO_CREATE));
        try {
            assertTrue("Debug identity helper service did not connect", connected.await(20, java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(endpoint[0]);
            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            android.os.Bundle report;
            try {
                data.writeInterfaceToken("mybox.synthetic.MicrogSelfIdentity");
                assertTrue("Identity helper rejected the test transaction",
                        endpoint[0].transact(android.os.IBinder.FIRST_CALL_TRANSACTION, data, reply, 0));
                reply.readException();
                report = reply.readBundle(getClass().getClassLoader());
            } finally { reply.recycle(); data.recycle(); }
            assertNotNull(report);
            assertNull("Identity helper failed: " + report.getString("failure"), report.getString("failure"));
            for (String field : new String[]{"trustedHelperLoaded", "samePhysicalUid", "differentPhysicalPid",
                    "localRawIdentity", "localSelf", "remoteRegistryRejected", "incomingIdentityRestored"}) {
                assertTrue("Actual pinned helper boundary failed: " + field, report.getBoolean(field));
            }
            assertFalse("Unregistered remote caller returned normally", report.getBoolean("remoteUnexpectedlyReturned"));
            System.out.println("MYLITE_PINNED_MICROG_SELF_IDENTITY trustedHelper=true; localSelf=true; sameUidRemoteRejected=true; identityRestored=true");
        } finally { context.unbindService(connection); }
    }

    @android.annotation.SuppressLint("MissingPermission")
    public void testHostNormalPermissionsReachRealConnectivityAndWakeLock() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE));
        assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(android.Manifest.permission.WAKE_LOCK));
        android.net.ConnectivityManager connectivity = context.getSystemService(android.net.ConnectivityManager.class);
        assertNotNull(connectivity);
        android.net.NetworkInfo network = connectivity.getActiveNetworkInfo();

        android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
        assertNotNull(power);
        String tag = "MyliteHostPowerBoundary";
        android.os.PowerManager.WakeLock lock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, tag);
        lock.setReferenceCounted(false);
        try {
            lock.acquire(30000);
            assertTrue("The real PowerManager did not hold the test lock", lock.isHeld());
            assertTrue("The OS has no wake-lock record for the real host process", hasPhysicalWakeLock(tag));
        } finally {
            if (lock.isHeld()) lock.release();
        }
        assertFalse("The test lock remained held after release", lock.isHeld());
        assertFalse("The OS retained the released test wake lock", hasPhysicalWakeLock(tag));
        System.out.println("MYLITE_HOST_NORMAL_PERMISSIONS connectivityQuery=true; networkPresent=" + (network != null)
                + "; networkConnected=" + (network != null && network.isConnected())
                + "; wakeLockOsRecord=true; wakeLockReleased=true");
    }

    private boolean hasPhysicalWakeLock(String tag) throws Exception {
        StringBuilder dump = new StringBuilder();
        try (java.io.Reader reader = new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                getInstrumentation().getUiAutomation().executeShellCommand("dumpsys -t 5 power")), java.nio.charset.StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                assertTrue("Power diagnostics exceeded the bounded test buffer", dump.length() + count <= 1048576);
                dump.append(buffer, 0, count);
            }
        }
        assertTrue("The OS did not return power diagnostics", dump.indexOf("POWER MANAGER (dumpsys power)") >= 0);
        assertTrue("The OS did not return its wake-lock records", dump.indexOf("Wake Locks: size=") >= 0);
        for (String line : dump.toString().split("\n")) {
            if (line.trim().startsWith("PARTIAL_WAKE_LOCK") && line.contains("'" + tag + "'")) {
                assertTrue("The test wake lock has an unexpected physical owner",
                        line.contains("(uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid() + ")"));
                return true;
            }
        }
        return false;
    }

    public void testPinnedMicrogSignatureViewDoesNotReplaceRealSigner() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Properties pins = new Properties();
        try (InputStream source = context.getAssets().open("mybox/trusted-microg.properties")) { pins.load(source); }
        InstalledPackage installed = BundledMicrog.install();
        assertEquals(Collections.singletonList(pins.getProperty("gms.cert.sha256")), installed.certificateSha256);
        int flags = PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo real = Engine.get().packages().getPackageInfo(installed.packageName, flags);
        assertEquals(pins.getProperty("gms.cert.sha256"), hash("SHA-256", real.signatures[0]));
        Class<?> view = Class.forName("io.github.xmy953538104.mybox.engine.GoogleSignatureView");
        Method apply = view.getDeclaredMethod("apply", Engine.class, PackageInfo.class, int.class);
        apply.setAccessible(true);
        PackageInfo compatible = (PackageInfo) apply.invoke(null, Engine.get(), real, flags);
        String google = "38918a453d07199354f8b19af05ec6562ced5788";
        assertEquals(google, hash("SHA-1", compatible.signatures[0]));
        assertEquals(google, hash("SHA-1", compatible.signingInfo.getApkContentsSigners()[0]));
        assertEquals(google, hash("SHA-1", compatible.signingInfo.getSigningCertificateHistory()[0]));
        PackageInfo reloaded = Engine.get().packages().getPackageInfo(installed.packageName, flags);
        assertEquals(pins.getProperty("gms.cert.sha256"), hash("SHA-256", reloaded.signatures[0]));
        assertEquals(pins.getProperty("gms.cert.sha256"), hash("SHA-256", reloaded.signingInfo.getApkContentsSigners()[0]));
        assertEquals(context.getApplicationInfo().uid, compatible.applicationInfo.uid);
        System.out.println("MYBOX_PINNED_MICROG_SIGNATURE_VIEW=passed; Google network/account/application behavior remains a separate check");
    }

    public void testSourceBuiltMicrogSettingsActuallyResumes() throws Exception {
        BundledMicrog.install();
        Engine.get().launch("com.google.android.gms");
        long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
        boolean visible = false;
        String hostPackage = getInstrumentation().getTargetContext().getPackageName();
        String lastWindow = "No active accessibility window";
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayDeque<>();
                nodes.add(root);
                int count = 0;
                StringBuilder window = new StringBuilder();
                while (!nodes.isEmpty() && count++ < 1000) {
                    android.view.accessibility.AccessibilityNodeInfo node = nodes.removeFirst();
                    CharSequence text = node.getText();
                    if (text != null && window.length() < 3000) {
                        window.append(node.getPackageName()).append(':').append(text).append('\n');
                    }
                    if (hostPackage.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                            && text != null && "microG Settings".equalsIgnoreCase(text.toString())) visible = true;
                    for (int i = 0; i < node.getChildCount(); i++) {
                        android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) nodes.add(child);
                    }
                    node.recycle();
                }
                while (!nodes.isEmpty()) nodes.removeFirst().recycle();
                lastWindow = window.toString();
            }
            if (visible) break;
            Thread.sleep(300);
        }
        android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
        if (screenshot != null) {
            try (java.io.FileOutputStream output = getInstrumentation().getTargetContext().openFileOutput("ci-microg-settings.png", Context.MODE_PRIVATE)) {
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
            } finally { screenshot.recycle(); }
        }
        assertTrue("The pinned microG Settings UI did not become visible in the reconstructed guest. Active window: " + lastWindow, visible);
        android.os.Bundle guest = io.github.xmy953538104.mybox.engine.GuestProbe.describe("com.google.android.gms");
        assertEquals("com.google.android.gms", guest.getString("package"));
        assertEquals("BOUND", guest.getString("state"));
        assertEquals("com.google.android.gms", guest.getString("resumed_package"));
        assertEquals("org.microg.gms.ui.MainSettingsActivity", guest.getString("resumed_activity"));
        assertTrue(guest.getInt("pid") != android.os.Process.myPid());
        android.util.Log.i("MyboxFixture", "MYBOX_PINNED_MICROG_RUNTIME=" + guest);
        android.util.Log.i("MyboxFixture", "MYBOX_PINNED_MICROG_SETTINGS_VISIBLE=passed; real Google login/download/push remain separate checks");
    }

    private static String hash(String algorithm, Signature signature) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance(algorithm).digest(signature.toByteArray())) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
