package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;

public final class SettingsRuntimeTest extends InstrumentationTestCase {
    public void testRealGuestSettingsReadWriteDeleteCrossVmNeverMutatesPhysicalSettings() throws Exception {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        InstalledPackage fixture = engine.packages().get("io.github.xmy953538104.mybox.fixture"); assertNotNull(fixture);
        String run = UUID.randomUUID().toString(), name = "mylite_settings_" + run;
        assertNull(Settings.Secure.getString(host.getContentResolver(), name));
        assertNull(Settings.Global.getString(host.getContentResolver(), name));
        File files = new File(fixture.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot create Settings fixture directory");
        Files.write(new File(files, "settings-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(fixture, "settings"); engine.launch(fixture.packageName);
        File result = new File(files, "settings-result-" + run + ".json"); long end = SystemClock.elapsedRealtime() + 30000;
        while (!result.isFile() && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
        assertTrue("Real guest produced no Settings result", result.isFile());
        JSONObject report = new JSONObject(new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8));
        assertEquals(run, report.getString("run")); assertFalse(report.toString(), report.has("failure"));
        assertTrue(report.getBoolean("crossProcessReadWriteDelete")); assertTrue(report.getBoolean("foreignUserRejected"));
        assertTrue(report.getInt("pid") != android.os.Process.myPid()); assertTrue(report.getInt("readerPid") != report.getInt("pid"));
        assertNull("Guest secure write reached the actual system", Settings.Secure.getString(host.getContentResolver(), name));
        assertNull("Guest global write reached the actual system", Settings.Global.getString(host.getContentResolver(), name));
    }

    public void testHostCannotImpersonateRegisteredSettingsWriter() {
        Bundle request = EngineProtocol.response(); request.putInt("user", 0);
        request.putString("operation", "put"); request.putString("domain", "secure");
        request.putString("name", "mylite_settings_unregistered_" + UUID.randomUUID());
        request.putString("package", "com.android.vending"); request.putString("value", "forged");
        try { EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.SETTINGS, request); fail("Unregistered host impersonated Google Store"); }
        catch (EngineException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("SecurityException")); }
    }
}
