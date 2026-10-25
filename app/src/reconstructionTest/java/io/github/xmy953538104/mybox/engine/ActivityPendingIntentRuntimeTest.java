package io.github.xmy953538104.mybox.engine;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;

/** Actual target callbacks and the same OS sender after server death, not a mocked Binder. */
public final class ActivityPendingIntentRuntimeTest extends InstrumentationTestCase {
    public void testActualFrameworkDefaultSendOptionsArePreserved() {
        android.os.Bundle baseline = android.app.ActivityOptions.makeBasic().toBundle();
        assertNotNull("Framework did not serialize its basic options", baseline);
        android.os.Bundle options = new android.os.Bundle(baseline);
        android.hardware.display.DisplayManager manager = getInstrumentation().getTargetContext()
                .getSystemService(android.hardware.display.DisplayManager.class);
        android.view.Display display = manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        assertNotNull(display);
        options.putInt(ServerPendingIntents.CALLER_DISPLAY_ID, display.getDisplayId());
        android.os.Bundle accepted = ServerPendingIntents.normalizeActivitySendOptions(options);
        assertNotSame("Caller options were returned without a defensive copy", options, accepted);
        assertEquals(options.keySet(), accepted.keySet());
        for (String key : options.keySet()) {
            Object expected = options.get(key), actual = accepted.get(key);
            assertEquals("Default option type changed: " + key, expected == null ? null : expected.getClass(),
                    actual == null ? null : actual.getClass());
            assertEquals("Default option value changed: " + key, expected, actual);
        }
        for (String key : baseline.keySet()) {
            if (ServerPendingIntents.CALLER_DISPLAY_ID.equals(key)) continue;
            Object expected = baseline.get(key);
            android.os.Bundle changed = new android.os.Bundle(options);
            if (expected instanceof Integer) changed.putInt(key, ((Integer) expected) ^ 1);
            else if (expected instanceof Boolean) changed.putBoolean(key, !((Boolean) expected));
            else if (expected instanceof Long) changed.putLong(key, ((Long) expected) ^ 1L);
            else if (expected instanceof Float) changed.putFloat(key, ((Float) expected) == 0 ? 1 : 0);
            else if (expected instanceof Double) changed.putDouble(key, ((Double) expected) == 0 ? 1 : 0);
            else changed.putString(key, String.valueOf(expected) + "-changed");
            try { ServerPendingIntents.normalizeActivitySendOptions(changed); fail("A changed default value was accepted: " + key); }
            catch (EngineException rejected) { }
            changed = new android.os.Bundle(options);
            if (expected instanceof String) changed.putInt(key, 1); else changed.putString(key, String.valueOf(expected));
            try { ServerPendingIntents.normalizeActivitySendOptions(changed); fail("A changed default type was accepted: " + key); }
            catch (EngineException rejected) { }
        }
        android.os.Bundle privileged = new android.os.Bundle(options);
        String privilegeKey = "android.pendingIntent.backgroundActivityAllowedByPermission";
        privileged.putBoolean(privilegeKey, !Boolean.TRUE.equals(baseline.get(privilegeKey)));
        try { ServerPendingIntents.normalizeActivitySendOptions(privileged); fail("Privileged activity-start options were accepted"); }
        catch (EngineException rejected) { }
    }

