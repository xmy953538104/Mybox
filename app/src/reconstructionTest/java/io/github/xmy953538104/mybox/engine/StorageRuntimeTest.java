package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.StatFs;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import org.json.JSONObject;

/** Real framework static/instance volume queries and actual filesystem capacity. */
public final class StorageRuntimeTest extends InstrumentationTestCase {
    public void testGuestVolumeQueriesReachRealStorageAndExternalStatFs() throws Exception {
        Context host = getInstrumentation().getTargetContext(); Engine engine = Engine.get();
        InstalledPackage installed = engine.packages().get("io.github.xmy953538104.mybox.fixture");
        if (installed == null) {
            File apk = new File(host.getCacheDir(), "storage-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                        FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer,0,count);
                }
                installed = engine.install(Collections.singletonList(apk));
            } finally { Files.deleteIfExists(apk.toPath()); }
        }
        File result = new File(installed.dataDir,"files/storage-result.json");
        File retained = new File(host.getFilesDir(),"storage-runtime-result.json");
        Files.deleteIfExists(result.toPath()); Files.deleteIfExists(retained.toPath());
        FixtureRun.prepare(installed,"storage"); engine.launch(installed.packageName);
        long deadline = SystemClock.elapsedRealtime()+15000;
        while (!result.isFile() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50);
        assertTrue("The actual guest storage query produced no result", result.isFile());
        Files.copy(result.toPath(),retained.toPath(),StandardCopyOption.REPLACE_EXISTING);
        JSONObject actual = new JSONObject(new String(Files.readAllBytes(result.toPath()),StandardCharsets.UTF_8));
        assertFalse("Real storage failure: " + actual,actual.has("error"));
        assertEquals(installed.packageName,actual.getString("package"));
        assertTrue(actual.getInt("volumeCount")>0); assertTrue(actual.getBoolean("primary"));
        assertEquals(actual.getBoolean("volumeEmulated"),actual.getBoolean("environmentEmulated"));
        assertEquals(actual.getString("volumeState"),actual.getString("environmentState"));
        assertEquals(new StatFs(host.getFilesDir().getAbsolutePath()).getTotalBytes(),actual.getLong("dataTotal"));
        assertTrue(actual.getLong("allocatable")>=0); assertTrue(actual.getLong("cacheQuota")>=0); assertTrue(actual.getLong("cacheSize")>=0);
        for (String scope : new String[]{"data","external"}) {
            assertTrue(actual.getLong(scope+"Total")>0); assertTrue(actual.getLong(scope+"Available")>=0);
            assertTrue(actual.getLong(scope+"Available")<=actual.getLong(scope+"Total"));
        }
        System.out.println("MYLITE_STORAGE actualVolumes=true staticEnvironment=true realDataAndExternalStatFs=true");
    }
}
