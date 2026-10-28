package io.github.xmy953538104.mybox;

import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.EngineDiagnostics;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;

public final class DiagnosticExportTest extends InstrumentationTestCase {
    public void testHostAndSeparateServerPersistBoundedSafeEventsAndDisableWithoutDeletingData() throws Exception {
        android.content.Context host = getInstrumentation().getTargetContext();
        ((MyboxApplication) host.getApplicationContext()).requireEngine();
        boolean before = EngineDiagnostics.enabled();
        try {
            EngineDiagnostics.setEnabled(true); EngineDiagnostics.clear();
            EngineDiagnostics.failure("fixture", "message_redaction", 17, new IllegalArgumentException("PrivateSecretCanary987"));
            Engine.get().captureDiagnostics();
            String data = exported(host);
            assertTrue(data.contains("\"area\":\"ipc\""));
            assertTrue("Actual engine server did not record its own process", data.contains("\"role\":\"engine_server\""));
            assertTrue(data.contains("IllegalArgumentException"));
            assertFalse(data.contains("PrivateSecretCanary987"));
            EngineDiagnostics.setEnabled(false);
            EngineDiagnostics.event("fixture", "off_canary", 1, 0);
            Engine.get().captureDiagnostics();
            assertFalse(exported(host).contains("off_canary"));
            assertTrue(exported(host).contains("message_redaction"));
        } finally { EngineDiagnostics.setEnabled(before); }
    }
    private String exported(android.content.Context host) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); EngineDiagnostics.export(host, output);
        assertTrue(output.size() < 2 * 1024 * 1024);
        StringBuilder text = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            byte[] buffer = new byte[4096];
            while (zip.getNextEntry() != null) {
                ByteArrayOutputStream item = new ByteArrayOutputStream(); int count;
                while ((count = zip.read(buffer)) != -1) item.write(buffer, 0, count);
                text.append(new String(item.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }
}