    public void testPhysicalActivitySenderAndColdRecovery() throws Exception {
        Engine engine = Engine.get();
        Context context = getInstrumentation().getTargetContext();
        InstalledPackage installed = fixture();
        File files = new File(installed.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare Activity probe");
        String run = UUID.randomUUID().toString();
        Files.write(new File(files, "activity-pending-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(installed, "activity-pending"); engine.launch(installed.packageName);
        JSONObject report = await(new File(files, "activity-pending-result-" + run + ".json"), 90_000);
        assertFalse(report.toString(), report.has("failure"));
        for (String check : new String[]{"creationDidNotLaunch", "mutable", "callerDisplay", "sendOptionsRejected", "updateAndNewIntent", "immutable", "oneShot", "cancelAndNoCreate", "canceledCapabilityMetadata",
                "cancelInflight", "oneShotReplacementInflight", "updateInflight", "coldReady"})
            assertTrue(check + ": " + report, report.getBoolean(check));
        OsPendingStore store = new OsPendingStore(engine);
        OsPendingStore.Snapshot cold = null, coldOnce = null;
        for (OsPendingStore.Record record : store.all()) if (record.snapshot.senderType == ServerPendingIntents.ACTIVITY
                && run.equals(record.snapshot.intent.getAction())) {
            if (record.snapshot.requestCode == 7913) cold = record.snapshot;
            if (record.snapshot.requestCode == 7914) coldOnce = record.snapshot;
        }
        assertNotNull("No durable Activity capability", cold);
        assertNotNull("No durable one-shot Activity capability", coldOnce);
        PendingIntent physical = PendingIntent.getActivity(context, cold.requestCode, store.identity(cold),
                cold.flags | PendingIntent.FLAG_NO_CREATE);
        assertNotNull("OS Activity record was not created", physical);
        assertTrue(physical.isActivity());
        assertEquals(context.getPackageName(), physical.getCreatorPackage());
        PendingIntent once = PendingIntent.getActivity(context, coldOnce.requestCode, store.identity(coldOnce),
                coldOnce.flags | PendingIntent.FLAG_NO_CREATE);
        assertNotNull(once);
        int oldServer = process(context, context.getPackageName() + ":engine_server");
        assertTrue("No engine server", oldServer > 0);
        ProviderConnection lease = (ProviderConnection) RuntimeReflection.get(engine, "server");
        if (lease != null) lease.close();
        android.os.Process.killProcess(oldServer);
        long diedBy = android.os.SystemClock.elapsedRealtime() + 10_000;
        while (process(context, context.getPackageName() + ":engine_server") == oldServer
                && android.os.SystemClock.elapsedRealtime() < diedBy) Thread.sleep(25);
        assertFalse("Server did not terminate", oldServer == process(context, context.getPackageName() + ":engine_server"));
        // No Engine/server acquire, guest launch or PI recreation before this real OS send.
        once.send();
        assertEquals("cold-once", await(new File(files, "activity-pending-event-" + run + "-cold-once.json"), 35_000).getString("value"));
        @SuppressWarnings("unchecked") java.util.Set<String> durableClaims = (java.util.Set<String>) RuntimeReflection.get(
                new OsPendingStore(engine), "activityClaims");
        assertTrue("One-shot admission did not survive reloading the AtomicFile", durableClaims.contains(coldOnce.capId));
        try { once.send(); fail("Cold one-shot OS record sent twice"); }
        catch (PendingIntent.CanceledException expected) { }
        physical.send();
        JSONObject delivered = await(new File(files, "activity-pending-event-" + run + "-cold.json"), 35_000);
        assertEquals("cold", delivered.getString("value"));
        assertFalse("Cold recovery reused the retired guest process", delivered.getInt("pid") ==
                await(new File(files, "activity-pending-event-" + run + "-1.json"), 1_000).getInt("pid"));
        assertTrue("OS did not start a new engine server", process(context, context.getPackageName() + ":engine_server") != oldServer);
        assertEquals("Server death replaced the physical capability", physical,
                PendingIntent.getActivity(context, cold.requestCode, store.identity(cold), cold.flags | PendingIntent.FLAG_NO_CREATE));
        physical.cancel();
    }

    public void testCodecTamperFillInAndGeneration() throws Exception {
        Engine engine = Engine.get(); InstalledPackage installed = fixture();
        OsPendingStore store = new OsPendingStore(engine);
        OsPendingStore.Creator creator = new OsPendingStore.Creator(installed);
        Intent base = new Intent("original").setComponent(new android.content.ComponentName(installed.packageName,
                installed.packageName + ".pending.ActivityPendingProbe$Target")).putExtra("value", "original");
        String key = OsPendingStore.keyId(creator, 9, PendingIntent.FLAG_MUTABLE, base, null, ServerPendingIntents.ACTIVITY);
        assertFalse("Broadcast and Activity identity collided", key.equals(OsPendingStore.keyId(creator, 9, PendingIntent.FLAG_MUTABLE, base, null)));
        OsPendingStore.Snapshot mutable = new OsPendingStore.Snapshot(UUID.randomUUID().toString(), key, 1,
                store.boot, 9, PendingIntent.FLAG_MUTABLE, creator, base, null, ServerPendingIntents.ACTIVITY);
        Intent envelope = store.envelope(mutable);
        assertEquals(ServerPendingIntents.ACTIVITY, store.verify(envelope).senderType);
        Intent filled = new Intent(envelope).putExtras(OsPendingStore.activityFillIn(new Intent("new-action")
                .putExtra("value", "cannot-overwrite-base").putExtra("fill", "filled"), null));
        Intent result = OsPendingStore.activityIntent(mutable, filled);
        assertEquals("original", result.getAction()); assertEquals("original", result.getStringExtra("value"));
        assertEquals("filled", result.getStringExtra("fill"));
        OsPendingStore.Snapshot immutable = new OsPendingStore.Snapshot(UUID.randomUUID().toString(), key, 1,
                store.boot, 9, PendingIntent.FLAG_IMMUTABLE, creator, base, null, ServerPendingIntents.ACTIVITY);
        Intent ignored = OsPendingStore.activityIntent(immutable, new Intent(store.envelope(immutable))
                .putExtras(OsPendingStore.activityFillIn(new Intent().putExtra("fill", "forged"), null)));
        assertFalse("Immutable capability accepted a new fill-in extra", ignored.hasExtra("fill"));
        Intent tampered = new Intent(envelope); byte[] bytes = tampered.getByteArrayExtra(OsPendingStore.PAYLOAD).clone();
        bytes[bytes.length - 1] ^= 1; tampered.putExtra(OsPendingStore.PAYLOAD, bytes);
        try { store.verify(tampered); fail("Tampered signed snapshot was accepted"); }
        catch (SecurityException expected) { }
        Intent cross = new Intent(envelope).putExtras(OsPendingStore.activityFillIn(new Intent()
                .setPackage("com.google.android.gms"), null));
        try { OsPendingStore.activityIntent(mutable, cross); fail("Cross-package fill-in escaped"); }
        catch (SecurityException expected) { }
        java.lang.reflect.Constructor<OsPendingStore.Creator> constructor = OsPendingStore.Creator.class.getDeclaredConstructor(
                String.class, String.class, int.class, int.class, java.util.List.class);
        constructor.setAccessible(true);
        for (OsPendingStore.Creator stale : new OsPendingStore.Creator[]{
                constructor.newInstance(creator.packageName, creator.generation + ".retired", 0, creator.appId, creator.certificates),
                constructor.newInstance(creator.packageName, creator.generation, 0, creator.appId,
                        Collections.singletonList(String.join("", Collections.nCopies(64, "0"))))}) {
            OsPendingStore.Snapshot wrong = new OsPendingStore.Snapshot(UUID.randomUUID().toString(), key, 1,
                    store.boot, 9, PendingIntent.FLAG_MUTABLE, stale, base, null, ServerPendingIntents.ACTIVITY);
            try { store.verify(store.envelope(wrong)); fail("Signed snapshot bypassed installed generation/signer"); }
            catch (SecurityException expected) { }
        }
        Intent broadcast = new Intent().setComponent(new android.content.ComponentName(installed.packageName,
                installed.packageName + ".pending.PendingReceiver"));
        OsPendingStore.Snapshot legacy = new OsPendingStore.Snapshot(UUID.randomUUID().toString(), key, 1,
                store.boot, 9, PendingIntent.FLAG_IMMUTABLE, creator, broadcast, null);
        byte[] modern = OsPendingStore.encode(legacy);
        byte[] versionOne = java.util.Arrays.copyOf(modern, modern.length - Integer.BYTES);
        java.nio.ByteBuffer.wrap(versionOne).putInt(1);
        OsPendingStore.Snapshot restored = OsPendingStore.decode(versionOne);
        assertEquals(ServerPendingIntents.BROADCAST, restored.senderType);
        assertEquals(legacy.capId, restored.capId);
    }

    private InstalledPackage fixture() throws Exception {
        Engine engine = Engine.get();
        InstalledPackage installed = engine.packages().get("io.github.xmy953538104.mybox.fixture");
        if (installed != null) return installed;
        File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "activity-pending-fixture.apk");
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                Files.copy(input, apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return engine.install(Collections.singletonList(apk));
        } finally { apk.delete(); }
    }
    private static JSONObject await(File file, long timeout) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!file.isFile() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        if (!file.isFile()) throw new AssertionError("No actual Activity report: " + file.getName());
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    private static int process(Context context, String name) {
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = context.getSystemService(ActivityManager.class).getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) if (name.equals(process.processName)) return process.pid;
        return -1;
    }
}
