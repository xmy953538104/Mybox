package io.github.xmy953538104.mybox.engine;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.UiAutomation;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.test.InstrumentationTestCase;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.InputDevice;
import android.view.MotionEvent;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;

/** Actual SystemUI touch and guest callback, independently observed from the host. */
public final class NotificationRuntimeTest extends InstrumentationTestCase {
    public void testSystemUiBroadcastCancelOneShotAndSwipeDelete() throws Throwable {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        UiAutomation ui = getInstrumentation().getUiAutomation();
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(host.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        InstalledPackage installed = notificationFixture(); String run = UUID.randomUUID().toString();
        android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo(); int flags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS; ui.setServiceInfo(info);
        Throwable primary = null;
        try {
            seedBroadcast(installed, run, true, false);
            JSONObject posted = awaitDestroyed(event(installed, run, "posted"));
            for (String check : new String[]{"posted", "mutableRejected", "serviceRejected", "foregroundRejected"}) assertTrue(posted.toString(), posted.getBoolean(check));
            StatusBarNotification notice = notice(host, "Mylite broadcast " + run);
            assertTrue(notice.getNotification().actions[0].actionIntent.isBroadcast());
            assertTrue(notice.getNotification().deleteIntent.isBroadcast());
            assertEquals(host.getPackageName(), notice.getNotification().actions[0].actionIntent.getCreatorPackage());
            OsPendingStore store = new OsPendingStore(engine); OsPendingStore.Snapshot action = broadcastSnapshot(store, run, "cancel");
            assertFalse(event(installed, run, "cancel").exists());
            shell(ui, "cmd statusbar expand-notifications"); touch(ui, "Mylite cancel " + run);
            JSONObject canceled = await(event(installed, run, "cancel"));
            assertEquals(installed.packageName, canceled.getString("package")); assertEquals(17, canceled.getInt("value")); assertEquals(1, canceled.getInt("count"));
            awaitAbsent(host, "Mylite work " + run);
            assertNull("One-shot broadcast system record survived actual UI delivery", PendingIntent.getBroadcast(host, 0,
                    store.identity(action), action.flags | PendingIntent.FLAG_NO_CREATE));
            // If SystemUI still shows the canceled action, touch it again; Android may instead disable/remove it.
            shell(ui, "cmd statusbar expand-notifications"); Rect again = visible(ui, "Mylite cancel " + run);
            if (again != null) WebInputRuntimeTest.tap(ui, again.centerX(), again.centerY(), 50);
            Thread.sleep(400); assertEquals(1, read(event(installed, run, "cancel")).getInt("count"));
            dismiss(ui, host, "Mylite broadcast " + run); JSONObject deleted = await(event(installed, run, "delete"));
            assertEquals("delete", deleted.getString("kind")); assertEquals(1, deleted.getInt("count"));
            awaitAbsent(host, "Mylite broadcast " + run);
        } catch (Throwable failure) { primary = failure; throw failure; }
        finally { finishBroadcastTest(ui, info, flags, host, primary, run); }
    }

    public void testSystemUiBroadcastColdServerRecoveryAndSamePhysicalSenderRepost() throws Throwable {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        UiAutomation ui = getInstrumentation().getUiAutomation();
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(host.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        InstalledPackage installed = notificationFixture(); String run = UUID.randomUUID().toString();
        android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo(); int flags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS; ui.setServiceInfo(info);
        Throwable primary = null; Bundle oldGuest = null;
        java.util.concurrent.CountDownLatch oldGuestDied = new java.util.concurrent.CountDownLatch(1);
        android.os.IBinder.DeathRecipient oldGuestDeath = oldGuestDied::countDown;
        try {
            seedBroadcast(installed, run, false, false); JSONObject posted = awaitDestroyed(event(installed, run, "posted"));
            oldGuest = fixtureIdentity(installed); assertEquals(posted.getInt("pid"), oldGuest.getInt("pid"));
            oldGuest.getBinder("client").linkToDeath(oldGuestDeath, 0);
            Notification before = notice(host, "Mylite broadcast " + run).getNotification();
            PendingIntent action = before.actions[0].actionIntent, deleted = before.deleteIntent;
            OsPendingStore committed = new OsPendingStore(engine);
            broadcastSnapshot(committed, run, "cancel"); broadcastSnapshot(committed, run, "delete");
            int oldServer = serverPid(host); assertTrue(oldServer > 0);
            ProviderConnection lease = (ProviderConnection) RuntimeReflection.get(engine, "server"); assertNotNull(lease); lease.close();
            android.os.Process.killProcess(oldServer);
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (serverPid(host) == oldServer && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            assertTrue("Old server did not die", serverPid(host) != oldServer);
            // Its exact sealed guest must retire with that server, without a host reconnect hiding reuse.
            awaitGuestDeath(host, oldGuest, oldGuestDied);
            // The posted OS notification alone starts the cold server and the actual guest receiver.
            shell(ui, "cmd statusbar expand-notifications"); touch(ui, "Mylite cancel " + run);
            JSONObject delivered = await(event(installed, run, "cancel"));
            assertEquals(1, delivered.getInt("count"));
            assertFalse("Cold delivery reused a guest sealed to the dead server", oldGuest.getInt("pid") == delivered.getInt("pid"));
            assertTrue("Actual UI broadcast did not start a new server", serverPid(host) > 0 && serverPid(host) != oldServer);
            awaitAbsent(host, "Mylite work " + run);
            transfer(installed, run, action, deleted, "repost");
            JSONObject restored = awaitDestroyed(event(installed, run, "transferred"));
            assertTrue(restored.toString(), restored.getBoolean("reposted"));
            Notification after = notice(host, "Mylite restored " + run).getNotification();
            assertEquals("Cold requireToken/materialize replaced the original OS broadcast sender", action, after.actions[0].actionIntent);
            assertEquals(deleted, after.deleteIntent);
            shell(ui, "cmd statusbar expand-notifications"); touch(ui, "Mylite cancel " + run);
            awaitCount(event(installed, run, "cancel"), 2);
            dismiss(ui, host, "Mylite restored " + run); await(event(installed, run, "delete"));
            awaitAbsent(host, "Mylite restored " + run);
        } catch (Throwable failure) { primary = failure; throw failure; }
        finally {
            try { finishBroadcastTest(ui, info, flags, host, primary, oldGuest, null, run); }
            finally {
                if (oldGuest != null) try { oldGuest.getBinder("client").unlinkToDeath(oldGuestDeath, 0); }
                catch (java.util.NoSuchElementException alreadyDead) { }
            }
        }
    }

    public void testSystemUiRevokedAndOldInstallBroadcastsCannotDeliver() throws Throwable {
        Engine engine = Engine.get(); Context host = getInstrumentation().getTargetContext();
        UiAutomation ui = getInstrumentation().getUiAutomation();
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(host.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        InstalledPackage installed = notificationFixture(); String revoked = UUID.randomUUID().toString(), stale = UUID.randomUUID().toString(), current = UUID.randomUUID().toString();
        android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo(); int flags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; ui.setServiceInfo(info);
        Throwable primary = null; Bundle oldGuest = null, oldWorker = null;
        try {
            seedBroadcast(installed, revoked, false, false); awaitDestroyed(event(installed, revoked, "posted"));
            Notification revokedNotice = notice(host, "Mylite broadcast " + revoked).getNotification();
            OsPendingStore store = new OsPendingStore(engine);
            OsPendingStore.Snapshot revokedAction = broadcastSnapshot(store, revoked, "cancel");
            // Record the real sender before the guest actually cancels it; cancellation retires its journal.
            transfer(installed, revoked, revokedNotice.actions[0].actionIntent, revokedNotice.deleteIntent, "cancel");
            assertTrue(awaitDestroyed(event(installed, revoked, "transferred")).getBoolean("canceled"));
            assertNull("Guest cancellation left the original OS sender live", lookupBroadcast(host, store, revokedAction));
            touchActionOrRequireCanceledSender(ui, host, "Mylite cancel " + revoked, store, revokedAction);
            Thread.sleep(500); assertFalse("Revoked action reached the guest", event(installed, revoked, "cancel").exists());
            // A valid delete callback proves the negative was not merely an unavailable receiver/process.
            dismiss(ui, host, "Mylite broadcast " + revoked); await(event(installed, revoked, "delete"));
            cleanupNotices(host, revoked);
            seedBroadcast(installed, stale, false, false); awaitDestroyed(event(installed, stale, "posted"));
            notice(host, "Mylite broadcast " + stale);
            // The reader is a frozen disk snapshot; reload after the second real guest publication.
            OsPendingStore.Snapshot staleAction = broadcastSnapshot(new OsPendingStore(engine), stale, "cancel");
            oldGuest = fixtureIdentity(installed);
            oldWorker = fixtureIdentity(installed, installed.packageName + ":worker");
            assertTrue("The old receiver VM must actually exist before update", oldWorker.getBinder("client").pingBinder());
            assertTrue(oldGuest.getInt("pid") != oldWorker.getInt("pid"));
            assertEquals(read(event(installed, stale, "posted")).getInt("pid"), oldGuest.getInt("pid"));
            InstalledPackage replacement = engine.install(Collections.singletonList(installed.baseApk));
            assertFalse(installed.baseApk.equals(replacement.baseApk));
            touchActionOrRequireCanceledSender(ui, host, "Mylite cancel " + stale, store, staleAction);
            Thread.sleep(900); assertFalse("Old installation's action reached a new guest generation", event(installed, stale, "cancel").exists());
            dismiss(ui, host, "Mylite broadcast " + stale); Thread.sleep(400);
            assertFalse("Old installation's delete capability reached a new guest generation", event(installed, stale, "delete").exists());
            cleanupNotices(host, stale);
            awaitAbsent(host, "Mylite work " + stale);
            // The actual current installation must remain usable: old-capability rejection cannot
            // pass merely because every receiver or guest process is broken after the update.
            seedBroadcast(replacement, current, false, false); awaitDestroyed(event(replacement, current, "posted"));
            awaitGuestDeath(host, oldGuest);
            awaitGuestDeath(host, oldWorker);
            Notification currentNotice = notice(host, "Mylite broadcast " + current).getNotification();
            OsPendingStore currentStore = new OsPendingStore(engine);
            PendingIntent currentSender = lookupBroadcast(host, currentStore, broadcastSnapshot(currentStore, current, "cancel"));
            assertNotNull("The current installation's real OS sender was already canceled", currentSender);
            assertNotNull(currentNotice.actions); assertEquals(1, currentNotice.actions.length);
            assertEquals("Physical notification lost its actual sender", currentSender, currentNotice.actions[0].actionIntent);
            shell(ui, "cmd statusbar expand-notifications");
            retainNotificationUi(ui, host, "current", revoked, stale, current);
            touch(ui, "Mylite cancel " + current);
            JSONObject delivered = await(event(replacement, current, "cancel"));
            assertEquals(1, delivered.getInt("count"));
            assertTrue("Current action reused the retired receiver VM", delivered.getInt("pid") != oldWorker.getInt("pid"));
            awaitAbsent(host, "Mylite work " + current);
            dismiss(ui, host, "Mylite broadcast " + current);
            assertEquals(1, await(event(replacement, current, "delete")).getInt("count"));
            assertFalse(event(installed, stale, "cancel").exists()); assertFalse(event(installed, stale, "delete").exists());
        } catch (Throwable failure) {
            primary = failure;
            try { retainNotificationUi(ui, host, "failure", revoked, stale, current); }
            catch (Throwable diagnostic) { failure.addSuppressed(diagnostic); }
            throw failure;
        }
        finally { finishBroadcastTest(ui, info, flags, host, primary, oldGuest, installed.packageName, revoked, stale, current); }
    }

    public void testPhysicalNotificationContentAndActionOpenGuest() throws Exception {
        Context host = getInstrumentation().getTargetContext();
        Engine engine = Engine.get();
        UiAutomation automation = getInstrumentation().getUiAutomation();
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(host.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        InstalledPackage installed = engine.packages().get("io.github.xmy953538104.mybox.fixture");
        if (installed == null) {
            File apk = new File(host.getCacheDir(), "notification-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) {
                    Files.copy(input, apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                installed = engine.install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        String run = UUID.randomUUID().toString(), title = "Mylite notice " + run;
        File files = new File(installed.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare notification fixture");
        Files.write(new File(files, "notification-pending-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(installed, "notification-pending"); engine.launch(installed.packageName);
        await(new File(files, "notification-pending-posted-" + run + ".json"));
        NotificationManager manager = host.getSystemService(NotificationManager.class);
        StatusBarNotification posted = null;
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        do {
            for (StatusBarNotification candidate : manager.getActiveNotifications()) {
                if (title.contentEquals(candidate.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE, ""))) posted = candidate;
            }
            if (posted == null) Thread.sleep(50);
        } while (posted == null && SystemClock.elapsedRealtime() < deadline);
        assertNotNull("Android did not actually post the guest notification", posted);
        Notification notification = posted.getNotification();
        assertEquals(host.getPackageName(), posted.getPackageName());
        assertTrue(notification.contentIntent.isActivity());
        assertEquals(host.getPackageName(), notification.contentIntent.getCreatorPackage());
        assertEquals(1, notification.actions.length); assertTrue(notification.actions[0].actionIntent.isActivity());
        assertNotNull(notification.getSmallIcon().loadDrawable(host));
        assertFalse("Posting launched the target", new File(files, "notification-pending-event-" + run + "-content.json").exists());
        android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
        int flags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        try {
            shell(automation, "cmd statusbar expand-notifications");
            touch(automation, "Mylite action");
            assertEquals("action", await(new File(files, "notification-pending-event-" + run + "-action.json")).getString("kind"));
            shell(automation, "cmd statusbar expand-notifications");
            touch(automation, title);
            JSONObject result = await(new File(files, "notification-pending-event-" + run + "-content.json"));
            assertEquals("content", result.getString("kind")); assertEquals(installed.packageName, result.getString("package"));
            assertTrue(result.getInt("pid") > 0);
            System.out.println("MYLITE_NOTIFICATION physicalPost=true systemUiActionTouch=true systemUiContentTouch=true guestCallbacks=true");
        } finally {
            manager.cancel(posted.getId()); info.flags = flags; automation.setServiceInfo(info);
            shell(automation, "cmd statusbar collapse");
        }
    }

    private static void shell(UiAutomation automation, String command) throws Exception {
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024]; while (input.read(buffer) != -1) { }
        }
    }
    private InstalledPackage notificationFixture() throws Throwable {
        Engine engine = Engine.get(); InstalledPackage installed = engine.packages().get("io.github.xmy953538104.mybox.fixture");
        if (installed != null) return installed;
        File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "notification-broadcast-fixture.apk"); Throwable primary = null;
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk")) { Files.copy(input, apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            return engine.install(Collections.singletonList(apk));
        } catch (Throwable failure) { primary = failure; throw failure; }
        finally { try { Files.deleteIfExists(apk.toPath()); } catch (Throwable cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; } }
    }
    private void seedBroadcast(InstalledPackage installed, String run, boolean oneShot, boolean revoke) throws Exception {
        File files = new File(installed.dataDir, "files"); if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare notification probe");
        Files.write(new File(files, "notification-broadcast-request-" + run + ".json").toPath(),
                new JSONObject().put("oneShot", oneShot).put("revoke", revoke).toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(files, "notification-pending-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
        FixtureRun.prepare(installed, "notification-pending"); Engine.get().launch(installed.packageName);
    }
    private void transfer(InstalledPackage installed, String run, PendingIntent action, PendingIntent deleted, String operation) {
        Engine engine = Engine.get(); Intent intent = new Intent().setComponent(new ComponentName(installed.packageName,
                installed.packageName + ".pending.NotificationPendingProbe$Target")).putExtra("run", run)
                .putExtra("notification_broadcast_operation", operation).putExtra("action", action).putExtra("deleted", deleted)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Bundle request = EngineProtocol.response(); request.putString("operation", "prepare"); request.putParcelable("intent", intent);
        Bundle launch = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, request);
        // Same admission contract as Engine.launch: queued operations are dispatched by the
        // server's real lifecycle worker. The caller below still awaits the actual Target report.
        if (launch.getBoolean("queued")) return;
        Bundle outcome = ActivityEnvelope.operation(launch, "start_result", null);
        try { getInstrumentation().getTargetContext().startActivity(launch.getParcelable("outer_intent")); outcome.putBoolean("accepted", true); }
        catch (RuntimeException failure) { outcome.putBoolean("accepted", false); try { EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, outcome); } catch (RuntimeException report) { failure.addSuppressed(report); } throw failure; }
        EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, outcome);
    }
    private static File event(InstalledPackage installed, String run, String name) { return new File(installed.dataDir, "files/notification-broadcast-" + run + "-" + name + ".json"); }
    private static JSONObject read(File file) throws Exception { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
    private static JSONObject awaitDestroyed(File file) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000; JSONObject result = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile()) {
                result = read(file);
                if (result.getBoolean("destroyed")) { assertFalse(result.toString(), result.has("failure")); return result; }
            }
            Thread.sleep(40);
        }
        throw new AssertionError("Notification fixture did not really destroy its Activity: " + result);
    }
    private static void awaitCount(File file, int count) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < deadline) { if (file.isFile() && read(file).getInt("count") == count) return; Thread.sleep(40); }
        throw new AssertionError("No real broadcast count " + count);
    }
    private static StatusBarNotification notice(Context host, String title) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < deadline) { StatusBarNotification found = findNotice(host, title); if (found != null) return found; Thread.sleep(40); }
        throw new AssertionError("No physical notification " + title);
    }
    private static StatusBarNotification findNotice(Context host, String title) {
        for (StatusBarNotification item : host.getSystemService(NotificationManager.class).getActiveNotifications())
            if (title.contentEquals(item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE, ""))) return item;
        return null;
    }
    private static void awaitAbsent(Context host, String title) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (findNotice(host, title) != null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(40);
        assertNull("Physical notification survived actual cancel/delete", findNotice(host, title));
    }
    private static OsPendingStore.Snapshot broadcastSnapshot(OsPendingStore store, String run, String kind) {
        for (OsPendingStore.Record item : store.all()) if (item.snapshot.senderType == ServerPendingIntents.BROADCAST
                && run.equals(item.snapshot.intent.getStringExtra("run")) && kind.equals(item.snapshot.intent.getStringExtra("kind"))) {
            assertNull(item.journal); return item.snapshot;
        }
        throw new AssertionError("No committed broadcast notification snapshot");
    }
    private static PendingIntent lookupBroadcast(Context host, OsPendingStore store, OsPendingStore.Snapshot saved) {
        return PendingIntent.getBroadcast(host, 0, store.identity(saved), saved.flags | PendingIntent.FLAG_NO_CREATE);
    }
    private static void touchActionOrRequireCanceledSender(UiAutomation ui, Context host, String label,
            OsPendingStore store, OsPendingStore.Snapshot saved) throws Exception {
        shell(ui, "cmd statusbar expand-notifications"); long until = SystemClock.elapsedRealtime() + 15000;
        do {
            Rect target = visible(ui, label);
            if (target != null) {
                WebInputRuntimeTest.tap(ui, target.centerX(), target.centerY(), 50);
                System.out.println("MYLITE_NOTIFICATION_NEGATIVE actualSystemUiTouch=true");
                return;
            }
            // Android may disable/remove a canceled action. A missing node alone proves nothing;
            // require the original real OS record to be absent without creating or sending it.
            if (lookupBroadcast(host, store, saved) == null) {
                System.out.println("MYLITE_NOTIFICATION_NEGATIVE actualSystemUiTouch=false actualOsSenderCanceled=true");
                return;
            }
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
        fail("SystemUI action absent while its original OS sender remains live");
    }
    private static int serverPid(Context host) {
        for (ActivityManager.RunningAppProcessInfo process : host.getSystemService(ActivityManager.class).getRunningAppProcesses())
            if (process.uid == android.os.Process.myUid() && (host.getPackageName() + ":engine_server").equals(process.processName)) return process.pid;
        return -1;
    }
    private static Bundle fixtureIdentity(InstalledPackage installed) {
        return fixtureIdentity(installed, installed.packageName);
    }
    private static Bundle fixtureIdentity(InstalledPackage installed, String process) {
        Bundle request = EngineProtocol.response(); request.putString("package", installed.packageName);
        request.putString("process", process); request.putBoolean("include_runtime_state", true);
        Bundle identity = EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.ACQUIRE, request);
        Bundle runtime = identity.getBundle("runtime"); assertNotNull(runtime);
        assertEquals(installed.packageName, runtime.getString("package")); assertEquals(process, runtime.getString("process"));
        assertEquals(installed.baseApk.getAbsolutePath(), identity.getString("package_generation"));
        assertEquals(identity.getString("package_generation"), runtime.getString("package_generation"));
        assertEquals(identity.getInt("pid"), runtime.getInt("pid")); assertEquals(identity.getLong("generation"), runtime.getLong("generation"));
        assertNotNull(identity.getBinder("client")); assertTrue(identity.getBinder("client").pingBinder());
        return identity;
    }
    private static ActivityManager.RunningAppProcessInfo guestProcess(Context host, Bundle identity) {
        int slot = identity.getInt("slot", -1), pid = identity.getInt("pid", -1);
        assertTrue("Invalid recorded fixture slot", slot >= 0 && slot < EngineProtocol.SLOT_COUNT);
        assertTrue("Recorded guest cannot be the host", pid > 0 && pid != android.os.Process.myPid());
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = host.getSystemService(ActivityManager.class).getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) if (process.pid == pid) {
            assertEquals("Recorded PID no longer belongs to the exact host guest slot", host.getPackageName() + ":engine_p" + slot, process.processName);
            assertEquals(android.os.Process.myUid(), process.uid); return process;
        }
        return null;
    }
    private static void awaitGuestDeath(Context host, Bundle identity) throws Exception {
        awaitGuestDeath(host, identity, null);
    }
    private static void awaitGuestDeath(Context host, Bundle identity, java.util.concurrent.CountDownLatch death) throws Exception {
        android.os.IBinder client = identity.getBinder("client"); long until = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < until) {
            // BpBinder.isBinderAlive is cached until a transaction/death notification observes death.
            if (!client.pingBinder() && (death == null || death.getCount() == 0) && guestProcess(host, identity) == null) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Recorded fixture VM did not actually exit: pid=" + identity.getInt("pid"));
    }
    private static void retireRecordedGuest(Context host, Bundle identity) throws Exception {
        if (identity == null) return;
        android.os.IBinder client = identity.getBinder("client"); assertNotNull(client);
        if (client.pingBinder()) {
            // Never select by the shared fixture package or kill every :engine_p process.
            assertNotNull("Live fixture Binder has no matching OS guest process", guestProcess(host, identity));
            if (client.pingBinder()) android.os.Process.killProcess(identity.getInt("pid"));
        }
        awaitGuestDeath(host, identity);
    }
    private static Rect visible(UiAutomation ui, String text) {
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot(); window.recycle(); if (root == null) continue;
            try { Rect value = find(root, text); if (value != null && !value.isEmpty()) return value; } finally { root.recycle(); }
        }
        return null;
    }
    private static void retainNotificationUi(UiAutomation ui, Context host, String phase, String... runs) throws Exception {
        android.graphics.Bitmap display = ui.takeScreenshot(); assertNotNull(display);
        try (FileOutputStream output = host.openFileOutput("notification-ui-" + phase + ".png", Context.MODE_PRIVATE)) {
            assertTrue(display.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { display.recycle(); }
        StringBuilder structure = new StringBuilder(); int[] remaining = {512}; int systemRoots = 0;
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot(); window.recycle(); if (root == null) continue;
            try {
                if ("com.android.systemui".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                    systemRoots++;
                    notificationStructure(root, 0, remaining, structure, runs);
                }
            } finally { root.recycle(); }
        }
        structure.append("systemRoots=").append(systemRoots).append(" truncated=").append(remaining[0] <= 0).append('\n');
        Files.write(new File(host.getFilesDir(), "notification-ui-" + phase + ".txt").toPath(), structure.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void notificationStructure(AccessibilityNodeInfo node, int depth, int[] remaining, StringBuilder out, String[] runs) {
        if (depth > 30 || remaining[0]-- <= 0) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        String text = node.getText() == null ? "" : node.getText().toString(), label = text.isEmpty() ? "empty" : "redacted";
        for (String run : runs) for (String prefix : new String[]{"Mylite broadcast ", "Mylite cancel ", "Mylite work "}) {
            String expected = prefix + run;
            if (expected.equalsIgnoreCase(text)) label = expected + " caseExact=" + expected.equals(text);
        }
        out.append(depth).append(' ').append(node.getClassName()).append(" id=").append(node.getViewIdResourceName())
                .append(" bounds=").append(bounds).append(" visible=").append(node.isVisibleToUser())
                .append(" enabled=").append(node.isEnabled()).append(" clickable=").append(node.isClickable())
                .append(" actions=").append(node.getActions()).append(" label=").append(label).append('\n');
        for (int i = 0; i < node.getChildCount() && remaining[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { notificationStructure(child, depth + 1, remaining, out, runs); } finally { child.recycle(); }
        }
    }
    private static void dismiss(UiAutomation ui, Context host, String title) throws Exception {
        shell(ui, "cmd statusbar expand-notifications"); Rect row = null; long deadline = SystemClock.elapsedRealtime() + 10000;
        WebInputRuntimeTest.syncInput(ui);
        while (row == null && SystemClock.elapsedRealtime() < deadline) { row = visible(ui, title); if (row == null) Thread.sleep(40); }
        assertNotNull("Actual notification to dismiss was absent", row);
        android.graphics.Bitmap display = ui.takeScreenshot(); assertNotNull("Cannot measure the physical notification row", display);
        int width = display.getWidth(); display.recycle();
        // Swipe the actual card, not only the title's remaining right-hand half. A slow half-card
        // motion can snap back below SystemUI SwipeHelper's dismissal-distance threshold.
        long down = SystemClock.uptimeMillis(); float from = width * 0.12f, to = width * 0.96f, y = row.centerY();
        for (int step = 0; step <= 12; step++) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), step == 0 ? MotionEvent.ACTION_DOWN
                    : step == 12 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, from + (to - from) * step / 12, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { assertTrue(ui.injectInputEvent(event, true)); } finally { event.recycle(); }
            if (step < 12) Thread.sleep(20);
        }
        // Separate actual OS row removal from subsequent guest Receiver completion.
        awaitAbsent(host, title);
    }
    private static void cleanupNotices(Context host, String run) throws Exception {
        for (StatusBarNotification item : host.getSystemService(NotificationManager.class).getActiveNotifications())
            if (item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE, "").toString().endsWith(run)) {
                Notification value = item.getNotification();
                if (value.actions != null) for (Notification.Action action : value.actions) if (action.actionIntent != null) action.actionIntent.cancel();
                if (value.deleteIntent != null) value.deleteIntent.cancel();
                host.getSystemService(NotificationManager.class).cancel(item.getId());
            }
        // A dismissed row no longer exposes its non-one-shot deleteIntent. Retire those real OS
        // senders too; the server's existing cancellation listener/reconciliation owns journal cleanup.
        OsPendingStore store = new OsPendingStore(Engine.get());
        for (OsPendingStore.Record item : store.all()) if (item.snapshot.senderType == ServerPendingIntents.BROADCAST
                && run.equals(item.snapshot.intent.getStringExtra("run"))) {
            PendingIntent physical = PendingIntent.getBroadcast(host, 0, store.identity(item.snapshot), item.snapshot.flags | PendingIntent.FLAG_NO_CREATE);
            if (physical != null) physical.cancel();
        }
    }
    private static void finishBroadcastTest(UiAutomation ui, android.accessibilityservice.AccessibilityServiceInfo info,
            int flags, Context host, Throwable primary, String... runs) throws Throwable {
        finishBroadcastTest(ui, info, flags, host, primary, null, null, runs);
    }
    private static void finishBroadcastTest(UiAutomation ui, android.accessibilityservice.AccessibilityServiceInfo info,
            int flags, Context host, Throwable primary, Bundle oldGuest, String updatedPackage, String... runs) throws Throwable {
        Throwable failure = null;
        for (String run : runs) try { cleanupNotices(host, run); }
        catch (Throwable cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        try {
            retireRecordedGuest(host, oldGuest);
            if (oldGuest != null && updatedPackage != null) {
                InstalledPackage current = Engine.get().packages().get(updatedPackage); assertNotNull(current);
                if (!current.baseApk.getAbsolutePath().equals(oldGuest.getString("package_generation"))) {
                    // acquire's existing bounded retirement barrier must release the old reservation
                    // before it can bind the real new installation. Leave only that current VM reusable.
                    Bundle fresh = fixtureIdentity(current);
                    assertFalse("New install retained the old guest Binder", oldGuest.getBinder("client").equals(fresh.getBinder("client")));
                }
            }
        } catch (Throwable cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        try { info.flags = flags; ui.setServiceInfo(info); }
        catch (Throwable cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        try { shell(ui, "cmd statusbar collapse"); }
        catch (Throwable cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        if (failure != null) { if (primary != null) primary.addSuppressed(failure); else throw failure; }
    }
    private static JSONObject await(File file) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (!file.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertTrue("No real notification callback: " + file.getName(), file.isFile());
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    private static void touch(UiAutomation automation, String text) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        do {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot(); window.recycle();
                if (root == null) continue;
                Rect bounds;
                try { bounds = find(root, text); } finally { root.recycle(); }
                if (bounds != null && !bounds.isEmpty()) {
                    WebInputRuntimeTest.tap(automation, bounds.centerX(), bounds.centerY(), 50); return;
                }
            }
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Actual SystemUI touch target absent: " + text);
    }
    private static Rect find(AccessibilityNodeInfo node, String text) {
        if (node.isVisibleToUser() && text.contentEquals(node.getText() == null ? "" : node.getText())) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds); return bounds;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { Rect found = find(child, text); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }
}
