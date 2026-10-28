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
    public void testVisibleSwitchAndActivityExportResultWriteARealZip() throws Exception {
        android.content.Context host = getInstrumentation().getTargetContext();
        boolean before = EngineDiagnostics.enabled(); EngineDiagnostics.setEnabled(false);
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(new android.content.Intent(host, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        java.io.File destination = new java.io.File(host.getCacheDir(), "diagnostic-ui-" + java.util.UUID.randomUUID() + ".zip");
        try {
            getInstrumentation().runOnMainSync(() -> { android.widget.Switch control = debugSwitch(activity.getWindow().getDecorView()); assertNotNull(control); assertFalse(control.isChecked()); control.performClick(); });
            awaitUi(activity, () -> EngineDiagnostics.enabled() && debugSwitch(activity.getWindow().getDecorView()).isEnabled());
            getInstrumentation().runOnMainSync(() -> activity.onActivityResult(4, android.app.Activity.RESULT_OK, new android.content.Intent().setData(android.net.Uri.fromFile(destination))));
            awaitUi(activity, () -> containsText(activity.getWindow().getDecorView(), "诊断日志已导出"));
            assertTrue(destination.isFile());
            String text = readZip(java.nio.file.Files.readAllBytes(destination.toPath()));
            String revision = host.getPackageManager().getApplicationInfo(host.getPackageName(), android.content.pm.PackageManager.GET_META_DATA).metaData.getString("mybox.source-revision");
            assertTrue("CI build provenance is missing", revision.matches("[0-9a-f]{40}"));
            assertTrue(text.contains("\"build\":\"" + revision + "\""));
            getInstrumentation().runOnMainSync(() -> debugSwitch(activity.getWindow().getDecorView()).performClick());
            awaitUi(activity, () -> !EngineDiagnostics.enabled() && debugSwitch(activity.getWindow().getDecorView()).isEnabled());
        } finally {
            getInstrumentation().runOnMainSync(activity::finish); EngineDiagnostics.setEnabled(before);
            if (destination.exists()) assertTrue(destination.delete());
        }
    }
    private void awaitUi(MainActivity activity, java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 15000;
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        while (!ready.get() && android.os.SystemClock.elapsedRealtime() < until) {
            getInstrumentation().runOnMainSync(() -> { try { ready.set(condition.call()); } catch (Exception failure) { throw new AssertionError(failure); } });
            if (!ready.get()) Thread.sleep(50);
        }
        assertTrue("Diagnostic UI operation did not complete", ready.get());
    }
    private static android.widget.Switch debugSwitch(android.view.View view) {
        if (view instanceof android.widget.Switch) return (android.widget.Switch) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { android.widget.Switch found = debugSwitch(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    private static boolean containsText(android.view.View view, String text) {
        if (view instanceof android.widget.TextView && ((android.widget.TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), text)) return true;
        }
        return false;
    }
    private String exported(android.content.Context host) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); EngineDiagnostics.export(host, output);
        assertTrue(output.size() < 2 * 1024 * 1024);
        return readZip(output.toByteArray());
    }
    static String readZip(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
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
