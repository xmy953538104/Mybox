package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.test.InstrumentationTestCase;

public final class GuestDiagnosticBoundaryTest extends InstrumentationTestCase {
    public void testHostActivityWaitCaptureRequiresCurrentScopeAndReturnsNoCapability() throws Exception {
        Engine engine = Engine.get();
        String name = "io.github.xmy953538104.mybox.fixture";
        io.github.xmy953538104.mybox.engine.packages.InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            java.io.File apk = java.io.File.createTempFile("diagnostic-fixture-", ".apk", getInstrumentation().getTargetContext().getCacheDir());
            try {
                try (java.io.InputStream stream = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                    java.nio.file.Files.copy(stream, apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                installed = engine.install(java.util.Collections.singletonList(apk));
            } finally { java.nio.file.Files.deleteIfExists(apk.toPath()); }
        }
        // Arrange a real existing reservation; the diagnostic operation itself must never acquire one.
        Bundle acquire = EngineProtocol.response(); acquire.putString("package", name); acquire.putString("process", name);
        Bundle process = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.ACQUIRE, acquire);
        IBinder client = process.getBinder("client"); assertNotNull(client); assertTrue(client.isBinderAlive());
        Bundle scope = EngineProtocol.response(); scope.putString("operation", "diagnose_activity_wait");
        scope.putString("package", name); scope.putString("package_generation", installed.baseApk.getAbsolutePath());
        Bundle absent = new Bundle(scope); absent.remove("package"); rejected(absent, "scope");
        Bundle foreign = new Bundle(scope); foreign.putString("package", "org.mybox.uninstalled.diagnostic"); rejected(foreign, "installation");
        Bundle stale = new Bundle(scope); stale.putString("package_generation", installed.baseApk.getAbsolutePath() + ".stale"); rejected(stale, "installation");
        Bundle protocol = new Bundle(scope); protocol.putInt("protocol", EngineProtocol.VERSION + 1); rejected(protocol, "protocol");
        Bundle result = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, scope);
        assertEquals(2, result.keySet().size()); assertEquals(EngineProtocol.VERSION, result.getInt("protocol"));
        assertTrue(result.getInt("requested_processes") > 0);
        assertFalse(result.containsKey("client")); assertFalse(result.containsKey("diagnostic_token"));
        assertTrue("Read-only stack capture killed the existing guest", client.isBinderAlive());
    }

    private void rejected(Bundle request, String boundary) {
        try {
            EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, request);
            fail("Invalid Activity diagnostic request was accepted");
        } catch (EngineException expected) { assertTrue(expected.toString(), expected.getMessage().contains(boundary)); }
    }

    public void testOnewayCaptureRequiresTheExactReservationCapability() {
        IBinder token = new Binder(); Bundle ticket = new Bundle(); ticket.putBinder("diagnostic_token", token);
        GuestRuntime.requireDiagnosticTicket(token, ticket, IBinder.FLAG_ONEWAY);
        try { GuestRuntime.requireDiagnosticTicket(new Binder(), ticket, IBinder.FLAG_ONEWAY); fail("Another reservation's ticket was accepted"); }
        catch (SecurityException expected) { }
        try { GuestRuntime.requireDiagnosticTicket(token, new Bundle(), IBinder.FLAG_ONEWAY); fail("Missing ticket was accepted"); }
        catch (SecurityException expected) { }
        try { GuestRuntime.requireDiagnosticTicket(null, ticket, IBinder.FLAG_ONEWAY); fail("Unprepared guest admitted diagnostics"); }
        catch (SecurityException expected) { }
        try { GuestRuntime.requireDiagnosticTicket(token, ticket, 0); fail("Blocking diagnostic IPC was accepted"); }
        catch (SecurityException expected) { }
    }
}
