package io.github.xmy953538104.mybox.engine.installer;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import io.github.xmy953538104.mybox.engine.CallerIdentity;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** User-0 virtual installer. Never obtains or delegates to the system PackageInstaller. */
public final class VirtualInstallerService {
    private final InstallerCallerResolver callers;
    private final InstallerStatusDispatcher statuses;
    private final InstallerBackend backend;
    private final SessionRepository repository;
    private final InstallerFiles files;
    private final Map<IBinder, OpenSession> opens = new HashMap<>();
    private final Map<IBinder, Listener> listeners = new HashMap<>();
    private final ThreadPoolExecutor commits = executor("mybox-install-commit", 1, 8);
    private final ThreadPoolExecutor notifications = executor("mybox-install-callback", 1, 128);
    private final Semaphore commitSlots = new Semaphore(8);
    private final IVirtualInstallerService.Stub binder = new IVirtualInstallerService.Stub() {
        @Override public Bundle execute(String operation, Bundle request, IVirtualInstallerCallback callback) throws RemoteException {
            if (request == null || request.getInt("version") != InstallerWire.VERSION || request.getInt("userId", 0) != 0) {
                throw new IllegalArgumentException("Invalid virtual installer protocol or user");
            }
            try {
                CallerIdentity caller = caller(InstallerWire.required(request, "opPackage"));
                return dispatch(operation, request, callback, caller);
            } catch (IOException failure) {
                Log.w("MyboxInstaller", "Virtual installer operation failed", failure); throw InstallerWire.io(failure);
            }
        }
        @Override public ParcelFileDescriptor openFile(String opPackage, int sessionId, String name, long offset,
                long length, boolean writing, IBinder lifetime) {
            try {
                CallerIdentity caller = caller(opPackage);
                if (writing) {
                    repository.get(caller, sessionId);
                    files.finishClosedWriters(sessionId);
                }
                SessionRepository.Lease lease = writing ? repository.openWrite(caller, sessionId, name, offset, length)
                        : repository.openRead(caller, sessionId, name);
                long identity = Binder.clearCallingIdentity();
                try { return files.open(sessionId, lease, writing, offset, lifetime); }
                finally { Binder.restoreCallingIdentity(identity); }
            } catch (IOException failure) {
                Log.w("MyboxInstaller", "Virtual installer " + (writing ? "openWrite" : "openRead") + " failed for session " + sessionId, failure);
                throw InstallerWire.io(failure);
            }
        }
    };

    public VirtualInstallerService(Context context, PackageStore packages, InstallerCallerResolver callers,
            InstallerAuthorization authorization, InstallerStatusDispatcher statuses) throws IOException {
        if (callers == null || authorization == null || statuses == null) throw new IllegalArgumentException("Installer security dependencies are required");
        this.callers = callers; this.statuses = statuses; backend = new PackageInstallerBackend(packages);
        repository = new SessionRepository(new File(context.getNoBackupFilesDir(), "virtual-installer"), backend, authorization);
        files = new InstallerFiles(context);
    }
    public IBinder asBinder() { return binder; }

    private CallerIdentity caller(String opPackage) throws IOException {
        CallerIdentity caller = callers.resolve(opPackage);
        if (caller == null || caller.userId != 0 || !caller.packageName.equals(opPackage)
                || !caller.certificateSha256.equals(backend.certificates(caller.packageName))) {
            throw new SecurityException("Installer caller is not a current registered identity");
        }
        return caller;
    }

    private Bundle dispatch(String operation, Bundle input, IVirtualInstallerCallback callback, CallerIdentity caller)
            throws IOException, RemoteException {
        Bundle result = new Bundle(); int id = input.getInt("id", -1);
        switch (operation) {
            case "describe": result.putInt("serverPid", Process.myPid()); result.putInt("serverUid", Process.myUid()); break;
            case "create": {
                id = repository.create(caller, new InstallRequest(input.getString("package"), input.getLong("size", -1), input.getString("label")));
                result.putInt("id", id); event(caller, "created", id, null); break;
            }
            case "info": result.putBundle("session", InstallerWire.snapshot(repository.get(caller, id), active(id))); break;
            case "list": {
                ArrayList<Bundle> values = new ArrayList<>();
                for (SessionSnapshot value : repository.list(caller)) values.add(InstallerWire.snapshot(value, active(value.id)));
                result.putParcelableArrayList("sessions", values); break;
            }
            case "open": {
                SessionSnapshot value = repository.get(caller, id);
                if (value == null || !(value.state.equals("OPEN") || value.state.equals("SEALED"))) throw new IllegalStateException("Installation session cannot be opened");
                IBinder token = input.getBinder("handle"); if (token == null) throw new IllegalArgumentException("Session handle is required");
                synchronized (opens) {
                    if (opens.size() >= 64 || opens.containsKey(token)) throw new IllegalStateException("Invalid or excessive open session handles");
                    OpenSession opened = new OpenSession(caller, id, token); token.linkToDeath(opened.death, 0); opens.put(token, opened);
                }
                event(caller, "active", id, Boolean.TRUE); break;
            }
            case "close": {
                repository.get(caller, id);
                IBinder token = input.getBinder("handle"); OpenSession opened;
                synchronized (opens) { opened = opens.get(token); }
                if (opened != null) {
                    if (opened.id != id || !same(opened.owner, caller)) throw new SecurityException("Session handle belongs to another owner");
                    close(opened);
                }
                break;
            }
            case "names": result.putStringArray("names", repository.names(caller, id)); break;
            case "progress":
                repository.progress(caller, id, input.getFloat("progress"), input.getBoolean("add"));
                event(caller, "progress", id, repository.get(caller, id).progress); break;
            case "label": repository.label(caller, id, input.getString("label")); event(caller, "badging", id, null); break;
            case "seal": repository.get(caller, id); files.finishClosedWriters(id); repository.seal(caller, id); break;
            case "abandon": {
                SessionSnapshot before = repository.get(caller, id);
                repository.abandon(caller, id); files.revoke(id);
                if (before != null && (before.state.equals("OPEN") || before.state.equals("SEALED"))) event(caller, "finished", id, Boolean.FALSE);
                break;
            }
            case "commit": commit(caller, id, input.getParcelable("receiver")); break;
            case "register": register(caller, callback); break;
            case "unregister": unregister(caller, callback); break;
            default: throw new UnsupportedOperationException("Unsupported virtual installer operation: " + operation);
        }
        return result;
    }

