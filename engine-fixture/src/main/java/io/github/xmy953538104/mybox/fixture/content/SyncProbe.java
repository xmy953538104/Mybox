package io.github.xmy953538104.mybox.fixture.content;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import io.github.xmy953538104.mybox.fixture.accounts.FixtureAuthenticatorService;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Actual Android adapter, remote Provider mutation and cancellation; no engine API in the fixture. */
public final class SyncProbe {
    private static final String AUTHORITY = "io.github.xmy953538104.mybox.fixture.sync";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static void run(Context source) {
        Context context = source.getApplicationContext();
        new Thread(() -> {
            JSONObject report = new JSONObject(); AccountManager manager = AccountManager.get(context);
            long since = SystemClock.elapsedRealtime();
            Account account = new Account("fixture-sync-" + java.util.UUID.randomUUID(), FixtureAuthenticatorService.TYPE);
            boolean master = false, masterRead = false, added = false;
            try {
                master = ContentResolver.getMasterSyncAutomatically(); masterRead = true;
                require(manager.addAccountExplicitly(account, null, Bundle.EMPTY), "Synthetic sync account was not created"); added = true;
                boolean found = false;
                for (android.content.SyncAdapterType adapter : ContentResolver.getSyncAdapterTypes()) if (AUTHORITY.equals(adapter.authority)) {
                    require(account.type.equals(adapter.accountType) && adapter.supportsUploading() && adapter.isUserVisible(), "Real metadata was lost"); found = true;
                }
                require(found, "Declared adapter was not returned"); report.put("metadata", true);
                ordinaryBindingCannotImpersonateSystem(context, account, report);
                ContentResolver.setSyncAutomatically(account, AUTHORITY, false);
                ContentResolver.setIsSyncable(account, AUTHORITY, -1);
                require(ContentResolver.getIsSyncable(account, AUTHORITY) == -1, "Unknown syncability was overwritten by a disabled setting");
                context.getContentResolver().notifyChange(URI, null, ContentResolver.NOTIFY_SYNC_TO_NETWORK);
                await(() -> ContentResolver.getIsSyncable(account, AUTHORITY) == 1, 15000, "Real initialization did not set syncability");
                report.put("initializeThroughFramework", true);
                awaitIdle(account, 10000, "Initialization remained active");
                String disabled = java.util.UUID.randomUUID().toString();
                Bundle off = new Bundle(); off.putString("nonce", disabled);
                ContentResolver.requestSync(account, AUTHORITY, off); Thread.sleep(250);
                require(!new File(context.getFilesDir(), "sync-write-" + disabled + ".json").exists()
                        && !ContentResolver.isSyncPending(account, AUTHORITY) && !ContentResolver.isSyncActive(account, AUTHORITY), "Disabled automatic sync executed or remained pending");
                report.put("disabledSettingsSelectNoWork", true);
                String nonce = java.util.UUID.randomUUID().toString(); Bundle extras = new Bundle();
                extras.putString("nonce", nonce); extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                ContentResolver.requestSync(account, AUTHORITY, extras);
                File mutation = new File(context.getFilesDir(), "sync-write-" + nonce + ".json");
                await(mutation::isFile, 15000, "Actual adapter did not mutate remote Provider");
                JSONObject actual = read(mutation);
                require(nonce.equals(actual.getString("nonce")) && actual.getInt("adapterPid") != Process.myPid()
                        && actual.getInt("providerPid") != actual.getInt("adapterPid") && actual.getInt("providerPid") != Process.myPid(), "Sync did not cross actual processes");
                awaitIdle(account, 10000, "Actual onFinished did not retire work");
                report.put("remoteProviderMutation", true).put("realFinished", true);
                ContentResolver.setMasterSyncAutomatically(true);
                ContentResolver.setSyncAutomatically(account, AUTHORITY, true);
                String automatic = java.util.UUID.randomUUID().toString(); Bundle autoExtras = new Bundle(); autoExtras.putString("nonce", automatic);
                ContentResolver.requestSync(account, AUTHORITY, autoExtras);
                File autoMutation = new File(context.getFilesDir(), "sync-write-" + automatic + ".json");
                await(autoMutation::isFile, 15000, "Enabled automatic request did not mutate the real Provider");
                awaitIdle(account, 10000, "Automatic request did not actually finish");
                report.put("automaticMutationAndFinished", true);
                String hold = java.util.UUID.randomUUID().toString(); Bundle holdExtras = new Bundle(extras);
                holdExtras.putString("nonce", hold); holdExtras.putBoolean("hold", true);
                ContentResolver.requestSync(account, AUTHORITY, holdExtras);
                File started = new File(context.getFilesDir(), "sync-write-" + hold + ".json");
                await(started::isFile, 15000, "Cancel case did not enter the real adapter");
                require(ContentResolver.isSyncActive(account, AUTHORITY), "Executing adapter was not active");
                require(!ContentResolver.getCurrentSyncs().isEmpty(), "Current sync omitted actual work");
                ContentResolver.cancelSync(account, AUTHORITY);
                File canceled = new File(context.getFilesDir(), "sync-canceled-" + hold + ".json");
                await(canceled::isFile, 10000, "Real cancelSync did not interrupt the adapter");
                awaitIdle(account, 10000, "Canceled work remained active");
                report.put("realCancellation", true);
                Account wrong = new Account("fixture-absent", account.type);
                try { ContentResolver.requestSync(wrong, AUTHORITY, extras); throw new AssertionError("Invisible account was admitted"); }
                catch (SecurityException expected) { report.put("invisibleAccountRejected", true); }
                try { Bundle invalid = new Bundle(); invalid.putBinder("invalid", new android.os.Binder()); ContentResolver.requestSync(account, AUTHORITY, invalid); throw new AssertionError("Binder sync extra was accepted"); }
                catch (IllegalArgumentException expected) { report.put("invalidExtrasRejected", true); }
                report.put("pid", Process.myPid()).put("adapterPid", actual.getInt("adapterPid")).put("providerPid", actual.getInt("providerPid"));
            } catch (Throwable failure) { try { report.put("failure", failure.toString()); } catch (Exception ignored) { } }
            finally {
                try {
                    File wire = new File(context.getFilesDir(), "content-sync-wire.json");
                    org.json.JSONArray fresh = new org.json.JSONArray();
                    if (wire.isFile()) {
                        org.json.JSONArray rows = new org.json.JSONArray(new String(Files.readAllBytes(wire.toPath()), StandardCharsets.UTF_8));
                        for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).getLong("mono") >= since) fresh.put(rows.getJSONObject(i));
                    }
                    report.put("wire", fresh);
                } catch (Exception failure) { try { report.put("wireFailure", failure.getClass().getSimpleName()); } catch (Exception ignored) { } }
                try {
                    ContentResolver.cancelSync(account, AUTHORITY);
                    if (added) require(manager.removeAccountExplicitly(account), "Synthetic account cleanup failed");
                    if (masterRead) ContentResolver.setMasterSyncAutomatically(master);
                } catch (Throwable failure) { try { report.put("cleanupFailure", failure.toString()); } catch (Exception ignored) { } }
                try { write(new File(context.getFilesDir(), "content-sync-result.json"), report); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        }, "fixture-sync-probe").start();
    }
    private interface Check { boolean ready() throws Exception; }
    private static void ordinaryBindingCannotImpersonateSystem(Context context, Account account, JSONObject report) throws Exception {
        java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
        android.os.IBinder[] target = new android.os.IBinder[1];
        android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName component, android.os.IBinder binder) { target[0] = binder; connected.countDown(); }
            @Override public void onServiceDisconnected(android.content.ComponentName component) { target[0] = null; }
        };
        boolean bound = context.bindService(new Intent("android.content.SyncAdapter").setClass(context, Service.class), connection, Context.BIND_AUTO_CREATE);
        require(bound, "Ordinary guest binding was rejected");
        try {
            require(connected.await(10, java.util.concurrent.TimeUnit.SECONDS) && target[0] != null, "Ordinary binding did not connect");
            require("android.content.ISyncAdapter".equals(target[0].getInterfaceDescriptor()), "Ordinary guest obtained private sync control");
            Class<?> contextType = Class.forName("android.content.ISyncContext"), adapterType = Class.forName("android.content.ISyncAdapter");
            java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
            android.os.Binder callback = new android.os.Binder() {
                { attachInterface(null, "android.content.ISyncContext"); }
                @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                    if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString("android.content.ISyncContext"); return true; }
                    data.enforceInterface("android.content.ISyncContext"); callbacks.incrementAndGet();
                    if (reply != null) reply.writeNoException(); return true;
                }
            };
            Object syncContext = Class.forName("android.content.ISyncContext$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, callback);
            Object adapter = Class.forName("android.content.ISyncAdapter$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, target[0]);
            java.lang.reflect.Method start = adapterType.getMethod("startSync", contextType, String.class, Account.class, Bundle.class);
            String ordinary = java.util.UUID.randomUUID().toString(), spoofed = java.util.UUID.randomUUID().toString();
            Bundle extras = new Bundle(); extras.putString("nonce", ordinary);
            start.invoke(adapter, syncContext, AUTHORITY, account, extras);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                java.lang.reflect.Method set = android.os.Binder.class.getDeclaredMethod("setCallingWorkSourceUid", int.class);
                java.lang.reflect.Method restore = android.os.Binder.class.getDeclaredMethod("restoreCallingWorkSource", long.class);
                long token = (Long) set.invoke(null, 578875);
                try { Bundle spoof = new Bundle(); spoof.putString("nonce", spoofed); start.invoke(adapter, syncContext, AUTHORITY, account, spoof); }
                finally { restore.invoke(null, token); }
            }
            Thread.sleep(400);
            require(callbacks.get() == 0 && !new File(context.getFilesDir(), "sync-write-" + ordinary + ".json").exists()
                    && !new File(context.getFilesDir(), "sync-write-" + spoofed + ".json").exists(), "Untrusted caller passed the Android system guard");
            report.put("ordinaryBindingRejectedByFramework", true).put("workSourceSpoofRejected", true);
        } finally { context.unbindService(connection); }
    }
    private static void await(Check check, long timeout, String message) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (true) {
            if (check.ready()) return;
            require(SystemClock.elapsedRealtime() < end, message);
            Thread.sleep(25);
        }
    }
    private static void awaitIdle(Account account, long timeout, String message) throws Exception {
        long[] quietSince = {-1};
        await(() -> {
            // These are separate real APIs: pending work may become active between their replies.
            boolean idle = !ContentResolver.isSyncActive(account, AUTHORITY)
                    && !ContentResolver.isSyncPending(account, AUTHORITY) && !ContentResolver.isSyncActive(account, AUTHORITY);
            long now = SystemClock.elapsedRealtime();
            if (!idle) quietSince[0] = -1;
            else if (quietSince[0] < 0) quietSince[0] = now;
            return idle && now - quietSince[0] >= 250;
        }, timeout, message);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static JSONObject read(File file) throws Exception { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
    private static void write(File path, JSONObject value) throws Exception {
        File temporary = new File(path + ".new");
        try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(value.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
        Files.move(temporary.toPath(), path.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    public static final class Service extends android.app.Service {
        private AbstractThreadedSyncAdapter adapter;
        @Override public void onCreate() {
            super.onCreate(); adapter = new AbstractThreadedSyncAdapter(this, true) {
                @Override public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult result) {
                    String nonce = extras.getString("nonce");
                    if (nonce == null) return;
                    try {
                        Bundle values = new Bundle(); values.putString("nonce", nonce); values.putInt("adapterPid", Process.myPid());
                        Bundle actual = provider.call("write", null, values);
                        require(actual != null && actual.getBoolean("written"), "Provider mutation did not complete");
                        if (extras.getBoolean("hold")) {
                            try { Thread.sleep(60000); result.stats.numIoExceptions++; }
                            catch (InterruptedException canceled) { provider.call("canceled", null, values); Thread.currentThread().interrupt(); }
                        }
                    } catch (Exception failure) { result.stats.numIoExceptions++; }
                }
            };
        }
        @Override public android.os.IBinder onBind(Intent intent) {
            if (!"android.content.SyncAdapter".equals(intent.getAction())) return null;
            android.os.IBinder original = adapter.getSyncAdapterBinder();
            return new android.os.Binder() {
                { attachInterface(null, "android.content.ISyncAdapter"); }
                @Override protected synchronized boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
                    if (code == INTERFACE_TRANSACTION) { reply.writeString("android.content.ISyncAdapter"); return true; }
                    try {
                        File path = new File(getFilesDir(), "content-sync-wire.json");
                        org.json.JSONArray rows = path.isFile() ? new org.json.JSONArray(new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8)) : new org.json.JSONArray();
                        while (rows.length() >= 16) rows.remove(0);
                        int workSource = android.os.Build.VERSION.SDK_INT >= 29 ? (Integer) android.os.Binder.class.getDeclaredMethod("getCallingWorkSourceUid").invoke(null) : -1;
                        rows.put(new JSONObject().put("code", code).put("flags", flags).put("mono", SystemClock.elapsedRealtime())
                                .put("uid", android.os.Binder.getCallingUid()).put("pid", android.os.Binder.getCallingPid())
                                .put("processUid", Process.myUid()).put("workSource", workSource));
                        File temporary = new File(path + ".new");
                        try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(rows.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
                        Files.move(temporary.toPath(), path.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception failure) { throw new IllegalStateException("Synthetic adapter receipt could not be retained", failure); }
                    // Preserve the framework's real guard and callbacks; this diagnostic changes no UID.
                    return original.transact(code, data, reply, flags);
                }
            };
        }
    }
    public static final class Provider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String argument, Bundle extras) {
            String nonce = extras == null ? null : extras.getString("nonce");
            if (nonce == null || !nonce.matches("[0-9a-f-]{36}") || !"write".equals(method) && !"canceled".equals(method)) throw new IllegalArgumentException("Invalid synthetic sync mutation");
            try {
                JSONObject value = new JSONObject().put("nonce", nonce).put("providerPid", Process.myPid()).put("adapterPid", extras.getInt("adapterPid"));
                write(new File(getContext().getFilesDir(), "sync-" + method + "-" + nonce + ".json"), value);
                Bundle result = new Bundle(); result.putBoolean("written", true); return result;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
