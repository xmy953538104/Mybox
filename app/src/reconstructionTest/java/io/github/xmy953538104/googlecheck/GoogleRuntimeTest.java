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
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayDeque<>();
                nodes.add(root);
                int count = 0;
                while (!nodes.isEmpty() && count++ < 1000) {
                    android.view.accessibility.AccessibilityNodeInfo node = nodes.removeFirst();
                    CharSequence text = node.getText();
                    if ("com.google.android.gms".contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                            && text != null && text.toString().toLowerCase(java.util.Locale.ROOT).contains("microg")) visible = true;
                    for (int i = 0; i < node.getChildCount(); i++) {
                        android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) nodes.add(child);
                    }
                    node.recycle();
                }
                while (!nodes.isEmpty()) nodes.removeFirst().recycle();
            }
            if (visible) break;
            Thread.sleep(300);
        }
        assertTrue("The pinned microG Settings UI did not become visible in the reconstructed guest", visible);
        android.util.Log.i("MyboxFixture", "MYBOX_PINNED_MICROG_SETTINGS_VISIBLE=passed; real Google login/download/push remain separate checks");
    }

    private static String hash(String algorithm, Signature signature) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance(algorithm).digest(signature.toByteArray())) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
