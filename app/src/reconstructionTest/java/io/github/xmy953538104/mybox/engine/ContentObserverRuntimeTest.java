package io.github.xmy953538104.mybox.engine;

import android.content.Context;
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
import org.json.JSONObject;

public final class ContentObserverRuntimeTest extends InstrumentationTestCase {
    public void testServerDeathRetiresGuestsAndTheHostCanReconnect() throws Exception {
        recoverActivity(true);
    }
    public void testExecutorDeathRetiresLateActivityWithoutKillingTheLiveServer() throws Exception {
        recoverActivity(false);
    }
    private void recoverActivity(boolean serverDeath) throws Exception {
        Engine engine = Engine.get(); Context context = getInstrumentation().getTargetContext();
        android.os.IBinder original = engine.server();
        InstalledPackage installed = engine.packages().get("io.github.xmy953538104.mybox.fixture");
        assertNotNull("Activity recovery requires the installed fixture", installed);
        android.content.Intent stale = null; android.os.IBinder oldGuest = null; int oldGuestPid = -1;
        java.util.concurrent.CountDownLatch guestDeath = new java.util.concurrent.CountDownLatch(1);
        if (installed != null) {
            android.content.pm.ActivityInfo launcher = engine.packages().findLaunchActivity(installed.packageName);
            assertNotNull(launcher);
            Bundle prepare = EngineProtocol.response(); prepare.putString("operation", "prepare");
            prepare.putParcelable("intent", new android.content.Intent().setComponent(new android.content.ComponentName(launcher.packageName, launcher.name))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            stale = EngineProtocol.request(original, EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, prepare).getParcelable("outer_intent");
            assertNotNull(stale);
            Bundle owner = EngineProtocol.response(); owner.putString("package", launcher.packageName);
            owner.putString("process", launcher.processName == null ? launcher.packageName : launcher.processName);
            Bundle acquired = EngineProtocol.request(original, EngineProtocol.SERVER, EngineProtocol.ACQUIRE, owner);
            oldGuest = acquired.getBinder("client"); oldGuestPid = acquired.getInt("pid");
            assertNotNull(oldGuest);
            oldGuest.linkToDeath(guestDeath::countDown, 0);
        }
        int serverPid = -1;
        for (android.app.ActivityManager.RunningAppProcessInfo process : context.getSystemService(android.app.ActivityManager.class).getRunningAppProcesses()) {
            if (process.uid == android.os.Process.myUid() && (context.getPackageName() + ":engine_server").equals(process.processName)) serverPid = process.pid;
        }
        assertTrue("No exact owned server process", serverPid > 0 && serverPid != android.os.Process.myPid());
        assertTrue(serverDeath || oldGuestPid > 0 && oldGuestPid != android.os.Process.myPid());
        android.os.Process.killProcess(serverDeath ? serverPid : oldGuestPid);
        long end = SystemClock.elapsedRealtime() + 10000;
        if (serverDeath) {
            while (original.isBinderAlive() && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
            assertFalse("Original server did not die", original.isBinderAlive());
        } else assertTrue("Executor-only death retired the server", original.isBinderAlive());
        if (oldGuest != null) {
            assertTrue("Original sealed guest survived its server", guestDeath.await(10, java.util.concurrent.TimeUnit.SECONDS));
            if (!serverDeath) assertTrue("Executor test also lost the server-owned record Binder", stale.getBundleExtra(EngineProtocol.LAUNCH).getBinder(ActivityEnvelope.RECORD).isBinderAlive());
            java.util.Set<Integer> priorTasks = new java.util.HashSet<>();
            for (android.app.ActivityManager.AppTask task : context.getSystemService(android.app.ActivityManager.class).getAppTasks()) {
                priorTasks.add(task.getTaskInfo().taskId);
            }
            context.startActivity(stale);
            boolean remaining;
            long closed = SystemClock.elapsedRealtime() + 10000;
            do {
                remaining = false;
                for (android.app.ActivityManager.AppTask task : context.getSystemService(android.app.ActivityManager.class).getAppTasks()) {
                    android.app.ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    if (!priorTasks.contains(info.taskId) && info.numActivities > 0 && stale.filterEquals(info.baseIntent)) remaining = true;
                }
                if (remaining) Thread.sleep(25);
            } while (remaining && SystemClock.elapsedRealtime() < closed);
            assertFalse("Expired physical Activity stayed alive in its Android task", remaining);
        }
        android.os.IBinder replacement = engine.server();
        assertTrue("Host could not reconnect to the actual replacement server", replacement.isBinderAlive());
        assertEquals("Server replacement differs from the actual killed process", !serverDeath, original.equals(replacement));
        if (installed != null) {
            FixtureRun.prepare(installed, "providers"); engine.launch(installed.packageName);
            assertTrue("Replacement server died during real guest relaunch", replacement.isBinderAlive());
            Bundle inspect = EngineProtocol.response(); inspect.putString("package", installed.packageName);
            android.content.pm.ActivityInfo launcher = engine.packages().findLaunchActivity(installed.packageName);
            inspect.putString("process", launcher.processName == null ? launcher.packageName : launcher.processName);
            inspect.putBoolean("include_runtime_state", true);
            Bundle runtime = EngineProtocol.request(replacement, EngineProtocol.SERVER, EngineProtocol.ACQUIRE, inspect).getBundle("runtime");
            assertNotNull(runtime);
            assertTrue("Replacement bypassed the expired Activity recovery path", runtime.getInt("discarded_activity_launches") > 0);
            int discarded = runtime.getInt("discarded_activity_launches"), pid = runtime.getInt("pid");
            java.util.Set<Integer> boundTasks = new java.util.HashSet<>();
            for (android.app.ActivityManager.AppTask task : context.getSystemService(android.app.ActivityManager.class).getAppTasks()) boundTasks.add(task.getTaskInfo().taskId);
            context.startActivity(stale);
            long late = SystemClock.elapsedRealtime() + 10000;
            do {
                runtime = EngineProtocol.request(replacement, EngineProtocol.SERVER, EngineProtocol.ACQUIRE, inspect).getBundle("runtime");
                if (runtime.getInt("discarded_activity_launches") > discarded) break;
                Thread.sleep(25);
            } while (SystemClock.elapsedRealtime() < late);
            assertEquals("A late dead launch killed the newly bound guest", pid, runtime.getInt("pid"));
            assertTrue("Bound guest did not discard the actually dead Activity record", runtime.getInt("discarded_activity_launches") > discarded);
            Thread.sleep(1500); // A counter alone precedes the physical finish and can miss a delayed crash.
            runtime = EngineProtocol.request(replacement, EngineProtocol.SERVER, EngineProtocol.ACQUIRE, inspect).getBundle("runtime");
            assertEquals("Discarding the stale physical Activity crashed the bound guest", pid, runtime.getInt("pid"));
            for (android.app.ActivityManager.AppTask task : context.getSystemService(android.app.ActivityManager.class).getAppTasks()) {
                android.app.ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                assertFalse("Bound guest left the expired physical task alive", !boundTasks.contains(info.taskId) && info.numActivities > 0 && stale.filterEquals(info.baseIntent));
            }
            String nonce = java.util.UUID.randomUUID().toString();
            File health = new File(installed.dataDir, "files/activity-health-" + nonce + ".json");
            assertFalse("Recovery health result already exists", health.exists());
            try {
                // A live Binder/PID can survive while Android's crash dialog blocks the guest main thread.
                FixtureRun.prepare(installed, "activity-health:" + nonce); engine.launch(installed.packageName);
                long healthy = SystemClock.elapsedRealtime() + 10000;
                while (!health.isFile() && SystemClock.elapsedRealtime() < healthy) Thread.sleep(25);
                assertTrue("Recovered guest main thread did not complete a new Activity", health.isFile());
                JSONObject result = new JSONObject(new String(Files.readAllBytes(health.toPath()), StandardCharsets.UTF_8));
                assertEquals("Recovery result belongs to another launch", nonce, result.getString("nonce"));
                assertEquals("Recovery silently replaced the expected guest", pid, result.getInt("pid"));
                assertTrue("Recovery health did not run on the guest main thread", result.getBoolean("main_thread"));
                assertTrue("Recovery health launch killed the server", replacement.isBinderAlive());
                assertEquals("Recovery health launch replaced the server", replacement, engine.server());
            } finally { Files.deleteIfExists(health.toPath()); }
        }
    }

    public void testProtectedProviderMetadataCannotBecomeAnOpenPeerGrant() {
        android.content.pm.ProviderInfo target = new android.content.pm.ProviderInfo(); target.packageName = "owner";
        VirtualProviders.requireOpen("owner", target);
        try { VirtualProviders.requireOpen("peer", target); fail("Private provider was exported"); } catch (SecurityException expected) { }
        target.exported = true; VirtualProviders.requireOpen("peer", target);
        target.readPermission = "permission.READ";
        try { VirtualProviders.requireOpen("peer", target); fail("Read permission was discarded"); } catch (SecurityException expected) { }
        target.readPermission = null; target.writePermission = "permission.WRITE";
        try { VirtualProviders.requireOpen("peer", target); fail("Write permission was discarded"); } catch (SecurityException expected) { }
        target.writePermission = null; target.pathPermissions = new android.content.pm.PathPermission[]{
                new android.content.pm.PathPermission("/private", android.os.PatternMatcher.PATTERN_PREFIX, "permission.READ", null)};
        try { VirtualProviders.requireOpen("peer", target); fail("Path permission was discarded"); } catch (SecurityException expected) { }
    }

    public void testActualRemoteNotificationsPathsSelfFlagsAndUnregister() throws Exception {
        Context context = getInstrumentation().getTargetContext(); Engine engine = Engine.get();
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            File apk = new File(context.getCacheDir(), "content-observer-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk"); FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] bytes = new byte[16384]; for (int size; (size = input.read(bytes)) != -1;) output.write(bytes, 0, size);
                }
                installed = engine.install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        File reportFile = new File(installed.dataDir, "files/content-observer-result.json");
        if (reportFile.exists() && !reportFile.delete()) throw new java.io.IOException("Cannot clear the synthetic observer report");
        FixtureRun.prepare(installed, "content-observers"); engine.launch(name);
        long end = SystemClock.elapsedRealtime() + 60000;
        while (!reportFile.isFile() && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
        assertTrue("Guest content observer probe produced no report", reportFile.isFile());
        JSONObject result = new JSONObject(new String(Files.readAllBytes(reportFile.toPath()), StandardCharsets.UTF_8));
        assertFalse(result.toString(), result.has("failure"));
        for (String check : new String[]{"remoteDelivery", "descendantsAndLegacyUser", "ancestorAndSelf", "skipDescendantFlag", "unregister", "syncRejected", "quotaAndFiveProcessDeaths"}) {
            assertTrue(check + ": " + result, result.getBoolean(check));
        }
        assertTrue(result.getInt("pid") != result.getInt("peerPid"));
    }

    public void testUnregisteredHostCannotPretendToBeAGuestSubscriber() {
        Bundle connect = EngineProtocol.response(); connect.putString("service", "content_observers");
        try { EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.GET_SERVICE, connect); fail("Unregistered host acquired the guest content endpoint"); }
        catch (EngineException denied) { assertTrue(denied.getMessage().contains("identity")); }
    }

    public void testLiveOrFailedPingRecordIsNotADeathProof() {
        Bundle envelope = EngineProtocol.response();
        envelope.putBinder(ActivityEnvelope.RECORD, new android.os.Binder() { @Override public boolean pingBinder() { return false; } });
        assertFalse("A failed ping became evidence of actual Binder death", ActivityEnvelope.recordDied(envelope));
        envelope.putBinder(ActivityEnvelope.RECORD, new android.os.Binder() {
            @Override protected boolean onTransact(int code, android.os.Parcel input, android.os.Parcel reply, int flags) { return false; }
        });
        assertFalse("An unsupported read transaction became evidence of Binder death", ActivityEnvelope.recordDied(envelope));
    }
}