    private void commit(CallerIdentity caller, int id, IntentSender receiver) throws IOException {
        repository.get(caller, id);
        if (receiver == null) throw new IllegalArgumentException("An installation status receiver is required");
        InstallerStatusDispatcher.Delivery delivery = statuses.prepare(caller, receiver);
        if (delivery == null) throw new IllegalStateException("Virtual installation status delivery is unavailable");
        if (!commitSlots.tryAcquire()) throw new IllegalStateException("Too many installation commits are pending");
        try {
            files.finishClosedWriters(id);
            SessionRepository.CommitPlan plan = repository.prepareCommit(caller, id);
            commits.execute(() -> {
                try {
                    SessionRepository.CommitOutcome outcome = plan.run(); files.revoke(id);
                    Intent result = new Intent(); result.putExtra(PackageInstaller.EXTRA_SESSION_ID, id);
                    result.putExtra(PackageInstaller.EXTRA_STATUS, outcome.status); result.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, outcome.message);
                    if (outcome.session.packageName != null) result.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, outcome.session.packageName);
                    event(caller, "progress", id, Float.valueOf(1)); event(caller, "finished", id, outcome.status == 0);
                    try { delivery.send(result); }
                    catch (Exception failure) { Log.w("MyboxInstaller", "Installation status delivery failed; durable session state remains queryable"); }
                } finally { commitSlots.release(); }
            });
        } catch (IOException | RuntimeException failure) { commitSlots.release(); throw failure; }
    }

    private void register(CallerIdentity owner, IVirtualInstallerCallback callback) throws RemoteException {
        if (callback == null) throw new IllegalArgumentException("Installer callback is required");
        synchronized (listeners) {
            Listener old = listeners.get(callback.asBinder());
            if (old != null) { if (!same(old.owner, owner)) throw new SecurityException("Callback belongs to another owner"); return; }
            int owned = 0; for (Listener entry : listeners.values()) if (same(entry.owner, owner)) owned++;
            if (listeners.size() >= 32 || owned >= 8) throw new IllegalStateException("Too many installer callbacks");
            Listener listener = new Listener(owner, callback); callback.asBinder().linkToDeath(listener.death, 0); listeners.put(callback.asBinder(), listener);
        }
    }
    private void unregister(CallerIdentity owner, IVirtualInstallerCallback callback) {
        if (callback == null) return;
        synchronized (listeners) {
            Listener value = listeners.get(callback.asBinder());
            if (value == null) return;
            if (!same(value.owner, owner)) throw new SecurityException("Callback belongs to another owner");
            listeners.remove(callback.asBinder()); callback.asBinder().unlinkToDeath(value.death, 0);
        }
    }
    private void event(CallerIdentity owner, String event, int id, Object argument) {
        Bundle value = new Bundle(); value.putInt("id", id);
        if (argument instanceof Boolean) value.putBoolean("value", (Boolean) argument);
        if (argument instanceof Float) value.putFloat("value", (Float) argument);
        ArrayList<Listener> targets = new ArrayList<>();
        synchronized (listeners) { for (Listener listener : listeners.values()) if (same(owner, listener.owner)) targets.add(listener); }
        for (Listener target : targets) try {
            notifications.execute(() -> {
                try {
                    if (!target.owner.certificateSha256.equals(backend.certificates(target.owner.packageName))) return;
                    target.callback.onEvent(event, value);
                } catch (IOException | RemoteException | RuntimeException unavailable) { unregister(target.owner, target.callback); }
            });
        } catch (RejectedExecutionException overloaded) { Log.w("MyboxInstaller", "Installer callback queue is full"); }
    }
    private boolean active(int id) { synchronized (opens) { for (OpenSession value : opens.values()) if (value.id == id) return true; return false; } }
    private void close(OpenSession value) {
        synchronized (opens) { if (opens.remove(value.token) == null) return; value.token.unlinkToDeath(value.death, 0); }
        if (!active(value.id)) event(value.owner, "active", value.id, Boolean.FALSE);
    }
    private static boolean same(CallerIdentity a, CallerIdentity b) { return a.userId == b.userId && a.packageName.equals(b.packageName) && a.certificateSha256.equals(b.certificateSha256); }
    private static ThreadPoolExecutor executor(String name, int workers, int queued) {
        return new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queued), runnable -> new Thread(runnable, name));
    }
    private final class OpenSession {
        final CallerIdentity owner; final int id; final IBinder token;
        final IBinder.DeathRecipient death = () -> close(this);
        OpenSession(CallerIdentity owner, int id, IBinder token) { this.owner = owner; this.id = id; this.token = token; }
    }
    private final class Listener {
        final CallerIdentity owner; final IVirtualInstallerCallback callback;
        final IBinder.DeathRecipient death;
        Listener(CallerIdentity owner, IVirtualInstallerCallback callback) { this.owner = owner; this.callback = callback; death = () -> unregister(this.owner, this.callback); }
    }
}
