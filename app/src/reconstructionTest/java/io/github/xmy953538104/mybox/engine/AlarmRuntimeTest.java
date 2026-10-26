package io.github.xmy953538104.mybox.engine;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;

/** Real OS alarms and death/recovery. Never re-schedules the alarm after killing the server. */
public final class AlarmRuntimeTest extends InstrumentationTestCase {
    public void testServiceAlarmUsesActualOsServiceAndSurvivesServerDeath() throws Exception {
        Engine engine = Engine.get(); Context context = getInstrumentation().getTargetContext();
        InstalledPackage installed = fixture();
        String run = UUID.randomUUID().toString(); File files = new File(installed.dataDir, "files");
        FixtureRun.prepare(installed, "service-alarm:" + run); engine.launch(installed.packageName);
        waitReport(files, run, "service-ready", 35_000);
        OsPendingStore store = new OsPendingStore(engine);
        OsPendingStore.Snapshot scheduled = snapshot(store, run, "service-update");
        assertEquals(ServerPendingIntents.SERVICE, scheduled.senderType);
        PendingIntent physical = lookup(context, store, scheduled);
        assertNotNull("No actual OS Service capability", physical); assertTrue(physical.isService());
        assertFalse(physical.isBroadcast()); assertEquals(context.getPackageName(), physical.getCreatorPackage());
        assertScheduledOsRecord(context, scheduled);
        Files.write(file(files, run, "service-ack").toPath(), new byte[]{1});
        JSONObject report = waitReport(files, run, "service-summary", 45_000);
        for (String check : new String[]{"update", "cancellation", "oneShot", "mutableRejected", "wrongTargetRejected"})
            assertTrue(report.toString(), report.getBoolean(check));
        JSONObject warm = waitReport(files, run, "service-service-update-2", 1000);
        assertEquals(installed.packageName, warm.getString("package")); assertEquals(2, warm.getInt("value"));

        store = new OsPendingStore(engine);
        OsPendingStore.Snapshot cold = snapshot(store, run, "service-cold");
        PendingIntent original = lookup(context, store, cold);
        assertNotNull(original); assertTrue(original.isService()); assertScheduledOsRecord(context, cold);
        int oldServer = process(context, context.getPackageName() + ":engine_server");
        assertTrue(oldServer > 0);
        ProviderConnection lease = (ProviderConnection) RuntimeReflection.get(engine, "server");
        assertNotNull(lease); lease.close(); android.os.Process.killProcess(oldServer);
        // Only the original OS alarm may restart the server and guest; no reschedule or launch.
        JSONObject delivered = waitReport(files, run, "service-service-cold-9", 35_000);
        assertEquals(installed.packageName, delivered.getString("package")); assertEquals(9, delivered.getInt("value"));
        assertTrue("OS Service reused its dead server", oldServer != process(context, context.getPackageName() + ":engine_server"));
        assertTrue("OS Service reused the old guest VM", warm.getInt("pid") != delivered.getInt("pid"));
        assertEquals("Cold recovery recreated the OS capability", original, lookup(context, store, cold));
        original.cancel();
    }

