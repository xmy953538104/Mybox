package io.github.xmy953538104.mybox.engine;

import android.content.ContentProviderClient;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Owns process-slot reservations. No framework IPC is performed while holding its monitor. */
final class ProcessRegistry {
    private final Engine engine;
    private final IBinder serverBinder;
    private final java.util.concurrent.ThreadPoolExecutor alarmBootstrap = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(4));
    private final Record[] slots = new Record[EngineProtocol.SLOT_COUNT];
    private long nextGeneration = 1;
    private volatile DeathListener deathListener;
    private ServerExitHistory exitHistory;

    interface DeathListener { void died(Identity identity, ProviderConnection connection); }

    /** A server-derived reservation, never reconstructed from guest-supplied identity fields. */
    static final class Identity {
        final String packageName, processName, packageGeneration;
        final int pid, slot;
        final long generation;
        final IBinder client, applicationThread;
        Identity(Record record) {
            packageName = record.packageName; processName = record.processName;
            packageGeneration = record.packageGeneration; pid = record.pid;
            slot = record.slot; generation = record.generation; client = record.client;
            applicationThread = record.applicationThread;
        }
        boolean same(Identity other) {
            return other != null && pid == other.pid && generation == other.generation
                    && client.equals(other.client) && packageGeneration.equals(other.packageGeneration)
                    && packageName.equals(other.packageName) && processName.equals(other.processName);
        }
    }

    void setDeathListener(DeathListener listener) { deathListener = listener; }
    void setExitHistory(ServerExitHistory history) { exitHistory = history; }

    private static final class Record {
        final int slot;
        final long generation;
        final String packageName;
        final String processName;
        final String packageGeneration;
        final IBinder diagnosticToken = new android.os.Binder();
        final IBinder retirementToken = new android.os.Binder();
        final CountDownLatch preparedReady = new CountDownLatch(1);
        final CountDownLatch ready = new CountDownLatch(1);
        boolean binding;
        final CountDownLatch died = new CountDownLatch(1);
        volatile IBinder client;
        volatile IBinder applicationThread;
        volatile ProviderConnection connection;
        volatile String failure;
        volatile int pid;
        Record(int slot, long generation, InstalledPackage pkg, String processName) {
            this.slot = slot;
            this.generation = generation;
            packageName = pkg.packageName;
            this.processName = processName;
            packageGeneration = pkg.baseApk.getAbsolutePath();
        }
    }

    ProcessRegistry(Engine engine, IBinder serverBinder) { this.engine = engine; this.serverBinder = serverBinder; }

    Bundle acquire(Bundle input) throws Exception {
        return acquire(input, 0);
    }

    Bundle acquirePrepared(Bundle input) throws Exception { return acquire(input, 0, false); }

    Bundle acquire(Bundle input, long deadline) throws Exception { return acquire(input, deadline, true); }

    private Bundle acquire(Bundle input, long deadline, boolean applicationRequired) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) {
            throw new EngineException("Invalid process request protocol");
        }
        String packageName = input.getString("package");
        String processName = input.getString("process");
        final long until = deadline == 0 ? android.os.SystemClock.elapsedRealtime() + 35_000 : deadline;
        for (;;) {
            if (remaining(input, deadline, until) == 0) throw new EngineException("Guest binding timed out");
            GoogleLaunchPolicy.require(engine, packageName);
            InstalledPackage pkg = engine.packages().get(packageName);
            if (pkg == null || processName == null) throw new EngineException("Installed package/process missing");
            if (!retireOldInstallation(pkg, input, deadline, until)) continue;
            Record record = null, retiring = null;
            Record[] occupied = null;
            boolean create = false;
            synchronized (this) {
                int free = -1;
                for (int index = 0; index < slots.length; index++) {
                    Record current = slots[index];
                    if (current == null) {
                        if (free < 0) free = index;
                    } else {
                        if (current.packageName.equals(packageName) && current.processName.equals(processName)) {
                            record = current;
                            break;
                        }
                        if (retiring == null && current.client != null && !current.client.isBinderAlive()) retiring = current;
                    }
                }
                if (record == null) {
                    if (free < 0) {
                        if (retiring == null) occupied = slots.clone();
                        else record = retiring;
                    } else {
                        record = new Record(free, nextGeneration++, pkg, processName);
                        slots[free] = record;
                        create = true;
                    }
                }
            }
            if (occupied != null) {
                for (Record peer : occupied) {
                    if (remaining(input, deadline, until) == 0) throw new EngineException("Guest binding timed out");
                    if (confirmedDeadAfterProbe(peer.client)) {
                        retireBeforeReuse(peer, input, deadline, until);
                        break;
                    }
                }
                synchronized (this) {
                    boolean unchanged = true;
                    for (int index = 0; index < slots.length; index++) if (slots[index] != occupied[index]) { unchanged = false; break; }
                    if (unchanged) throw new EngineException("All " + slots.length + " guest slots are occupied; restart the host");
                }
                continue;
            }
            // A previous VM can be dead while its ordered journal/listener retirement is still running.
            // Do this before generation checks: an already-dead old version is not a running update conflict.
            if (!create && record.client != null && !record.client.isBinderAlive()) {
                retireBeforeReuse(record, input, deadline, until);
                continue;
            }
            if (!record.packageGeneration.equals(pkg.baseApk.getAbsolutePath())) {
                continue; // Another update raced the snapshot; reload the committed installation.
            }
            if (create) {
                if (deadline == 0) initialize(record, null);
                else {
                    Record reserved = record;
                    Bundle budget = new Bundle(input);
                    try { alarmBootstrap.execute(() -> initialize(reserved, budget)); }
                    catch (java.util.concurrent.RejectedExecutionException full) {
                        synchronized (this) { if (slots[reserved.slot] == reserved) slots[reserved.slot] = null; }
                        throw new EngineException("Alarm bootstrap queue is full", full);
                    }
                }
            }
            long available = remaining(input, deadline, until);
            if (available == 0 || !record.preparedReady.await(available, TimeUnit.MILLISECONDS)) throw new EngineException("Guest preparation timed out");
            if (applicationRequired && record.failure == null) {
                ensureBound(record, deadline == 0 ? null : input);
                available = remaining(input, deadline, until);
                if (available == 0 || !record.ready.await(available, TimeUnit.MILLISECONDS)) throw new EngineException("Guest binding timed out");
            }
            // Never erase this request's actual bootstrap failure by launching another Application.
            if (record.failure != null) throw new EngineException(record.failure);
            IBinder client = record.client;
            if (client == null) throw new EngineException("Guest binding completed without a client");
            if (!client.isBinderAlive()) {
                retireBeforeReuse(record, input, deadline, until);
                continue;
            }
            Bundle result = EngineProtocol.response();
            result.putInt("slot", record.slot); result.putInt("pid", record.pid);
            result.putLong("generation", record.generation); result.putString("package_generation", record.packageGeneration);
            result.putBinder("client", client);
            if (input.getBoolean("include_runtime_state")) {
                // DESCRIBE is read-only; only an actual DeadObject plus this same Binder's death permits recovery.
                Bundle runtime;
                try { runtime = EngineProtocol.request(client, EngineProtocol.GUEST, EngineProtocol.DESCRIBE, EngineProtocol.response()); }
                catch (EngineException failure) {
                    if (!(failure.getCause() instanceof android.os.DeadObjectException) || client.isBinderAlive()) throw failure;
                    retireBeforeReuse(record, input, deadline, until);
                    continue;
                }
                if (runtime.getInt("pid") != record.pid || runtime.getLong("generation") != record.generation) {
                    throw new SecurityException("Guest state differs from its process registration");
                }
                result.putBundle("runtime", runtime);
            }
            if (remaining(input, deadline, until) == 0) throw new EngineException("Guest binding timed out");
            if (!client.isBinderAlive()) {
                retireBeforeReuse(record, input, deadline, until);
                continue;
            }
            return result;
        }
    }

    private static long remaining(Bundle input, long externalDeadline, long until) {
        if (externalDeadline != 0) AlarmDeliveryTicket.remaining(input, 8_000);
        return Math.max(0, until - android.os.SystemClock.elapsedRealtime());
    }

    private boolean retireOldInstallation(InstalledPackage installed, Bundle input, long deadline, long until) throws Exception {
        java.util.List<Record> old = new java.util.ArrayList<>();
        synchronized (this) {
            InstalledPackage current = engine.packages().get(installed.packageName);
            if (current == null || !current.baseApk.equals(installed.baseApk)) return false;
            // New reservations cannot appear between this current-installation check and selection.
            for (Record record : slots) if (record != null && record.packageName.equals(installed.packageName)
                    && !record.packageGeneration.equals(installed.baseApk.getAbsolutePath())) old.add(record);
        }
        // shortcut: Reconcile all old VMs at first use; an immediate package-commit barrier is still required.
        for (Record record : old) {
            long available = remaining(input, deadline, until);
            if (available == 0 || !record.preparedReady.await(available, TimeUnit.MILLISECONDS))
                throw new EngineException("Old installation preparation did not finish before retirement");
            synchronized (this) { if (slots[record.slot] != record) continue; }
            IBinder client = record.client;
            if (client == null) throw new EngineException("Old installation has no authenticated retirement endpoint");
            if (!client.pingBinder()) continue;
            Bundle request = EngineProtocol.response();
            request.putBinder("retirement_token", record.retirementToken);
            request.putLong("generation", record.generation); request.putString("package_generation", record.packageGeneration);
            android.os.Parcel parcel = android.os.Parcel.obtain();
            try {
                parcel.writeInterfaceToken(EngineProtocol.GUEST); parcel.writeBundle(request);
                if (!client.transact(EngineProtocol.RETIRE_GUEST, parcel, null, IBinder.FLAG_ONEWAY))
                    throw new EngineException("Old guest does not support authenticated retirement");
            } catch (android.os.DeadObjectException gone) {
                if (client.pingBinder()) throw gone;
            } finally { parcel.recycle(); }
        }
        for (Record record : old) {
            synchronized (this) { if (slots[record.slot] != record) continue; }
            long available = remaining(input, deadline, until);
            if (available == 0 || !record.died.await(available, TimeUnit.MILLISECONDS))
                throw new EngineException("Old installation did not actually retire within this request's budget");
            retireBeforeReuse(record, input, deadline, until);
        }
        return true;
    }

    private void retireBeforeReuse(Record record, Bundle input, long externalDeadline, long until) throws InterruptedException {
        remaining(input, externalDeadline, until);
        awaitConfirmedRetirement(record.client, record.died, until);
        long available = remaining(input, externalDeadline, until);
        if (record.connection != null && (available == 0 || !record.connection.awaitReleased(available)))
            throw new EngineException("Guest provider lease was not released before reuse");
        synchronized (this) {
            if (slots[record.slot] == record) throw new EngineException("Guest death did not retire its reservation");
        }
    }

    static boolean confirmedDeadAfterProbe(IBinder client) {
        if (client == null) return false;
        // isBinderAlive is cached per proxy; perform IPC outside registry locks, never trust a failed ping alone.
        if (client.isBinderAlive()) client.pingBinder();
        return !client.isBinderAlive();
    }

    static void awaitConfirmedRetirement(IBinder client, CountDownLatch retired, long until) throws InterruptedException {
        if (client == null || client.isBinderAlive()) throw new EngineException("A live or unassigned Binder cannot authorize retirement");
        long available = Math.max(0, until - android.os.SystemClock.elapsedRealtime());
        if (available == 0 || !retired.await(available, TimeUnit.MILLISECONDS)) throw new EngineException("Guest retirement timed out");
    }

    private void initialize(final Record record, Bundle budget) {
        ContentProviderClient provider = null;
        boolean prepared = false;
        try {
            Bundle input = EngineProtocol.response();
            input.putString("package", record.packageName);
            input.putString("process", record.processName);
            input.putString("package_generation", record.packageGeneration);
            input.putInt("slot", record.slot);
            input.putLong("generation", record.generation);
            input.putBinder("server_binder", serverBinder);
            input.putBinder("diagnostic_token", record.diagnosticToken);
            input.putBinder("retirement_token", record.retirementToken);
            if (budget != null) {
                input.putLong(AlarmDeliveryTicket.DEADLINE, budget.getLong(AlarmDeliveryTicket.DEADLINE));
                input.putBinder(AlarmDeliveryTicket.TICKET, budget.getBinder(AlarmDeliveryTicket.TICKET));
                AlarmDeliveryTicket.remaining(input, 8_000);
            }
            // Binder death retires this reservation; a stable lease would also kill the engine server.
            provider = engine.hostContext().getContentResolver().acquireUnstableContentProviderClient(Uri.parse("content://"
                    + engine.hostContext().getPackageName() + ".engine.guest.p" + record.slot));
            if (provider == null) throw new EngineException("Guest provider is unavailable");
            AlarmDeliveryTicket.remaining(input, 35_000);
            Bundle result;
            try { result = provider.call("prepare", null, input); }
            catch (android.os.DeadObjectException stale) {
                // ContentProviderClient invalidates its unstable framework cache on this exact error.
                provider.close(); provider = null;
                AlarmDeliveryTicket.remaining(input, 35_000);
                provider = engine.hostContext().getContentResolver().acquireUnstableContentProviderClient(Uri.parse("content://"
                        + engine.hostContext().getPackageName() + ".engine.guest.p" + record.slot));
                if (provider == null) throw new EngineException("Replacement guest provider is unavailable", stale);
                android.util.Log.i("MyboxProvider", "Reacquiring guest bootstrap after cached Provider death");
                result = provider.call("prepare", null, input);
            }
            if (result == null || result.getInt("protocol") != EngineProtocol.VERSION) {
                throw new EngineException("Guest provider returned an invalid handshake");
            }
            if (result.getString("error") != null) throw new EngineException(result.getString("error"));
            final IBinder client = result.getBinder("client");
            if (client == null || !client.isBinderAlive()) throw new EngineException("Guest binder is absent");
            AlarmDeliveryTicket.remaining(input, 35_000);
            Bundle state = EngineProtocol.request(client, EngineProtocol.GUEST,
                    EngineProtocol.DESCRIBE, EngineProtocol.response());
            if (!record.packageName.equals(state.getString("package"))
                    || !record.processName.equals(state.getString("process"))
                    || !record.packageGeneration.equals(state.getString("package_generation"))
                    || state.getLong("generation") != record.generation
                    || !("PREPARED".equals(state.getString("state")) || "BOUND".equals(state.getString("state")))) {
                throw new EngineException("Guest bound identity does not match its reservation");
            }
            record.pid = state.getInt("pid");
            record.client = client;
            ContentProviderClient ownedProvider = provider;
            provider = null;
            record.connection = ProviderConnection.connect(client, ownedProvider, dead -> {
                EngineDiagnostics.event("engine", "process_died", record.pid, record.generation);
                if (exitHistory != null && android.os.Build.VERSION.SDK_INT >= 30) exitHistory.retired(new Identity(record));
                boolean removed = false;
                synchronized (ProcessRegistry.this) {
                    if (slots[record.slot] == record && record.client == dead.binder) {
                        slots[record.slot] = null;
                        removed = true;
                    }
                }
                DeathListener listener = deathListener;
                try {
                    if (removed && listener != null) listener.died(new Identity(record), dead);
                }
                finally { record.died.countDown(); }
                // ProviderConnection releases the lease after this callback, outside the monitor.
            });
            // Returned by prepare before guest code runs, never supplied by an Activity API caller.
            IBinder applicationThread = result.getBinder("application_thread");
            if (applicationThread == null || !applicationThread.isBinderAlive()
                    || !"android.app.IApplicationThread".equals(applicationThread.getInterfaceDescriptor()))
                throw new EngineException("Guest framework ApplicationThread handshake is invalid");
            record.applicationThread = applicationThread;
            if (exitHistory != null && android.os.Build.VERSION.SDK_INT >= 30) {
                exitHistory.seal(new Identity(record));
                // Death can beat seal after linkToDeath; retire the newly written reservation too.
                if (!client.isBinderAlive()) {
                    exitHistory.retired(new Identity(record));
                    throw new android.os.DeadObjectException();
                }
            }
            prepared = true;
        } catch (Exception error) {
            capturePeerStacks(record);
            // A partially initialized VM cannot be reassigned to another package safely.
            android.util.Log.e("MyliteProcesses", "Guest bootstrap failed: slot=" + record.slot
                    + " package=" + record.packageName + " process=" + record.processName, error);
            record.failure = "Guest slot " + record.slot + " failed: " + error.getMessage();
        } finally {
            if (provider != null) provider.close();
            if (!prepared && record.connection != null) {
                if (record.pid > 0 && record.client != null) {
                    // Only this validated reservation may be retired. Keep the slot/lease until its actual death.
                    if (record.client.isBinderAlive()) android.os.Process.killProcess(record.pid);
                } else record.connection.close();
            }
            record.preparedReady.countDown();
            if (!prepared) record.ready.countDown();
        }
    }

    private void ensureBound(Record record, Bundle budget) {
        synchronized (record) {
            if (record.binding || record.failure != null) return;
            record.binding = true;
        }
        try {
            Bundle request = EngineProtocol.response();
            if (budget != null) {
                request.putLong(AlarmDeliveryTicket.DEADLINE, budget.getLong(AlarmDeliveryTicket.DEADLINE));
                request.putBinder(AlarmDeliveryTicket.TICKET, budget.getBinder(AlarmDeliveryTicket.TICKET));
            }
            Bundle result = EngineProtocol.request(record.client, EngineProtocol.GUEST, EngineProtocol.BIND_APPLICATION, request);
            if (!"BOUND".equals(result.getString("state"))) throw new EngineException("Guest Application did not bind");
        } catch (Exception failure) {
            capturePeerStacks(record);
            record.failure = "Guest slot " + record.slot + " failed: " + failure.getMessage();
            android.util.Log.e("MyliteProcesses", "Guest Application bootstrap failed", failure);
            if (record.pid > 0 && record.client != null && record.client.isBinderAlive()) android.os.Process.killProcess(record.pid);
        } finally { record.ready.countDown(); }
    }

    /** Host-authenticated Activity wait diagnosis: existing reservations only, with no returned capability. */
    Bundle captureActivityWait(Bundle request) throws Exception {
        if (request.getInt("protocol") != EngineProtocol.VERSION) throw new SecurityException("Invalid Activity diagnostic protocol");
        String name = request.getString("package"), sealed = request.getString("package_generation");
        if (name == null || name.isEmpty() || sealed == null || sealed.isEmpty())
            throw new SecurityException("Activity diagnostic scope is missing");
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null || !sealed.equals(installed.baseApk.getAbsolutePath()))
            throw new SecurityException("Activity diagnostic installation is no longer current");
        java.util.List<Record> selected = new java.util.ArrayList<>();
        synchronized (this) {
            for (Record record : slots) if (record != null && name.equals(record.packageName)
                    && sealed.equals(record.packageGeneration) && record.failure == null
                    && record.client != null && record.client.isBinderAlive()) selected.add(record);
        }
        if (selected.isEmpty()) throw new SecurityException("Activity diagnostic has no current guest reservation");
        GuestRuntime.logThreadStacks(engine.hostContext().getPackageName(), "engine_server", "ACTIVITY_WAIT_FAILED");
        for (Record record : selected) {
            android.os.Parcel input = android.os.Parcel.obtain();
            try {
                Bundle ticket = EngineProtocol.response(); ticket.putBinder("diagnostic_token", record.diagnosticToken);
                input.writeInterfaceToken(EngineProtocol.GUEST); input.writeBundle(ticket);
                if (!record.client.transact(EngineProtocol.DUMP_THREADS, input, null, IBinder.FLAG_ONEWAY))
                    throw new EngineException("Guest Activity diagnostic transaction is unavailable");
            } finally { input.recycle(); }
        }
        Bundle result = EngineProtocol.response(); result.putInt("requested_processes", selected.size()); return result;
    }

    private void capturePeerStacks(Record failed) {
        GuestRuntime.logThreadStacks(engine.hostContext().getPackageName(), "engine_server", "BOOTSTRAP_FAILED");
        Record[] peers;
        synchronized (this) { peers = slots.clone(); }
        for (Record peer : peers) {
            if (peer == null || peer == failed || peer.client == null || !peer.client.isBinderAlive()) continue;
            android.os.Parcel input = android.os.Parcel.obtain();
            try {
                Bundle ticket = EngineProtocol.response(); ticket.putBinder("diagnostic_token", peer.diagnosticToken);
                input.writeInterfaceToken(EngineProtocol.GUEST); input.writeBundle(ticket);
                peer.client.transact(EngineProtocol.DUMP_THREADS, input, null, IBinder.FLAG_ONEWAY);
            } catch (android.os.RemoteException dead) {
                android.util.Log.w("MyliteProcesses", "Peer exited before asynchronous stack capture");
            } finally { input.recycle(); }
        }
    }

    boolean awaitDeath(IBinder client) throws InterruptedException {
        Record matched = null;
        synchronized (this) {
            for (Record record : slots) if (record != null && client.equals(record.client)) { matched = record; break; }
        }
        // Never reassign a live VM just because an arbitrary transaction/ping failed.
        return matched == null ? !client.isBinderAlive() : matched.died.await(1000, TimeUnit.MILLISECONDS);
    }

    CallerIdentity resolveCaller(int uid, int pid) throws Exception {
        Identity matched = identity(uid, pid);
        InstalledPackage installed = engine.packages().get(matched.packageName);
        if (installed == null || !matched.packageGeneration.equals(installed.baseApk.getAbsolutePath())) {
            throw new SecurityException("Caller package generation is no longer current");
        }
        return new CallerIdentity(installed.packageName, installed.appId, pid, installed.certificateSha256);
    }

    Identity identity(int uid, int pid) throws Exception { return identity(uid, pid, true); }
    Identity identityForRetirement(int uid, int pid) throws Exception { return identity(uid, pid, false); }
    private Identity identity(int uid, int pid, boolean requireInstallation) throws Exception {
        if (uid != android.os.Process.myUid()) throw new SecurityException("Caller UID is outside this host");
        Record matched = null;
        synchronized (this) {
            for (Record record : slots) {
                if (record != null && record.pid == pid && record.client != null
                        && record.client.isBinderAlive() && record.failure == null) {
                    matched = record;
                    break;
                }
            }
        }
        if (matched == null) throw new SecurityException("Caller PID has no assigned guest identity");
        InstalledPackage installed = engine.packages().get(matched.packageName);
        if (requireInstallation && (installed == null || !matched.packageGeneration.equals(installed.baseApk.getAbsolutePath()))) {
            throw new SecurityException("Caller package generation is no longer current");
        }
        synchronized (this) {
            if (slots[matched.slot] != matched || !matched.client.isBinderAlive()) {
                throw new SecurityException("Caller process reservation expired");
            }
            return new Identity(matched);
        }
    }

    synchronized Bundle find(String packageName, String processName) {
        for (Record record : slots) {
            if (record != null && record.packageName.equals(packageName) && record.processName.equals(processName)
                    && record.client != null && record.client.isBinderAlive() && record.failure == null) {
                Bundle result = EngineProtocol.response();
                result.putBinder("client", record.client);
                result.putInt("pid", record.pid);
                result.putInt("slot", record.slot);
                result.putLong("generation", record.generation);
                return result;
            }
        }
        return null;
    }

    synchronized IBinder clientForCaller(CallerIdentity caller) {
        for (Record record : slots) {
            if (record != null && record.pid == caller.pid && record.packageName.equals(caller.packageName)
                    && record.client != null && record.client.isBinderAlive() && record.failure == null) {
                return record.client;
            }
        }
        throw new SecurityException("Registered caller process is no longer live");
    }
}
