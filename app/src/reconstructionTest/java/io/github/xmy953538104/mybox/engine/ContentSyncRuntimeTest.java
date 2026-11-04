package io.github.xmy953538104.mybox.engine;

import android.accounts.Account;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ContentSyncRuntimeTest extends InstrumentationTestCase {
    public void testActualAdapterInitializationMutationCompletionAndCancellation() throws Exception {
        long since = System.currentTimeMillis();
        RuntimeDiagnosticCapture capture = new RuntimeDiagnosticCapture(getInstrumentation().getTargetContext(), "content-sync-diagnostics.zip");
        try (RuntimeDiagnosticCapture ignored = capture) {
            Engine engine = Engine.get(); String name = "io.github.xmy953538104.mybox.fixture";
            InstalledPackage installed = engine.packages().get(name);
            if (installed == null) {
                File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "content-sync-fixture.apk");
                try {
                    try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk"); FileOutputStream output = new FileOutputStream(apk)) {
                        byte[] buffer = new byte[16384]; for (int n; (n = input.read(buffer)) != -1;) output.write(buffer, 0, n);
                    }
                    installed = engine.install(Collections.singletonList(apk));
                } finally { apk.delete(); }
            }
            File path = new File(installed.dataDir, "files/content-sync-result.json");
            if (path.exists() && !path.delete()) throw new java.io.IOException("Cannot remove previous synthetic sync result");
            FixtureRun.prepare(installed, "content-sync"); engine.launch(name);
            long end = SystemClock.elapsedRealtime() + 110000;
            while (!path.isFile() && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
            assertTrue("Actual sync fixture produced no result", path.isFile());
            JSONObject result = new JSONObject(new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
            assertFalse(result.toString(), result.has("failure")); assertFalse(result.toString(), result.has("cleanupFailure"));
            for (String key : new String[]{"metadata", "initializeThroughFramework", "disabledSettingsSelectNoWork", "remoteProviderMutation",
                    "realFinished", "automaticMutationAndFinished", "realCancellation", "invisibleAccountRejected", "invalidExtrasRejected",
                    "ordinaryBindingRejectedByFramework", "workSourceSpoofRejected"}) assertTrue(key + ": " + result, result.getBoolean(key));
            assertTrue(result.getInt("adapterPid") != result.getInt("pid"));
            assertTrue(result.getInt("providerPid") != result.getInt("adapterPid"));
            boolean actualSystemDispatch = false, actualOrdinaryCaller = false;
            JSONArray wire = result.getJSONArray("wire");
            for (int i = 0; i < wire.length(); i++) {
                JSONObject row = wire.getJSONObject(i);
                assertTrue(row.toString(), row.getInt("processUid") >= 10000);
                if (row.getInt("uid") == 1000 && row.getInt("pid") > 0) actualSystemDispatch = true;
                if (row.getInt("uid") == row.getInt("processUid") && row.getInt("pid") == 0) actualOrdinaryCaller = true;
            }
            assertTrue(result.toString(), actualSystemDispatch); assertTrue(result.toString(), actualOrdinaryCaller);
            System.out.println("MYLITE_ACTUAL_SYNC " + result);
        }
        for (String phase : new String[]{"control_raw_uid", "control_raw_pid", "control_visible_uid", "control_restored_uid",
                "start_dispatched", "start_ack", "finished", "cancel_dispatched", "canceled"})
            assertTrue("Actual exported sync phase missing: " + phase, capture.contains("sync", phase, since));
    }

    public void testDurableSyncStateRetainsTypedWorkAndRejectsCorruptionBeforeReplacement() throws Exception {
        File directory = new File(getInstrumentation().getTargetContext().getCacheDir(), "sync-state-" + java.util.UUID.randomUUID());
        assertTrue(directory.mkdirs()); File path = new File(directory, "state.json");
        ContentSyncState state = new ContentSyncState(path);
        Bundle extras = new Bundle(); extras.putString("text", "value"); extras.putInt("int", 7); extras.putLong("long", Long.MAX_VALUE);
        extras.putBoolean("boolean", true); extras.putFloat("float", 0.5f); extras.putDouble("double", 0.125);
        extras.putParcelable("account", new Account("synthetic", "synthetic-type")); extras.putString("null", null);
        JSONObject encoded = ContentSyncState.extras(extras);
        JSONObject owner = new JSONObject().put("component", "synthetic.pkg/.Adapter").put("appId", 12000).put("certificate", "synthetic-certificate")
                .put("authority", "synthetic.authority").put("account", ContentSyncState.account(new Account("synthetic", "synthetic-type")));
        JSONObject work = new JSONObject(owner.toString()).put("generation", "/private/synthetic-generation/base.apk")
                .put("id", 1).put("due", 1234567).put("attempt", 2).put("extras", encoded);
        JSONObject next = state.copy(); next.put("master", true).put("next", 2).put("nextAuthority", 2).put("pending", new JSONArray().put(work));
        next.getJSONArray("settings").put(new JSONObject(owner.toString()).put("id", 1).put("syncable", 1).put("auto", true));
        state.commit(next);
        ContentSyncState reopened = new ContentSyncState(path);
        assertTrue(reopened.value.getBoolean("master"));
        Bundle restored = ContentSyncState.extras(reopened.value.getJSONArray("pending").getJSONObject(0).getJSONObject("extras"));
        assertEquals(7, restored.getInt("int")); assertEquals(Long.MAX_VALUE, restored.getLong("long"));
        assertTrue(restored.getBoolean("boolean")); assertEquals(0.5f, restored.getFloat("float"), 0.0f);
        assertEquals(0.125, restored.getDouble("double"), 0.0);
        Account beforeAccount = extras.getParcelable("account"), afterAccount = restored.getParcelable("account");
        assertEquals(beforeAccount, afterAccount);
        assertTrue(restored.containsKey("null")); assertNull(restored.getString("null"));
        byte[] before = Files.readAllBytes(path.toPath());
        JSONObject invalid = reopened.copy(); invalid.getJSONArray("pending").put(new JSONObject(work.toString()));
        try { reopened.commit(invalid); fail("Duplicate work id was accepted"); } catch (java.io.IOException expected) { }
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(path.toPath())));
        Bundle binder = new Bundle(); binder.putBinder("bad", new android.os.Binder());
        try { ContentSyncState.extras(binder); fail("Binder extra was made durable"); } catch (IllegalArgumentException expected) { }
        Bundle number = new Bundle(); number.putDouble("bad", Double.NaN);
        try { ContentSyncState.extras(number); fail("Non-finite number was made durable"); } catch (IllegalArgumentException expected) { }
        Files.write(path.toPath(), "{\"version\":99}".getBytes(StandardCharsets.UTF_8));
        try { new ContentSyncState(path); fail("Corrupt state silently became an empty queue"); } catch (Exception expected) { }
        for (File file : directory.listFiles()) assertTrue(file.delete()); assertTrue(directory.delete());
    }

    public void testGlobalSyncCallsAreVirtualAndRejectForeignUserAndAttribution() throws Exception {
        Class<?> contract = Class.forName("android.content.IContentService"); String caller = "synthetic.pkg";
        assertTrue(GuestContentSyncCalls.handles("getMasterSyncAutomatically"));
        Bundle master = GuestContentSyncCalls.request(caller, contract.getMethod("getMasterSyncAutomatically"), null);
        assertEquals("sync.getMasterSyncAutomatically", master.getString("operation"));
        try { GuestContentSyncCalls.request(caller, contract.getMethod("getMasterSyncAutomaticallyAsUser", int.class), new Object[]{10}); fail("Physical user was accepted"); }
        catch (SecurityException expected) { }
        try { GuestContentSyncCalls.request(caller, contract.getMethod("requestSync", Account.class, String.class, Bundle.class, String.class),
                new Object[]{null, "synthetic.authority", Bundle.EMPTY, "foreign.pkg"}); fail("Foreign sync attribution was accepted"); }
        catch (SecurityException expected) { }
        Bundle types = EngineProtocol.response(); types.putParcelableArray("value", new android.content.SyncAdapterType[]{new android.content.SyncAdapterType("synthetic.authority", "type", true, false)});
        assertTrue(GuestContentSyncCalls.value("getSyncAdapterTypes", types) instanceof android.content.SyncAdapterType[]);
    }
}
