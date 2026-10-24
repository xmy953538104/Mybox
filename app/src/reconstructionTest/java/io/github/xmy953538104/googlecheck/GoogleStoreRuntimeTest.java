package io.github.xmy953538104.googlecheck;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.BundledMicrog;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.EngineException;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Collections;

/** Explicit local-only diagnostic. No proprietary APK is a test asset or uploaded to CI. */
public final class GoogleStoreRuntimeTest extends InstrumentationTestCase {
    public void testLocallyProvidedStoreActuallyResumes() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        File input = new File(context.getFilesDir(), "private-inputs/play-store.apk");
        assertTrue("Provide the owned reference APK in this disposable emulator's private-inputs directory", input.isFile());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(input)) {
            byte[] bytes = new byte[65536];
            for (int size; (size = stream.read(bytes)) != -1;) digest.update(bytes, 0, size);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        assertEquals("The local diagnostic input differs from the inspected reference",
                "c56dedb39391b25ab35b25d8045300f0147582026a74d2d8f1e581b2cebcb88a", hash.toString());
        Engine engine = Engine.get();
        BundledMicrog.install();
        if (engine.packages().get("com.android.vending") == null) {
            try { engine.launch("com.android.vending"); fail("Absent virtual Store escaped to the physical system"); }
            catch (EngineException expected) { }
        }
        InstalledPackage store = engine.install(Collections.singletonList(input));
        assertEquals("com.android.vending", store.packageName);
        assertEquals(Collections.singletonList("f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"),
                store.certificateSha256);
        engine.launch(store.packageName);
        GoogleRuntimeTest.ActivityInspector inspector = new GoogleRuntimeTest.ActivityInspector();
        Bundle resumed = inspector.awaitResumed(null, store);
        // An initial RESUMED acknowledgement alone must not hide an immediate startup crash.
        Thread.sleep(3000);
        Bundle live = inspector.resumed(null, store);
        assertNotNull("Store left no current resumed Activity", live);
        assertEquals("Store startup replaced its original guest process", resumed.getInt("pid"), live.getInt("pid"));
        boolean processAlive = false;
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = context.getSystemService(ActivityManager.class).getRunningAppProcesses();
        assertNotNull("Physical process observation is unavailable", processes);
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == live.getInt("pid")) processAlive = process.uid == android.os.Process.myUid()
                    && process.pid != android.os.Process.myPid()
                    && (context.getPackageName() + ":engine_p" + live.getInt("slot")).equals(process.processName);
        }
        assertTrue("Store has no matching physical guest process", processAlive);
        System.out.println("MYLITE_STORE_STARTUP resumed=true; originalProcessAlive=true; authenticatedLogin=false; downloadVerified=false");
    }
}
