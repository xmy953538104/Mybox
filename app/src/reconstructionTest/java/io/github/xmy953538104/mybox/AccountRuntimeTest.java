package io.github.xmy953538104.mybox;

import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.json.JSONObject;

public final class AccountRuntimeTest extends InstrumentationTestCase {
    public void testRealVirtualAuthenticatorAndAccountCache() throws Exception {
        Context host=getInstrumentation().getTargetContext();
        ((MyboxApplication)host.getApplicationContext()).requireEngine();
        boolean before=io.github.xmy953538104.mybox.engine.EngineDiagnostics.enabled();
        long started=System.currentTimeMillis();byte[] snapshot;
        try {
            io.github.xmy953538104.mybox.engine.EngineDiagnostics.setEnabled(true);
            runRealAccountFixture();
        } finally {
            try {
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                io.github.xmy953538104.mybox.engine.EngineDiagnostics.export(host,bytes);snapshot=bytes.toByteArray();
                Files.write(new File(host.getFilesDir(),"account-callback-diagnostic-export.zip").toPath(),snapshot);
            } finally { io.github.xmy953538104.mybox.engine.EngineDiagnostics.setEnabled(before); }
        }
        assertAuthenticatedCallbacks(snapshot,started);
    }

    private void assertAuthenticatedCallbacks(byte[] snapshot,long started) throws Exception {
        String text=DiagnosticExportTest.readZip(snapshot);
        for(String secret:new String[]{"fixture-local","fixture-only-password","fixture-token:","fixture-key","fixture-new"})
            assertFalse("Account content leaked into diagnostics",text.contains(secret));
        java.util.List<JSONObject> callbacks=new java.util.ArrayList<>();
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(snapshot))) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
                while((count=zip.read(buffer))!=-1) bytes.write(buffer,0,count);
                if(!entry.getName().endsWith(".jsonl")) continue;
                for(String line:new String(bytes.toByteArray(),StandardCharsets.UTF_8).split("\n")) {
                    if(line.isEmpty()) continue;JSONObject row=new JSONObject(line);
                    if(row.getLong("wall_ms")<started || !row.getString("guest").equals("io.github.xmy953538104.mybox.fixture")
                            || !row.getString("area").equals("account_callback")) continue;
                    callbacks.add(row);
                }
            }
        }
        java.util.Set<String> token=new java.util.HashSet<>(),error=new java.util.HashSet<>(),ui=new java.util.HashSet<>(),added=new java.util.HashSet<>();
        for(JSONObject row:callbacks) {
            String key=row.getInt("pid")+"/"+row.getString("epoch")+"/"+row.getLong("id");
            String op=row.getString("operation");int kind=row.getInt("value");
            if(op.equals("getAuthToken.result_forwarded") && kind==37) token.add(key);
            if(op.equals("getAuthToken.error_forwarded") && kind==android.accounts.AccountManager.ERROR_CODE_BAD_ARGUMENTS) error.add(key);
            if(op.equals("addAccount.result_forwarded") || op.equals("addAccountAsUser.result_forwarded")) {
                if(kind==3) ui.add(key);if(kind==33) added.add(key);
            }
        }
        assertFalse("Actual token callback absent from exported journal",token.isEmpty());
        assertFalse("Actual error callback absent from exported journal",error.isEmpty());
        ui.retainAll(added);assertFalse("Interactive result and final account did not retain one response trace",ui.isEmpty());
        System.out.println("MYLITE_ACCOUNT_CALLBACK startedMs="+started+" realTokenTraces="+token.size()+" realErrorTraces="+error.size()+" interactiveTraces="+ui.size());
    }

    private void runRealAccountFixture() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ((MyboxApplication) context.getApplicationContext()).requireEngine();
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = installIfAbsent(name, "engine-fixture.apk");
        installIfAbsent("io.github.xmy953538104.mybox.uiconsumer", "account-ui-consumer.apk");
        File reportFile = new File(installed.dataDir, "files/account-result.json");
        if (reportFile.exists() && !reportFile.delete()) throw new java.io.IOException("Cannot clear account fixture report");
        FixtureRun.prepare(installed, "accounts");
        Engine.get().launch(name);
        long deadline = SystemClock.elapsedRealtime() + 90_000;
        while (!reportFile.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        assertTrue("Virtual account fixture did not produce a report", reportFile.isFile());
        JSONObject report = new JSONObject(new String(Files.readAllBytes(reportFile.toPath()), StandardCharsets.UTF_8));
        assertFalse(report.toString(), report.has("failure"));
        for (String check : new String[]{"trustedDirectory", "sameInstanceCache", "accountListener", "noAmbientPermission", "authenticatorBinder",
                "authenticatorFeatures", "authenticatorError", "ownerAcl", "userAndPackageAcl", "asyncAdd", "invalidUiIntent", "interactiveAdd", "removed",
                "ownerTargetProjection", "legacyVisibilityDefaults"}) {
            assertTrue(check + ": " + report, report.getBoolean(check));
        }
    }

    private InstalledPackage installIfAbsent(String name, String asset) throws Exception {
        InstalledPackage installed = Engine.get().packages().get(name);
        if (installed != null) return installed;
        File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "account-probe-" + asset);
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open(asset)) { MainActivity.copyApk(input, apk); }
            return Engine.get().install(Collections.singletonList(apk));
        } finally { apk.delete(); }
    }
}
