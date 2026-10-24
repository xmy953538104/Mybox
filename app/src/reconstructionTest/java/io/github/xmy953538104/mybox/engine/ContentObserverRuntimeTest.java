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
        Engine engine = Engine.get(); Context context = getInstrumentation().getTargetContext();
        android.os.IBinder original = engine.server();
        InstalledPackage installed = engine.packages().get("io.github.xmy953538104.mybox.fixture");
        android.content.Intent stale = null; android.os.IBinder oldGuest = null;
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
            oldGuest = EngineProtocol.request(original, EngineProtocol.SERVER, EngineProtocol.ACQUIRE, owner).getBinder("client");
            assertNotNull(oldGuest);
            oldGuest.linkToDeath(guestDeath::countDown, 0);
        }
        int serverPid = -1;
        for (android.app.ActivityManager.RunningAppProcessInfo process : context.getSystemService(android.app.ActivityManager.class).getRunningAppProcesses()) {
            if (process.uid == android.os.Process.myUid() && (context.getPackageName() + ":engine_server").equals(process.processName)) serverPid = process.pid;
        }
        assertTrue("No exact owned server process", serverPid > 0 && serverPid != android.os.Process.myPid());
        android.os.Process.killProcess(serverPid);
        long end = SystemClock.elapsedRealtime() + 10000;
        while (original.isBinderAlive() && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
        assertFalse("Original server did not die", original.isBinderAlive());
        if (oldGuest != null) {
            assertTrue("Original sealed guest survived its server", guestDeath.await(10, java.util.concurrent.TimeUnit.SECONDS));
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
        assertFalse("Dead server Binder was reused", original.equals(replacement));
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
}
