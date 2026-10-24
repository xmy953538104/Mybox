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
    private final Record[] slots = new Record[EngineProtocol.SLOT_COUNT];
    private long nextGeneration = 1;

    private static final class Record {
        final int slot;
        final long generation;
        final String packageName;
        final String processName;
        final String packageGeneration;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile IBinder client;
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

    ProcessRegistry(Engine engine) { this.engine = engine; }

    Bundle acquire(Bundle input) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) {
            throw new EngineException("Invalid process request protocol");
        }
        String packageName = input.getString("package");
        String processName = input.getString("process");
        GoogleLaunchPolicy.require(engine, packageName);
        InstalledPackage pkg = engine.packages().get(packageName);
        if (pkg == null || processName == null) throw new EngineException("Installed package/process missing");
        Record record = null;
        boolean create = false;
        synchronized (this) {
            int free = -1;
            for (int index = 0; index < slots.length; index++) {
                Record current = slots[index];
                if (current == null) {
                    if (free < 0) free = index;
                } else if (current.packageName.equals(packageName) && current.processName.equals(processName)) {
                    if (!current.packageGeneration.equals(pkg.baseApk.getAbsolutePath())) {
                        throw new EngineException("Package was updated while running; restart the host to load it");
                    }
                    record = current;
                    break;
                }
            }
            if (record == null) {
                if (free < 0) throw new EngineException("All four guest slots are occupied; restart the host");
                record = new Record(free, nextGeneration++, pkg, processName);
                slots[free] = record;
                create = true;
            }
        }
        if (create) initialize(record);
        if (!record.ready.await(35, TimeUnit.SECONDS)) throw new EngineException("Guest binding timed out");
        if (record.failure != null) throw new EngineException(record.failure);
        if (record.client == null || !record.client.isBinderAlive()) {
            throw new EngineException("Guest process died before launch");
        }
        Bundle result = EngineProtocol.response();
        result.putInt("slot", record.slot);
        result.putInt("pid", record.pid);
        result.putLong("generation", record.generation);
        result.putBinder("client", record.client);
        if (input.getBoolean("include_runtime_state")) {
            // Guest commands stay server-only; the authenticated host inspects through this broker.
            Bundle runtime = EngineProtocol.request(record.client, EngineProtocol.GUEST,
                    EngineProtocol.DESCRIBE, EngineProtocol.response());
            if (runtime.getInt("pid") != record.pid || runtime.getLong("generation") != record.generation) {
                throw new SecurityException("Guest state differs from its process registration");
            }
            result.putBundle("runtime", runtime);
        }
        return result;
    }

    private void initialize(final Record record) {
        ContentProviderClient provider = null;
        boolean bound = false;
        try {
            Bundle input = EngineProtocol.response();
            input.putString("package", record.packageName);
            input.putString("process", record.processName);
            input.putString("package_generation", record.packageGeneration);
            input.putInt("slot", record.slot);
            input.putLong("generation", record.generation);
            provider = engine.hostContext().getContentResolver().acquireContentProviderClient(Uri.parse("content://"
                    + engine.hostContext().getPackageName() + ".engine.guest.p" + record.slot));
            if (provider == null) throw new EngineException("Guest provider is unavailable");
            Bundle result = provider.call("prepare", null, input);
            if (result == null || result.getInt("protocol") != EngineProtocol.VERSION) {
                throw new EngineException("Guest provider returned an invalid handshake");
            }
            if (result.getString("error") != null) throw new EngineException(result.getString("error"));
            final IBinder client = result.getBinder("client");
            if (client == null || !client.isBinderAlive()) throw new EngineException("Guest binder is absent");
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
                synchronized (ProcessRegistry.this) {
                    if (slots[record.slot] == record && record.client == dead.binder) slots[record.slot] = null;
                }
                // ProviderConnection releases the lease after this callback, outside the monitor.
            });
            Bundle boundState = EngineProtocol.request(client, EngineProtocol.GUEST,
                    EngineProtocol.BIND_APPLICATION, EngineProtocol.response());
            if (!"BOUND".equals(boundState.getString("state"))) throw new EngineException("Guest Application did not bind");
            bound = true;
        } catch (Exception error) {
            // A partially initialized VM cannot be reassigned to another package safely.
            android.util.Log.e("MyliteProcesses", "Guest bootstrap failed: slot=" + record.slot
                    + " package=" + record.packageName + " process=" + record.processName, error);
            record.failure = "Guest slot " + record.slot + " failed: " + error.getMessage();
        } finally {
            if (provider != null) provider.close();
            if (!bound && record.connection != null) record.connection.close();
            record.ready.countDown();
        }
    }

    CallerIdentity resolveCaller(int uid, int pid) throws Exception {
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
        if (installed == null || !matched.packageGeneration.equals(installed.baseApk.getAbsolutePath())) {
            throw new SecurityException("Caller package generation is no longer current");
        }
        return new CallerIdentity(installed.packageName, installed.appId, pid, installed.certificateSha256);
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