    public void testPhysicalSnapshotCompletionAndColdServerRecovery() throws Exception {
        Engine engine = Engine.get(); Context context = getInstrumentation().getTargetContext();
        String pkg = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = fixture();
        File files = new File(installed.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare alarm fixture");
        String run = UUID.randomUUID().toString();
        Files.write(new File(files, "alarm-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(installed, "alarms"); engine.launch(pkg);
        waitFile(files, run, "physical", 35_000);
        OsPendingStore store = new OsPendingStore(engine);
        OsPendingStore.Snapshot warm = snapshot(store, run, "update");
        PendingIntent physical = lookup(context, store, warm);
        assertNotNull("No real Android PendingIntent record", physical);
        assertEquals(context.getPackageName(), physical.getCreatorPackage());
        assertEquals(android.os.Process.myUid(), physical.getCreatorUid());
        assertTrue(physical.isBroadcast());
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(physical.isImmutable());
        Bundle identityRequest = EngineProtocol.response(); identityRequest.putString("package", pkg); identityRequest.putString("process", pkg);
        Bundle identity = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.ACQUIRE, identityRequest);
        try (android.content.ContentProviderClient bootstrap = context.getContentResolver().acquireContentProviderClient(
                android.net.Uri.parse("content://" + context.getPackageName() + ".engine.guest.p" + identity.getInt("slot")))) {
            assertNotNull(bootstrap);
            Bundle forged = new Bundle(identityRequest); forged.putBinder("server_binder", new android.os.Binder());
            try { bootstrap.call("prepare", null, forged); fail("Same-UID host replaced/retired a bound guest's server"); }
            catch (SecurityException expected) { }
        }
        assertTrue("Wrong-owner bootstrap retired the actual guest", identity.getBinder("client").isBinderAlive());
        assertScheduledOsRecord(context, warm);
        Files.write(file(files, run, "physical-ack").toPath(), new byte[]{1});

        JSONObject report = waitReport(files, run, "warm", 70_000);
        assertEquals(report.toString(), android.os.Build.VERSION.SDK_INT < 31
                || context.getSystemService(AlarmManager.class).canScheduleExactAlarms(), report.getBoolean("exactAllowed"));
        for (String check : new String[]{"realCompletion", "updateSnapshot", "immutable", "alarmCancelKeepsPi", "keyFlags",
                "cancelInflight", "cancelCurrent", "oneShot", "physicalCancellation", "deadline", "lateAdmissionRejected", "receiverFailure", "unsupportedRejected", "coldScheduled"}) {
            assertTrue(check + ": " + report, report.getBoolean(check));
        }
        try { physical.send(); fail("Original physical CANCEL_CURRENT record remained live"); }
        catch (PendingIntent.CanceledException expected) { }
        store = new OsPendingStore(engine);
        OsPendingStore.Snapshot cold = snapshot(store, run, "cold");
        PendingIntent coldPhysical = lookup(context, store, cold);
        assertNotNull(coldPhysical);
        int oldServer = process(context, context.getPackageName() + ":engine_server");
        int oldReceiver = waitReport(files, run, "update-done-1", 1000).getInt("pid");
        assertTrue(oldServer > 0); assertTrue(oldReceiver > 0);
        // Release the host lease so the original alarm alone must reacquire its cold server.
        ProviderConnection lease = (ProviderConnection) RuntimeReflection.get(engine, "server");
        assertNotNull(lease); lease.close();
        android.os.Process.killProcess(oldServer);
        // No Engine/server operation, launch, force-stop, or alarm set occurs until the OS delivers.
        JSONObject received = waitReport(files, run, "cold-done-99", 35_000);
        assertEquals(pkg, received.getString("package"));
        assertEquals(99, received.getInt("value"));
        assertFalse("Immutable alarm unexpectedly fabricated a mutable count", received.getBoolean("hasAlarmCount"));
        assertTrue("OS did not launch a new server", process(context, context.getPackageName() + ":engine_server") != oldServer);
        assertTrue("A guest bound to the dead server was reused", received.getInt("pid") != oldReceiver);
        assertEquals("Cold restore replaced the physical capability", coldPhysical, lookup(context, store, cold));

        FixtureRun.prepare(installed, "alarm-recover"); engine.launch(pkg);
        JSONObject restored = waitReport(files, run, "recovery", 35_000);
        for (String check : new String[]{"noCreateRestored", "realRestoredSend", "cancelRestored", "guestColdScheduled"}) assertTrue(restored.toString(), restored.getBoolean(check));
        assertNull("Canceled restored capability was recreated", lookup(context, store, cold));
        store = new OsPendingStore(engine);
        OsPendingStore.Snapshot guestCold = snapshot(store, run, "guest-cold");
        PendingIntent guestPhysical = lookup(context, store, guestCold);
        assertNotNull(guestPhysical);
        lease = (ProviderConnection) RuntimeReflection.get(engine, "server"); lease.close();
        int oldGuest = received.getInt("pid");
        android.os.Process.killProcess(oldGuest); // OS provider dependencies may also retire its server.
        JSONObject restarted = waitReport(files, run, "guest-cold-done-100", 35_000);
        assertTrue("The dead receiver process was reused", oldGuest != restarted.getInt("pid"));
        assertEquals(100, restarted.getInt("value"));
        assertEquals("Guest death replaced the original system record", guestPhysical, lookup(context, store, guestCold));
        guestPhysical.cancel();
    }

    public void testSnapshotCodecTamperAndCallerGenerationBoundaries() throws Exception {
        Engine engine = Engine.get(); InstalledPackage installed = fixture();
        OsPendingStore store = new OsPendingStore(engine); OsPendingStore.Creator creator = new OsPendingStore.Creator(installed);
        Intent original = new Intent().setComponent(new android.content.ComponentName(installed.packageName,
                installed.packageName + ".alarm.AlarmReceiver")).setAction("fixture.codec").putExtra("number", 23);
        Bundle nested = new Bundle(); nested.putString("text", "hello"); original.putExtra("nested", nested);
        String key = OsPendingStore.keyId(creator, 2, PendingIntent.FLAG_IMMUTABLE, original, null);
        OsPendingStore.Snapshot snapshot = new OsPendingStore.Snapshot(UUID.randomUUID().toString(), key, 1, store.boot,
                2, PendingIntent.FLAG_IMMUTABLE, creator, original, null);
        Intent envelope = store.envelope(snapshot);
        original.putExtra("number", 99); nested.putString("text", "changed");
        assertEquals(23, store.verify(envelope).intent.getIntExtra("number", -1));
        assertEquals("hello", store.verify(envelope).intent.getBundleExtra("nested").getString("text"));
        Intent terminal = new Intent(envelope).setComponent(null);
        assertEquals(snapshot.capId, store.verifyCompletion(terminal).capId);
        try { store.verify(terminal); fail("Component-less envelope entered the physical receiver"); }
        catch (SecurityException expected) { }
        try { store.verifyCompletion(new Intent(envelope).setComponent(new android.content.ComponentName("other.package", "other.package.Receiver")));
            fail("Terminal completion accepted a foreign component"); }
        catch (SecurityException expected) { }
        Intent corrupted = new Intent(envelope);
        byte[] bytes = corrupted.getByteArrayExtra(OsPendingStore.PAYLOAD).clone(); bytes[bytes.length - 1] ^= 1;
        corrupted.putExtra(OsPendingStore.PAYLOAD, bytes);
        try { store.verify(corrupted); fail("Tampered snapshot was accepted"); } catch (SecurityException expected) { }
        try { store.verify(new Intent(envelope).setData(android.net.Uri.parse("mylite-alarm://cap/forged"))); fail("Wrong filter identity was accepted"); }
        catch (SecurityException expected) { }
        java.lang.reflect.Constructor<OsPendingStore.Creator> constructor = OsPendingStore.Creator.class.getDeclaredConstructor(
                String.class, String.class, int.class, int.class, java.util.List.class);
        constructor.setAccessible(true);
        OsPendingStore.Creator wrongSigner = constructor.newInstance(creator.packageName, creator.generation, 0, creator.appId,
                Collections.singletonList(String.join("", Collections.nCopies(64, "0"))));
        OsPendingStore.Snapshot wrong = new OsPendingStore.Snapshot(UUID.randomUUID().toString(), key, 1, store.boot, 2,
                PendingIntent.FLAG_IMMUTABLE, wrongSigner, snapshot.intent, null);
        try { store.verify(store.envelope(wrong)); fail("Signed envelope bypassed the actual APK certificate"); }
        catch (SecurityException expected) { }
        Intent serviceIntent = new Intent().setClassName(creator.packageName, creator.packageName + ".alarm.ServiceAlarmProbe");
        String serviceKey = OsPendingStore.keyId(creator, 8201, PendingIntent.FLAG_IMMUTABLE, serviceIntent, null, ServerPendingIntents.SERVICE);
        assertFalse(serviceKey.equals(OsPendingStore.keyId(creator, 8201, PendingIntent.FLAG_IMMUTABLE, serviceIntent, null)));
        OsPendingStore.Snapshot service = new OsPendingStore.Snapshot(UUID.randomUUID().toString(), serviceKey, 1, store.boot,
                8201, PendingIntent.FLAG_IMMUTABLE, creator, serviceIntent, null, ServerPendingIntents.SERVICE);
        Intent serviceEnvelope = store.envelope(service);
        assertEquals(ServerPendingIntents.SERVICE, store.verify(serviceEnvelope).senderType);
        assertEquals(ServerPendingIntents.SERVICE, store.verifyCompletion(new Intent(serviceEnvelope).setComponent(null)).senderType);
        try { store.verify(new Intent(serviceEnvelope).setComponent(new android.content.ComponentName(engine.hostContext(), OsPendingReceiver.class)));
            fail("Service snapshot was admitted by the broadcast entry"); }
        catch (SecurityException expected) { }
        try { OsPendingStore.validateService(new Intent(serviceIntent).addFlags(Intent.FLAG_RECEIVER_FOREGROUND), PendingIntent.FLAG_IMMUTABLE, creator);
            fail("Receiver-only flags accepted for a Service"); }
        catch (IllegalArgumentException expected) { }
        Bundle bad = new Bundle(); bad.putBinder("binder", new android.os.Binder());
        try { OsPendingStore.validate(new Intent(original).putExtras(bad), PendingIntent.FLAG_IMMUTABLE, creator); fail("Binder persisted"); }
        catch (java.io.IOException expected) { }
        try { OsPendingStore.validate(new Intent(original).putExtra("huge", new byte[32769]), PendingIntent.FLAG_IMMUTABLE, creator); fail("Oversized payload persisted"); }
        catch (java.io.IOException expected) { }
        assertFalse(key.equals(OsPendingStore.keyId(creator, 2, PendingIntent.FLAG_IMMUTABLE | Intent.FILL_IN_ACTION, original, null)));
        assertEquals(key, OsPendingStore.keyId(creator, 2, PendingIntent.FLAG_IMMUTABLE, new Intent(original).setFlags(Intent.FLAG_RECEIVER_FOREGROUND), null));
        AlarmDeliveryTicket ticket = new AlarmDeliveryTicket(creator, SystemClock.elapsedRealtime() + 1000);
        try { EngineProtocol.request(ticket, AlarmDeliveryTicket.DESCRIPTOR, android.os.IBinder.FIRST_CALL_TRANSACTION, EngineProtocol.response()); fail("Unregistered host PID admitted as guest"); }
        catch (SecurityException expected) { }
        ticket.close();
        try { ticket.requireActive(); fail("Closed delivery remained live"); } catch (EngineException expected) { }
        AlarmDeliveryTicket expired = new AlarmDeliveryTicket(creator, SystemClock.elapsedRealtime() - 1);
        Bundle budget = EngineProtocol.response(); expired.attach(budget);
        try { AlarmDeliveryTicket.remaining(budget, 8000); fail("Expired work entered main queue"); } catch (EngineException expected) { }
        // Same real signer/version still creates a new committed APK generation.
        InstalledPackage replacement = engine.install(Collections.singletonList(installed.baseApk));
        assertFalse(installed.baseApk.equals(replacement.baseApk));
        try { store.verify(envelope); fail("Old signed snapshot authorized a newly installed generation"); }
        catch (SecurityException expected) { }
        try { store.verify(serviceEnvelope); fail("Old Service snapshot authorized a newly installed generation"); }
        catch (SecurityException expected) { }
    }

    public void testZUncertainDurableMutationCancelsFutureRecordWithoutReplay() throws Exception {
        Engine engine = Engine.get(); Context context = getInstrumentation().getTargetContext();
        fixture();
        ProviderConnection connection = (ProviderConnection) RuntimeReflection.get(engine, "server");
        int server = process(context, context.getPackageName() + ":engine_server");
        if (connection != null) connection.close();
        if (server > 0) {
            android.os.Process.killProcess(server);
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (process(context, context.getPackageName() + ":engine_server") == server && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
            assertTrue("Old store owner did not exit", process(context, context.getPackageName() + ":engine_server") != server);
        }
        OsPendingStore store = new OsPendingStore(engine);
        OsPendingStore.Creator creator = new OsPendingStore.Creator(engine.packages().get("io.github.xmy953538104.mybox.fixture"));
        Intent intent = new Intent().setComponent(new android.content.ComponentName(creator.packageName, creator.packageName + ".alarm.AlarmReceiver"))
                .setAction("fixture.interrupted." + UUID.randomUUID());
        String key = OsPendingStore.keyId(creator, 50, PendingIntent.FLAG_IMMUTABLE, intent, null);
        OsPendingStore.Snapshot snapshot = new OsPendingStore.Snapshot(UUID.randomUUID().toString(), key, 1, store.boot,
                50, PendingIntent.FLAG_IMMUTABLE, creator, intent, null);
        store.put(snapshot, "set");
        PendingIntent physical = PendingIntent.getBroadcast(context, 0, store.envelope(snapshot), snapshot.flags);
        context.getSystemService(AlarmManager.class).set(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 60_000, physical);
        assertScheduledOsRecord(context, snapshot);
        // Models a process exit after real OS acceptance but before COMMITTED was persisted.
        ServerPendingIntents recovered = new ServerPendingIntents(engine, null, null);
        RuntimeReflection.invoke(recovered, RuntimeReflection.method(ServerPendingIntents.class, "mutate", java.util.concurrent.Callable.class),
                (java.util.concurrent.Callable<Void>) () -> null);
        assertNull("Uncertain physical record was replayed/recreated", lookup(context, store, snapshot));
        assertNull("Recovery did not retire the uncertain journal", new OsPendingStore(engine).get(key));
        try { physical.send(); fail("Uncertain old OS capability remained sendable"); } catch (PendingIntent.CanceledException expected) { }
        // An OS-copied envelope remains independently valid; canceled membership is not its proof.
        assertEquals(snapshot.capId, store.verify(store.envelope(snapshot)).capId);
    }

    public void testStoreLossAndCorruptionCannotRegenerateSigningAuthority() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "alarm-store-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
        };
        java.lang.reflect.Constructor<Engine> constructor = Engine.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        Engine testEngine = constructor.newInstance(isolated);
        new OsPendingStore(testEngine);
        File store = new File(directory, "mylite-os-pending/capabilities.bin");
        Files.write(store.toPath(), new byte[]{1, 2, 3});
        try { new OsPendingStore(testEngine); fail("Corrupt store silently reset its signing key"); }
        catch (java.io.IOException expected) { }
        Files.delete(store.toPath());
        try { new OsPendingStore(testEngine); fail("Lost signing authority silently regenerated"); }
        catch (java.io.IOException expected) { }
    }

    private InstalledPackage fixture() throws Exception {
        Engine engine = Engine.get(); InstalledPackage installed = engine.packages().get("io.github.xmy953538104.mybox.fixture");
        if (installed != null) return installed;
        File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "alarm-fixture.apk");
        try {
            try (InputStream source = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                Files.copy(source, apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return engine.install(Collections.singletonList(apk));
        } finally { apk.delete(); }
    }

    private static OsPendingStore.Snapshot snapshot(OsPendingStore store, String run, String label) {
        for (OsPendingStore.Record record : store.all()) {
            if (("fixture.alarm." + run + "." + label).equals(record.snapshot.intent.getAction())) {
                assertNull("OS state returned before its journal committed", record.journal); return record.snapshot;
            }
        }
        throw new AssertionError("Missing durable OS capability: " + label);
    }
    private static PendingIntent lookup(Context context, OsPendingStore store, OsPendingStore.Snapshot snapshot) {
        if (snapshot.senderType == ServerPendingIntents.SERVICE)
            return PendingIntent.getService(context, snapshot.requestCode, store.identity(snapshot), snapshot.flags | PendingIntent.FLAG_NO_CREATE);
        return PendingIntent.getBroadcast(context, 0, store.identity(snapshot), snapshot.flags | PendingIntent.FLAG_NO_CREATE);
    }
    private static int process(Context context, String name) {
        for (ActivityManager.RunningAppProcessInfo process : context.getSystemService(ActivityManager.class).getRunningAppProcesses()) {
            if (name.equals(process.processName) && process.uid == android.os.Process.myUid()) return process.pid;
        }
        return -1;
    }
    private void assertScheduledOsRecord(Context context, OsPendingStore.Snapshot snapshot) throws Exception {
        String dump = shell("dumpsys alarm");
        String records = shell("dumpsys activity intents");
        // AlarmManager prints the OS record identity, not its Intent data URI/capability ID.
        java.util.regex.Matcher record = java.util.regex.Pattern.compile(
                "(?m)^\\s*(?:\\*|#\\d+:) PendingIntentRecord\\{([0-9a-f]+) [^\\r\\n]+\\}").matcher(records);
        String osRecord = null;
        int previousStart = -1;
        String previousId = null;
        while (record.find()) {
            if (previousStart >= 0 && records.substring(previousStart, record.start()).contains(storeIdentity(snapshot))) {
                osRecord = previousId; break;
            }
            previousStart = record.start(); previousId = record.group(1);
        }
        if (osRecord == null && previousStart >= 0
                && records.substring(previousStart).contains(storeIdentity(snapshot))) osRecord = previousId;
        assertNotNull("Real PendingIntent dump did not contain the exact capability", osRecord);
        assertTrue("Real AlarmManager did not reference that exact OS record",
                dump.contains("PendingIntentRecord{" + osRecord + " " + context.getPackageName()
                        + (snapshot.senderType == ServerPendingIntents.SERVICE ? " startService}" : " broadcastIntent}")));
    }

    private static String storeIdentity(OsPendingStore.Snapshot snapshot) {
        return (snapshot.senderType == ServerPendingIntents.SERVICE ? "mylite-service" : "mylite-alarm") + "://cap/" + snapshot.capId;
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = getInstrumentation().getUiAutomation().executeShellCommand(command);
             InputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }
    private static File file(File files, String run, String name) { return new File(files, "alarm-" + run + "-" + name); }
    private static void waitFile(File files, String run, String name, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!file(files, run, name).isFile() && SystemClock.elapsedRealtime() < deadline) {
            for (String marker : new String[]{"warm", "service-summary"}) {
                if (!name.equals(marker) && file(files, run, marker).isFile()) {
                    JSONObject failure = new JSONObject(new String(Files.readAllBytes(file(files, run, marker).toPath()), StandardCharsets.UTF_8));
                    assertFalse(failure.toString(), failure.has("failure"));
                }
            }
            Thread.sleep(25);
        }
        assertTrue("Missing alarm runtime evidence: " + name, file(files, run, name).isFile());
    }
    private static JSONObject waitReport(File files, String run, String name, long timeout) throws Exception {
        waitFile(files, run, name, timeout);
        JSONObject report = new JSONObject(new String(Files.readAllBytes(file(files, run, name).toPath()), StandardCharsets.UTF_8));
        assertFalse(report.toString(), report.has("failure")); return report;
    }
}
