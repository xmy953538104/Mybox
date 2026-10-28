package io.github.xmy953538104.mybox.fixture.content;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONObject;

/** Synthetic URIs and public framework calls only. */
public final class ObserverProbe {
    private static final Uri ROOT = Uri.parse("content://io.github.xmy953538104.mybox.fixture.observers.remote");
    private static final class Watch extends ContentObserver {
        final boolean own;
        final ArrayList<Uri> uris = new ArrayList<>();
        final ArrayList<Integer> flags = new ArrayList<>();
        final ArrayList<Boolean> self = new ArrayList<>();
        Watch(boolean own) { super(null); this.own = own; }
        @Override public boolean deliverSelfNotifications() { return own; }
        @Override public void onChange(boolean changed, Uri uri) { onChange(changed, uri, 0); }
        @Override public synchronized void onChange(boolean changed, Uri uri, int flag) {
            uris.add(uri); flags.add(flag); self.add(changed); notifyAll();
        }
        synchronized void await(int count) throws Exception {
            long end = SystemClock.elapsedRealtime() + 10000;
            while (uris.size() < count && SystemClock.elapsedRealtime() < end) wait(Math.max(1, end - SystemClock.elapsedRealtime()));
            require(uris.size() == count, "Observer callback count differs: " + uris.size() + "/" + count);
        }
        synchronized int size() { return uris.size(); }
    }
    public static void run(Context context) { new Thread(() -> probe(context), "observer-fixture").start(); }
    private static void probe(Context context) {
        JSONObject report = new JSONObject(); ContentResolver resolver = context.getContentResolver();
        Watch exact = new Watch(false), tree = new Watch(false), nested = new Watch(false), sibling = new Watch(false), self = new Watch(true);
        Watch ordinarySync = new Watch(false), adapterSync = new Watch(false);
        Watch[] watches = {exact, tree, nested, sibling, self, ordinarySync, adapterSync};
        try {
            Uri a = Uri.withAppendedPath(ROOT, "a"), child = Uri.withAppendedPath(a, "b");
            resolver.registerContentObserver(a, false, exact); resolver.registerContentObserver(a, true, tree);
            resolver.registerContentObserver(child, false, nested);
            resolver.registerContentObserver(Uri.withAppendedPath(ROOT, "ab"), true, sibling);
            Bundle event = new Bundle(); event.putParcelable("uri", child); event.putInt("flags", ContentResolver.NOTIFY_UPDATE);
            Bundle peer = resolver.call(ROOT, "notify", null, event);
            require(peer != null && peer.getInt("pid") != Process.myPid(), "Notification did not use a separate guest process");
            tree.await(1); nested.await(1);
            require(exact.size() == 0 && sibling.size() == 0, "Descendant or path-segment isolation failed");
            require(child.equals(tree.uris.get(0)), "Original notification URI was lost");
            // This fixture/host targets 28: Android's legacy three-argument callback carries userId.
            require(tree.flags.get(0) == 0, "Legacy callback lost user zero");
            report.put("remoteDelivery", true); report.put("descendantsAndLegacyUser", true);
            resolver.notifyChange(a, null, 0); exact.await(1); tree.await(2); nested.await(2);
            resolver.notifyChange(a, exact, false); tree.await(3); nested.await(3);
            require(exact.size() == 1, "Self notification was not excluded");
            resolver.registerContentObserver(a, false, self); resolver.notifyChange(a, self, false);
            self.await(1); exact.await(2); tree.await(4); nested.await(4);
            require(self.self.get(0), "Requested self callback lost its origin"); report.put("ancestorAndSelf", true);
            resolver.notifyChange(a, null, ContentResolver.NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS);
            exact.await(3); nested.await(5); self.await(2);
            require(tree.size() == 4, "Skip-descendant flag was ignored"); report.put("skipDescendantFlag", true);
            for (Watch watch : watches) resolver.unregisterContentObserver(watch);
            int before = exact.size() + tree.size() + nested.size() + sibling.size() + self.size();
            resolver.notifyChange(a, null, 0); Thread.sleep(400);
            require(before == exact.size() + tree.size() + nested.size() + sibling.size() + self.size(), "Unregistered observer received a new callback");
            report.put("unregister", true);
            resolver.registerContentObserver(a, false, ordinarySync);
            resolver.notifyChange(a, null, ContentResolver.NOTIFY_SYNC_TO_NETWORK); ordinarySync.await(1);
            resolver.notifyChange(a, null); ordinarySync.await(2);
            report.put("syncWithoutAdapterNotifies", true);
            Uri sync = Uri.parse("content://io.github.xmy953538104.mybox.fixture.observers.sync/a");
            resolver.registerContentObserver(sync, false, adapterSync);
            try { resolver.notifyChange(sync, null, ContentResolver.NOTIFY_SYNC_TO_NETWORK); throw new AssertionError("Matching adapter execution was silently accepted"); }
            catch (RuntimeException expected) {
                require(expected.getMessage() != null && expected.getMessage().contains("Matching virtual sync adapter"), "Different failure hid adapter execution");
                adapterSync.await(1); report.put("syncAdapterRejectedAfterNotify", true);
            }
            android.content.ComponentName adapter = new android.content.ComponentName(context, SyncService.class);
            android.content.ComponentName invalid = new android.content.ComponentName(context, InvalidSyncService.class);
            android.content.pm.PackageManager pm = context.getPackageManager();
            try {
                pm.setComponentEnabledSetting(adapter, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP);
                resolver.notifyChange(sync, null, ContentResolver.NOTIFY_SYNC_TO_NETWORK); adapterSync.await(2);
                report.put("disabledSyncAdapterIsAbsent", true);
            } finally { pm.setComponentEnabledSetting(adapter, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, android.content.pm.PackageManager.DONT_KILL_APP); }
            try {
                pm.setComponentEnabledSetting(invalid, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP);
                try { resolver.notifyChange(a, null, ContentResolver.NOTIFY_SYNC_TO_NETWORK); throw new AssertionError("Unreadable adapter metadata became absence"); }
                catch (RuntimeException expected) {
                    require(expected.getMessage() != null && expected.getMessage().contains("metadata root"), "Different failure hid invalid adapter metadata");
                    ordinarySync.await(3); report.put("invalidSyncAdapterNotIgnored", true);
                }
            } finally { pm.setComponentEnabledSetting(invalid, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, android.content.pm.PackageManager.DONT_KILL_APP); }
            try { resolver.notifyChange(a, null, 0x40000000); throw new AssertionError("Unknown notification flags were accepted"); }
            catch (RuntimeException expected) { report.put("unknownFlagsRejected", true); }
            resolver.unregisterContentObserver(ordinarySync); resolver.unregisterContentObserver(adapterSync);
            // Five full process generations exceed the server's global quota unless death releases registrations.
            for (int round = 0; round < 5; round++) {
                try (android.content.ContentProviderClient client = resolver.acquireUnstableContentProviderClient(ROOT)) {
                    require(client != null, "No remote fixture Provider client");
                    java.lang.reflect.Field field = android.content.ContentProviderClient.class.getDeclaredField("mContentProvider"); field.setAccessible(true);
                    android.os.IBinder binder = ((android.os.IInterface) field.get(client)).asBinder();
                    java.util.concurrent.CountDownLatch died = new java.util.concurrent.CountDownLatch(1);
                    android.os.IBinder.DeathRecipient death = died::countDown; binder.linkToDeath(death, 0);
                    Bundle subscribed = client.call("subscribe", null, null);
                    require(subscribed != null && subscribed.getBoolean("quotaRejected"), "Observer quota was not enforced in generation " + round);
                    int peerPid = subscribed.getInt("pid"); require(peerPid > 0 && peerPid != Process.myPid(), "Invalid fixture peer PID");
                    Process.killProcess(peerPid);
                    require(died.await(10, java.util.concurrent.TimeUnit.SECONDS) && !binder.isBinderAlive(), "Remote Provider Binder did not die");
                    binder.unlinkToDeath(death, 0);
                }
            }
            report.put("quotaAndFiveProcessDeaths", true);
            report.put("pid", Process.myPid()); report.put("peerPid", peer.getInt("pid"));
        } catch (Throwable failure) { try { report.put("failure", failure.toString()); } catch (Exception ignored) { } }
        finally {
            for (Watch watch : watches) try { resolver.unregisterContentObserver(watch); } catch (Exception ignored) { }
            try {
                File temporary = new File(context.getFilesDir(), "content-observer-result.tmp");
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(report.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
                }
                if (!temporary.renameTo(new File(context.getFilesDir(), "content-observer-result.json"))) throw new IllegalStateException("Cannot publish observer report");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }
    public static class SyncService extends android.app.Service {
        @Override public android.os.IBinder onBind(android.content.Intent intent) { throw new AssertionError("Unsupported adapter must never be bound"); }
    }
    public static final class InvalidSyncService extends SyncService { }
    public static final class Remote extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String argument, Bundle extras) {
            if ("subscribe".equals(method)) {
                for (int index = 0; index < 64; index++) getContext().getContentResolver().registerContentObserver(
                        Uri.withAppendedPath(ROOT, "death/" + index), false, new Watch(false));
                boolean rejected = false;
                try { getContext().getContentResolver().registerContentObserver(ROOT, false, new Watch(false)); }
                catch (RuntimeException expected) { rejected = expected.getMessage() != null && expected.getMessage().contains("quota"); }
                Bundle result = new Bundle(); result.putInt("pid", Process.myPid()); result.putBoolean("quotaRejected", rejected); return result;
            }
            if (!"notify".equals(method) || extras == null) throw new IllegalArgumentException("Unknown fixture call");
            Uri uri = extras.getParcelable("uri");
            if (uri == null || !ROOT.getAuthority().equals(uri.getAuthority())) throw new SecurityException("Foreign fixture URI");
            getContext().getContentResolver().notifyChange(uri, null, extras.getInt("flags"));
            Bundle result = new Bundle(); result.putInt("pid", Process.myPid()); return result;
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private ObserverProbe() { }
}
