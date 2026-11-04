package io.github.xmy953538104.mybox.engine;

import android.accounts.Account;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SyncAdapterType;
import android.content.SyncResult;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import io.github.xmy953538104.mybox.engine.accounts.AccountService;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Virtual account selection and genuine SyncAdapter sessions; never calls the physical sync service. */
final class ServerContentSync {
    static final String WAKE = "io.github.xmy953538104.mybox.engine.SYNC_WAKE";
    private final Engine engine;
    private final AccountService accounts;
    private final ComponentRouter components;
    private final ContentSyncState store;
    private final Object lock = new Object();
    private final Map<Long, Session> active = new HashMap<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "MyliteSync"));
    private final java.util.concurrent.ThreadPoolExecutor bindings = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8), r -> new Thread(r, "MyliteSyncBind"));
    private final AtomicBoolean pumpQueued = new AtomicBoolean();
    private final PendingIntent wake;
    private final AlarmManager alarms;
    private ScheduledFuture<?> timer;

    ServerContentSync(Engine engine, AccountService accounts, ComponentRouter components) throws Exception {
        this.engine = engine; this.accounts = accounts; this.components = components;
        store = new ContentSyncState(new File(engine.hostContext().getFilesDir(), "mylite-sync/user0.json"));
        alarms = engine.hostContext().getSystemService(AlarmManager.class);
        wake = PendingIntent.getBroadcast(engine.hostContext(), 0,
                new Intent(engine.hostContext(), ContentSyncReceiver.class).setAction(WAKE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    void restore() {
        ConnectivityManager network = engine.hostContext().getSystemService(ConnectivityManager.class);
        try {
            network.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network value) { changed(); }
                @Override public void onCapabilitiesChanged(android.net.Network value, NetworkCapabilities caps) { changed(); }
            });
        } catch (RuntimeException failure) { EngineDiagnostics.failure("sync", "network_watch", 0, failure); }
        changed();
    }

    void changed() {
        if (pumpQueued.compareAndSet(false, true)) loop.execute(() -> {
            pumpQueued.set(false);
            try { pump(); } catch (Exception failure) { EngineDiagnostics.failure("sync", "pump", 0, failure); }
        });
    }

    Bundle route(Bundle input, ProcessRegistry.Identity process) throws Exception {
        CallerIdentity caller = EngineServerProvider.resolveCaller();
        String op = input.getString("operation").substring(5);
        Account account = input.getParcelable("account"); String authority = input.getString("authority");
        Bundle result = EngineProtocol.response();
        if (op.equals("addStatusChangeListener") || op.equals("removeStatusChangeListener")) {
            listener(input, process, op.startsWith("add")); return result;
        }
        List<ContentSyncAdapters.Adapter> adapters = ContentSyncAdapters.list(engine);
        if (op.equals("getSyncAdapterTypes")) {
            SyncAdapterType[] types = new SyncAdapterType[adapters.size()];
            for (int i = 0; i < types.length; i++) types[i] = adapters.get(i).type;
            result.putParcelableArray("value", types); return result;
        }
        if (op.equals("getSyncAdapterPackagesForAuthority") || op.equals("getSyncAdapterPackage")) {
            ContentSyncState.text(authority); ArrayList<String> packages = new ArrayList<>();
            for (ContentSyncAdapters.Adapter adapter : adapters) if (authority.equals(adapter.type.authority)
                    && (input.getString("type") == null || input.getString("type").equals(adapter.type.accountType)))
                if (!packages.contains(adapter.component.getPackageName())) packages.add(adapter.component.getPackageName());
            if (op.equals("getSyncAdapterPackage")) result.putString("value", packages.isEmpty() ? null : packages.get(0));
            else result.putStringArray("value", packages.toArray(new String[0])); return result;
        }
        if (op.startsWith("getMaster") || op.startsWith("setMaster")) {
            permission(caller, op.startsWith("set") ? "WRITE_SYNC_SETTINGS" : "READ_SYNC_SETTINGS");
            boolean changed = false;
            synchronized (lock) {
                if (op.startsWith("set")) {
                    boolean value = input.getBoolean("master"); changed = value != store.value.getBoolean("master");
                    if (changed) { JSONObject next = store.copy(); next.put("master", value); store.commit(next); }
                }
                else result.putBoolean("value", store.value.getBoolean("master"));
            }
            if (changed) {
                statusChanged(1);
                if (input.getBoolean("master")) enqueue(adapters, null, null, Bundle.EMPTY, 0, false, null);
                changed();
            }
            return result;
        }
        if (op.equals("getCurrentSyncs")) {
            permission(caller, "READ_SYNC_STATS"); ArrayList<Parcelable> current = new ArrayList<>();
            List<Account> visible = accounts.syncAccounts(caller, null);
            synchronized (lock) {
                for (Session session : active.values()) if (visible.contains(session.account)) current.add((Parcelable)
                        Class.forName("android.content.SyncInfo").getConstructor(int.class, Account.class, String.class, long.class)
                                .newInstance(setting(store.value, session.adapter, session.account, false).getInt("id"), session.account, session.adapter.type.authority, session.started));
            }
            result.putParcelableArrayList("value", current); return result;
        }
        if (authority != null) ContentSyncState.text(authority);
        if (account != null && !accounts.syncAccounts(caller, account.type).contains(account))
            throw new SecurityException("Sync account is not visible to the caller");
        if (op.equals("requestSync") || op.equals("sync")) {
            Bundle extras = input.getBundle("extras");
            ContentResolver.validateSyncExtrasBundle(extras == null ? Bundle.EMPTY : extras);
            long delay = input.getLong("delay");
            if (delay < 0 || delay > 604800) throw new IllegalArgumentException("Sync delay exceeds bound");
            enqueue(adapters, account, authority, extras, delay * 1000, false, caller); return result;
        }
        if (op.equals("cancelSync") || op.equals("cancelRequest")) {
            JSONObject wanted = op.equals("cancelRequest") ? ContentSyncState.extras(input.getBundle("extras")) : null;
            ArrayList<Session> canceled = new ArrayList<>(); List<Account> visible = accounts.syncAccounts(caller, null);
            synchronized (lock) {
                JSONObject next = store.copy(); JSONArray pending = next.getJSONArray("pending");
                for (int i = pending.length() - 1; i >= 0; i--) {
                    JSONObject entry = pending.getJSONObject(i); Account target = ContentSyncState.account(entry.getJSONObject("account"));
                    if (!visible.contains(target) || account != null && !account.equals(target)
                            || authority != null && !authority.equals(entry.getString("authority"))
                            || wanted != null && !sameExtras(wanted, entry.getJSONObject("extras"))) continue;
                    Session session = active.get(entry.getLong("id")); if (session != null) canceled.add(session);
                    pending.remove(i);
                }
                store.commit(next);
            }
            for (Session session : canceled) session.cancel(); statusChanged(2 | 4); changed(); return result;
        }
        if (op.equals("getPeriodicSyncs")) throw new UnsupportedOperationException("Virtual periodic sync is not implemented");
        boolean setting = op.equals("setIsSyncable") || op.equals("setSyncAutomatically");
        boolean readSetting = op.equals("getIsSyncable") || op.equals("getSyncAutomatically");
        if (setting || readSetting) {
            permission(caller, setting ? "WRITE_SYNC_SETTINGS" : "READ_SYNC_SETTINGS");
            if (account == null || authority == null) throw new IllegalArgumentException("Sync settings require account and authority");
            ContentSyncAdapters.Adapter adapter = find(adapters, account.type, authority);
            if (adapter == null) {
                if (setting) throw new IllegalArgumentException("No current virtual sync adapter");
                if (op.equals("getIsSyncable")) result.putInt("value", -1); else result.putBoolean("value", false); return result;
            }
            if (!accountsFor(adapter).contains(account)) throw new SecurityException("Adapter cannot access this sync account");
            boolean changed = false, schedule = false;
            synchronized (lock) {
                JSONObject next = store.copy(), entry = setting(next, adapter, account, setting);
                if (op.equals("setIsSyncable")) {
                    int value = Math.max(-1, Math.min(1, input.getInt("syncable")));
                    changed = value != entry.getInt("syncable"); schedule = changed && value > 0;
                    entry.put("syncable", value); if (changed && value == -1) entry.put("initialized", false);
                } else if (op.equals("setSyncAutomatically")) {
                    boolean value = input.getBoolean("auto"); changed = value != entry.getBoolean("auto"); schedule = changed && value;
                    entry.put("auto", value);
                }
                else if (op.equals("getIsSyncable")) result.putInt("value", entry == null ? -1 : entry.getInt("syncable"));
                else result.putBoolean("value", entry != null && entry.getBoolean("auto"));
                if (changed) store.commit(next);
            }
            if (changed) {
                statusChanged(1);
                if (schedule) enqueue(adapters, account, authority, Bundle.EMPTY, 0, false, caller);
                changed();
            }
            return result;
        }
        if (op.equals("isSyncActive") || op.equals("isSyncPending") || op.equals("getSyncStatus")) {
            permission(caller, "READ_SYNC_STATS"); List<Account> visible = accounts.syncAccounts(caller, null);
            synchronized (lock) {
                boolean found = false;
                if (op.equals("isSyncActive")) {
                    for (Session session : active.values()) if (visible.contains(session.account)
                            && (account == null || account.equals(session.account)) && (authority == null || authority.equals(session.adapter.type.authority))) found = true;
                } else if (op.equals("isSyncPending")) {
                    JSONArray pending = store.value.getJSONArray("pending");
                    for (int i = 0; i < pending.length(); i++) {
                        JSONObject entry = pending.getJSONObject(i); Account target = ContentSyncState.account(entry.getJSONObject("account"));
                        if (!active.containsKey(entry.getLong("id")) && visible.contains(target) && (account == null || account.equals(target))
                                && (authority == null || authority.equals(entry.getString("authority")))) found = true;
                    }
                } else {
                    if (account == null || authority == null) throw new IllegalArgumentException("Sync status requires account and authority");
                    ContentSyncAdapters.Adapter adapter = find(adapters, account.type, authority);
                    JSONObject entry = adapter == null ? null : setting(store.value, adapter, account, false);
                    if (entry != null) {
                        Class<?> type = Class.forName("android.content.SyncStatusInfo"); Object status = type.getConstructor(int.class).newInstance(entry.getInt("id"));
                        RuntimeReflection.field(type, "lastSuccessTime").setLong(status, entry.optLong("success"));
                        RuntimeReflection.field(type, "lastFailureTime").setLong(status, entry.optLong("failure"));
                        RuntimeReflection.field(type, "lastFailureMesg").set(status, entry.optString("error", null));
                        boolean pending = false;
                        JSONArray queue = store.value.getJSONArray("pending");
                        for (int i = 0; i < queue.length(); i++) { JSONObject work = queue.getJSONObject(i);
                            if (account.equals(ContentSyncState.account(work.getJSONObject("account"))) && authority.equals(work.getString("authority"))
                                    && !active.containsKey(work.getLong("id"))) pending = true; }
                        RuntimeReflection.field(type, "pending").setBoolean(status, pending);
                        result.putParcelable("value", (Parcelable) status);
                    }
                    return result;
                }
                result.putBoolean("value", found); return result;
            }
        }
        throw new UnsupportedOperationException("Unknown virtual sync operation");
    }

    void notifyChanges(Set<String> authorities) throws Exception {
        List<ContentSyncAdapters.Adapter> adapters = ContentSyncAdapters.list(engine);
        Bundle extras = new Bundle(); extras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true);
        for (String authority : authorities) enqueue(adapters, null, authority, extras, 30_000, true, null);
    }

    private void permission(CallerIdentity caller, String name) throws Exception {
        InstalledPackage installed = engine.packages().get(caller.packageName);
        if (installed == null || installed.appId != caller.appId || !installed.certificateSha256.equals(caller.certificateSha256))
            throw new SecurityException("Sync caller installation changed");
        PackageInfo info = engine.packages().getPackageInfo(caller.packageName, PackageManager.GET_PERMISSIONS);
        if (info == null || info.requestedPermissions == null || !Arrays.asList(info.requestedPermissions).contains("android.permission." + name))
            throw new SecurityException("Virtual sync permission is not declared");
    }

    private List<Account> accountsFor(ContentSyncAdapters.Adapter adapter) throws Exception {
        return accounts.syncAccounts(new CallerIdentity(adapter.installed.packageName, adapter.installed.appId, 0,
                adapter.installed.certificateSha256), adapter.type.accountType);
    }

    private static ContentSyncAdapters.Adapter find(List<ContentSyncAdapters.Adapter> adapters, String type, String authority) {
        for (ContentSyncAdapters.Adapter adapter : adapters) if (adapter.type.accountType.equals(type) && adapter.type.authority.equals(authority)) return adapter;
        return null;
    }

    private void enqueue(List<ContentSyncAdapters.Adapter> adapters, Account requested, String authority, Bundle input,
            long delay, boolean local, CallerIdentity caller) throws Exception {
        Bundle extras = input == null ? new Bundle() : new Bundle(input);
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL)) {
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true); extras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true);
        }
        JSONObject encoded = ContentSyncState.extras(extras);
        List<Account> callerAccounts = caller == null ? null : accounts.syncAccounts(caller, null);
        synchronized (lock) {
            JSONObject next = store.copy(); int selected = 0;
            for (ContentSyncAdapters.Adapter adapter : adapters) {
                if (authority != null && !authority.equals(adapter.type.authority)
                        || requested != null && !requested.type.equals(adapter.type.accountType)
                        || extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD) && !adapter.type.supportsUploading()) continue;
                for (Account account : accountsFor(adapter)) {
                    if (requested != null && !requested.equals(account) || callerAccounts != null && !callerAccounts.contains(account)) continue;
                    JSONObject settings = setting(next, adapter, account, true);
                    if (settings.getInt("syncable") < 0 && (Boolean) RuntimeReflection.invoke(adapter.type, SyncAdapterType.class.getMethod("isAlwaysSyncable")))
                        settings.put("syncable", 1);
                    if (settings.getInt("syncable") == 0) continue;
                    if (settings.getInt("syncable") < 0 && !settings.optBoolean("initialized")) {
                        Bundle initialize = new Bundle(); initialize.putBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, true);
                        add(next, adapter, account, ContentSyncState.extras(initialize), 0);
                        selected++;
                    }
                    if (!extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS)
                            && (!next.getBoolean("master") || !settings.getBoolean("auto"))) continue;
                    add(next, adapter, account, encoded, delay); selected++;
                }
            }
            store.commit(next);
            EngineDiagnostics.event("sync", local ? "local_selected" : "request_selected", 0, selected);
        }
        statusChanged(2); changed();
    }

    private void add(JSONObject root, ContentSyncAdapters.Adapter adapter, Account account, JSONObject extras, long delay) throws Exception {
        JSONArray pending = root.getJSONArray("pending"); long due = System.currentTimeMillis() + delay;
        for (int i = 0; i < pending.length(); i++) {
            JSONObject entry = pending.getJSONObject(i);
            // A notification arriving during execution must survive that execution's completion.
            if ((!active.containsKey(entry.getLong("id")) || ContentSyncState.extras(extras).getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE)) && matches(entry, adapter, account)
                    && sameExtras(entry.getJSONObject("extras"), extras)) {
                entry.put("due", Math.min(entry.getLong("due"), due)); return;
            }
        }
        long id = root.getLong("next"); if (id == Long.MAX_VALUE) throw new IllegalStateException("Sync ids exhausted");
        root.put("next", id + 1);
        pending.put(owner(adapter, account).put("id", id).put("generation", adapter.installed.baseApk.getAbsolutePath())
                .put("extras", extras).put("due", due).put("attempt", 0));
    }

    private static JSONObject owner(ContentSyncAdapters.Adapter adapter, Account account) throws Exception {
        return new JSONObject().put("component", adapter.component.flattenToString()).put("certificate", String.join(",", adapter.installed.certificateSha256))
                .put("appId", adapter.installed.appId).put("authority", adapter.type.authority).put("account", ContentSyncState.account(account));
    }
    private static boolean matches(JSONObject entry, ContentSyncAdapters.Adapter adapter, Account account) throws Exception {
        return entry.getString("component").equals(adapter.component.flattenToString()) && entry.getInt("appId") == adapter.installed.appId
                && entry.getString("certificate").equals(String.join(",", adapter.installed.certificateSha256))
                && entry.getString("authority").equals(adapter.type.authority) && account.equals(ContentSyncState.account(entry.getJSONObject("account")));
    }
    private static JSONObject setting(JSONObject root, ContentSyncAdapters.Adapter adapter, Account account, boolean create) throws Exception {
        JSONArray settings = root.getJSONArray("settings");
        for (int i = 0; i < settings.length(); i++) if (matches(settings.getJSONObject(i), adapter, account)) return settings.getJSONObject(i);
        if (!create) return null;
        int id = root.getInt("nextAuthority"); if (id == Integer.MAX_VALUE) throw new IllegalStateException("Sync authority ids exhausted");
        root.put("nextAuthority", id + 1);
        JSONObject result = owner(adapter, account).put("id", id).put("auto", false).put("syncable", -1); settings.put(result); return result;
    }
    private static boolean sameExtras(JSONObject first, JSONObject second) throws Exception {
        if (first.length() != second.length()) return false;
        for (java.util.Iterator<String> keys = first.keys(); keys.hasNext();) {
            String key = keys.next(); if (!second.has(key)) return false;
            JSONObject a = first.getJSONObject(key), b = second.getJSONObject(key);
            if (!a.getString("t").equals(b.getString("t"))) return false;
            if (a.has("v") != b.has("v")) return false;
            if (a.has("v") && !a.get("v").toString().equals(b.get("v").toString())) return false;
        }
        return true;
    }

    private void pump() throws Exception {
        List<ContentSyncAdapters.Adapter> adapters = ContentSyncAdapters.list(engine);
        ConnectivityManager connectivity = engine.hostContext().getSystemService(ConnectivityManager.class);
        NetworkCapabilities network = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        boolean online = network != null && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        ArrayList<Session> start = new ArrayList<>(), cancel = new ArrayList<>(), timedOut = new ArrayList<>(); long nextWake = Long.MAX_VALUE, now = System.currentTimeMillis();
        synchronized (lock) {
            JSONObject next = store.copy(); JSONArray pending = next.getJSONArray("pending"); boolean dirty = false;
            for (int i = pending.length() - 1; i >= 0; i--) {
                JSONObject work = pending.getJSONObject(i); Account account = ContentSyncState.account(work.getJSONObject("account"));
                ContentSyncAdapters.Adapter adapter = find(adapters, account.type, work.getString("authority"));
                long id = work.getLong("id"); Session current = active.get(id);
                if (adapter == null || !matches(work, adapter, account) || !work.getString("generation").equals(adapter.installed.baseApk.getAbsolutePath())
                        || !accountsFor(adapter).contains(account)) {
                    pending.remove(i); dirty = true; if (current != null) cancel.add(current);
                    EngineDiagnostics.event("sync", "invalidated", id, 0); continue;
                }
                Bundle extras = ContentSyncState.extras(work.getJSONObject("extras"));
                JSONObject settings = setting(next, adapter, account, true);
                boolean initialize = extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE);
                if (!initialize && (settings.getInt("syncable") == 0 || !extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS)
                        && (!next.getBoolean("master") || !settings.getBoolean("auto")))) {
                    pending.remove(i); dirty = true; if (current != null) cancel.add(current); continue;
                }
                if (current != null) {
                    if (SystemClock.elapsedRealtime() >= current.deadline) {
                        timedOut.add(current);
                    }
                    nextWake = Math.min(nextWake, now + (current.ended.get() ? 30_000
                            : Math.max(1000, current.deadline - SystemClock.elapsedRealtime()))); continue;
                }
                if (settings.getInt("syncable") == 0 || !initialize && settings.getInt("syncable") < 0) continue;
                if (!initialize && !extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS)
                        && (!next.getBoolean("master") || !settings.getBoolean("auto"))) continue;
                if (!initialize && (!online || extras.getBoolean("allow_metered") && connectivity.isActiveNetworkMetered())) {
                    nextWake = Math.min(nextWake, now + 60_000); continue;
                }
                long due = Math.max(work.getLong("due"), settings.optLong("delayUntil"));
                if (!extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF)) due = Math.max(due, settings.optLong("backoff"));
                if (due > now) { nextWake = Math.min(nextWake, due); continue; }
                boolean busy = active.size() >= 2;
                for (Session session : active.values()) if (session.adapter.component.equals(adapter.component)
                        && (session.account.equals(account) || !(Boolean) RuntimeReflection.invoke(adapter.type, SyncAdapterType.class.getMethod("allowParallelSyncs")))) busy = true;
                if (busy) { nextWake = Math.min(nextWake, now + 30_000); continue; }
                Session session = new Session(work, adapter, account, extras); active.put(id, session); start.add(session);
                nextWake = Math.min(nextWake, now + 30_000);
            }
            if (dirty) try { store.commit(next); }
            catch (Exception failure) { for (Session session : start) active.remove(session.id); throw failure; }
        }
        for (Session session : cancel) session.cancel();
        for (Session session : timedOut) session.timeout();
        for (Session session : start) {
            EngineDiagnostics.event("sync", "bind", session.id, session.extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE) ? 1 : 0);
            try { bindings.execute(session::bind); }
            catch (java.util.concurrent.RejectedExecutionException failure) { session.failed(failure); }
        }
        schedule(nextWake); if (!start.isEmpty() || !cancel.isEmpty()) statusChanged(4);
    }

    private void schedule(long at) {
        if (timer != null) timer.cancel(false);
        if (at == Long.MAX_VALUE) { alarms.cancel(wake); return; }
        long delay = Math.max(1000, at - System.currentTimeMillis());
        timer = loop.schedule(this::changed, delay, TimeUnit.MILLISECONDS);
        long elapsed = SystemClock.elapsedRealtime() + Math.max(30_000, Math.min(7_200_000, delay));
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, wake);
            else alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, wake);
        } catch (RuntimeException failure) { EngineDiagnostics.failure("sync", "wake_schedule", 0, failure); }
    }

    private final class Session extends Binder implements ServerBoundServices.Connection {
        final long id, started = SystemClock.elapsedRealtime();
        final ContentSyncAdapters.Adapter adapter; final Account account; final Bundle extras; final JSONObject work;
        final AtomicBoolean ended = new AtomicBoolean();
        final AtomicBoolean connected = new AtomicBoolean();
        final java.util.concurrent.ExecutorService control = Executors.newSingleThreadExecutor(r -> new Thread(r, "MyliteSyncControl"));
        volatile boolean finalized;
        volatile long deadline = started + 300_000;
        volatile ProcessRegistry.Identity owner;
        volatile ServerBoundServices.Binding binding;
        volatile IBinder target;
        final int heartbeatCode, finishedCode;

        Session(JSONObject work, ContentSyncAdapters.Adapter adapter, Account account, Bundle extras) throws Exception {
            id = work.getLong("id"); this.work = work; this.adapter = adapter; this.account = account; this.extras = extras;
            Class<?> stub = Class.forName("android.content.ISyncContext$Stub");
            heartbeatCode = RuntimeReflection.field(stub, "TRANSACTION_sendHeartbeat").getInt(null);
            finishedCode = RuntimeReflection.field(stub, "TRANSACTION_onFinished").getInt(null);
            attachInterface(null, "android.content.ISyncContext");
        }
        void bind() {
            if (ended.get()) return;
            try {
                ServerBoundServices.Binding actual = components.bindInternal(adapter.component,
                        new Intent("android.content.SyncAdapter").setComponent(adapter.component),
                        Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND | Context.BIND_ALLOW_OOM_MANAGEMENT, this);
                binding = actual;
                if (finalized) actual.close();
                else if (!actual.admitted()) failed(new EngineException("Android rejected sync adapter binding"));
            } catch (Exception failure) { failed(failure); }
        }
        @Override public void onConnected(IBinder binder, ProcessRegistry.Identity process) {
            if (ended.get() || !connected.compareAndSet(false, true)) return;
            try {
                    if (binder == null || !binder.isBinderAlive()
                            || !adapter.installed.packageName.equals(process.packageName)
                            || !adapter.installed.baseApk.getAbsolutePath().equals(process.packageGeneration)) throw new SecurityException("Sync binding identity changed");
                    owner = process; target = binder;
                    control.execute(() -> {
                        if (ended.get()) return;
                        try {
                            SyncAdapterControl.dispatch(binder, false, id, this, adapter.type.authority, account, extras);
                            EngineDiagnostics.event("sync", "start_ack", id, 0);
                        } catch (Exception failure) { failed(failure); }
                    });
            } catch (Exception failure) { failed(failure); }
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString("android.content.ISyncContext"); return true; }
            if (code != heartbeatCode && code != finishedCode) return false;
            data.enforceInterface("android.content.ISyncContext");
            ProcessRegistry.Identity caller = EngineServerProvider.registeredProcess(Binder.getCallingUid(), Binder.getCallingPid());
            ProcessRegistry.Identity expected = owner;
            if (expected == null || !expected.packageName.equals(caller.packageName) || !expected.packageGeneration.equals(caller.packageGeneration))
                throw new SecurityException("Sync completion caller differs from its bound adapter");
            try {
                InstalledPackage installed = engine.packages().get(expected.packageName);
                if (installed == null || !expected.packageGeneration.equals(installed.baseApk.getAbsolutePath()))
                    throw new SecurityException("Sync completion installation changed");
            } catch (java.io.IOException failure) { throw new SecurityException("Sync completion installation is unavailable", failure); }
            SyncResult result = code == finishedCode && data.readInt() != 0 ? SyncResult.CREATOR.createFromParcel(data) : null;
            if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected sync callback fields");
            if (code == finishedCode && result == null) throw new IllegalArgumentException("Missing actual sync result");
            if (result != null && (result.delayUntil < 0 || result.delayUntil > Long.MAX_VALUE / 1000))
                throw new IllegalArgumentException("Invalid adapter delay");
            if (code == heartbeatCode && !ended.get()) deadline = Math.min(started + 1_800_000, SystemClock.elapsedRealtime() + 300_000);
            else if (code == finishedCode) complete(result, false, false);
            if (reply != null) reply.writeNoException(); return true;
        }
        void failed(Exception failure) {
            if (ended.get()) return;
            EngineDiagnostics.failure("sync", "adapter", id, failure); SyncResult result = new SyncResult(); result.stats.numIoExceptions = 1;
            complete(result, false, true);
        }
        void cancel() {
            complete(null, true, false);
        }
        void timeout() {
            failed(new EngineException("Sync adapter execution timed out"));
        }
        @Override public void onNullBinding(ProcessRegistry.Identity owner) { failed(new EngineException("Sync adapter returned a null binding")); }
        @Override public void onDisconnected(ProcessRegistry.Identity owner) { failed(new EngineException("Sync adapter disconnected")); }
        @Override public void onBindingDied(ProcessRegistry.Identity owner) { failed(new EngineException("Sync adapter binding died")); }
        @Override public void onFailure(Exception failure) { failed(failure); }
        private void complete(SyncResult result, boolean canceled, boolean transportFailure) {
            if (!ended.compareAndSet(false, true)) return;
            // Serialize cancellation after actual start dispatch; do not retire a live control call.
            control.execute(() -> {
                try {
                    if ((canceled || transportFailure) && target != null)
                        SyncAdapterControl.dispatch(target, true, id, this, adapter.type.authority, account, extras);
                } catch (Exception failure) { EngineDiagnostics.failure("sync", "cancel", id, failure); }
                finally { retire(result, canceled, transportFailure); control.shutdown(); }
            });
        }
        private void retire(SyncResult result, boolean canceled, boolean transportFailure) {
            loop.execute(() -> {
                try {
                    synchronized (lock) {
                        JSONObject next = store.copy(); JSONArray pending = next.getJSONArray("pending"); JSONObject current = null;
                        for (int i = 0; i < pending.length(); i++) if (pending.getJSONObject(i).getLong("id") == id) { current = pending.getJSONObject(i); pending.remove(i); break; }
                        if (current != null && !canceled) {
                            JSONObject settings = setting(next, adapter, account, true);
                            if (result.delayUntil > 0 && result.delayUntil <= Long.MAX_VALUE / 1000) settings.put("delayUntil", result.delayUntil * 1000);
                            if (result.hasError()) {
                                settings.put("failure", System.currentTimeMillis()).put("error", result.hasSoftError() ? "io-error" : "adapter-error");
                                if ((transportFailure || result.hasSoftError() || result.syncAlreadyInProgress)
                                        && !extras.getBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY)) {
                                    int attempt = Math.min(20, current.getInt("attempt") + 1);
                                    current.put("attempt", attempt).put("due", Math.max(System.currentTimeMillis() + Math.min(3_600_000, 30_000L << Math.min(attempt - 1, 7)), result.delayUntil * 1000));
                                    settings.put("backoff", current.getLong("due"));
                                    pending.put(current);
                                }
                            } else {
                                settings.put("success", System.currentTimeMillis()).put("backoff", 0); settings.remove("error");
                                if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE)) settings.put("initialized", true);
                                if (result.fullSyncRequested) add(next, adapter, account, ContentSyncState.extras(Bundle.EMPTY), Math.max(0, result.delayUntil * 1000 - System.currentTimeMillis()));
                            }
                        }
                        if (current != null) store.commit(next);
                        active.remove(id);
                    }
                    EngineDiagnostics.event("sync", canceled ? "canceled" : transportFailure ? "transport_failed" : result.hasError() ? "finished_error" : "finished", id, 0);
                } catch (Exception failure) {
                    // Durable pending work remains retryable after a failed completion commit.
                    synchronized (lock) { active.remove(id); }
                    EngineDiagnostics.failure("sync", "completion_commit", id, failure);
                } finally {
                    finalized = true;
                    ServerBoundServices.Binding current = binding;
                    if (current != null) try { current.close(); } catch (Exception failure) { EngineDiagnostics.failure("sync", "unbind", id, failure); }
                    statusChanged(2 | 4 | 8); changed();
                }
            });
        }
    }

    private final class Listener implements IBinder.DeathRecipient {
        final ProcessRegistry.Identity owner; final IBinder binder; final int mask;
        Listener(ProcessRegistry.Identity owner, IBinder binder, int mask) { this.owner = owner; this.binder = binder; this.mask = mask; }
        @Override public void binderDied() {
            synchronized (lock) { listeners.remove(this); }
            binder.unlinkToDeath(this, 0); owner.client.unlinkToDeath(this, 0);
        }
    }
    private void listener(Bundle input, ProcessRegistry.Identity owner, boolean add) throws Exception {
        IBinder binder = input.getBinder("listener");
        if (binder == null || !"android.content.ISyncStatusObserver".equals(binder.getInterfaceDescriptor())) throw new SecurityException("Invalid sync status observer");
        synchronized (lock) {
            for (Listener entry : new ArrayList<>(listeners)) if (entry.owner.same(owner) && entry.binder.equals(binder)) entry.binderDied();
            if (!add) return;
            int count = 0; for (Listener entry : listeners) if (entry.owner.same(owner)) count++;
            if (listeners.size() >= 128 || count >= 16) throw new IllegalStateException("Sync status observer quota exceeded");
            int mask = input.getInt("mask"); if ((mask & ~15) != 0 || mask == 0) throw new IllegalArgumentException("Invalid sync status mask");
            Listener entry = new Listener(owner, binder, mask); binder.linkToDeath(entry, 0);
            try { owner.client.linkToDeath(entry, 0); }
            catch (Exception failure) { binder.unlinkToDeath(entry, 0); throw failure; }
            listeners.add(entry);
        }
    }
    private void statusChanged(int mask) {
        ArrayList<Listener> targets; synchronized (lock) { targets = new ArrayList<>(listeners); }
        for (Listener listener : targets) if ((listener.mask & mask) != 0) {
            try {
                ServerBoundServices.CALLBACKS.execute(() -> {
                    try {
                        Class<?> type = Class.forName("android.content.ISyncStatusObserver");
                        Object callback = RuntimeReflection.invoke(null, Class.forName("android.content.ISyncStatusObserver$Stub").getMethod("asInterface", IBinder.class), listener.binder);
                        RuntimeReflection.invoke(callback, type.getMethod("onStatusChanged", int.class), mask & listener.mask);
                    } catch (Exception failure) { listener.binderDied(); EngineDiagnostics.failure("sync", "status_callback", 0, failure); }
                });
            } catch (java.util.concurrent.RejectedExecutionException failure) { EngineDiagnostics.failure("sync", "status_backpressure", 0, failure); }
        }
    }
}
