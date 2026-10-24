package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import io.github.xmy953538104.mybox.engine.accounts.AccountUiAuthorizer;
import io.github.xmy953538104.mybox.engine.accounts.AuthenticatorRegistration;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.lang.ref.WeakReference;

/** Ephemeral, exact-consumer authority for an authenticator's Activity delivery. */
final class ServerAccountUiTickets implements AccountUiAuthorizer {
    interface TaskPolicy { void requireFresh(android.content.ComponentName component, String generation); }
    static final String EXTRA = "io.github.xmy953538104.mybox.ACCOUNT_UI_TICKET";
    private static final long TTL = 300000;
    private static final int LIMIT = 64;
    private final Engine engine;
    private final ProcessRegistry processes;
    private final Map<IBinder, Ticket> tickets = new HashMap<>();
    private final android.os.Handler expiry = new android.os.Handler(android.os.Looper.getMainLooper());
    private TaskPolicy taskPolicy;

    private static final class Principal {
        final String name, generation;
        final int appId;
        final List<String> certificates;
        Principal(InstalledPackage installed) {
            name = installed.packageName; generation = installed.baseApk.getAbsolutePath(); appId = installed.appId;
            certificates = new ArrayList<>(installed.certificateSha256);
        }
        void matches(InstalledPackage current) {
            if (current == null || !name.equals(current.packageName) || appId != current.appId
                    || !generation.equals(current.baseApk.getAbsolutePath()) || !certificates.equals(current.certificateSha256)) {
                throw new SecurityException("Account UI installation generation or real signer changed");
            }
        }
        void matches(CallerIdentity caller) {
            if (caller.userId != 0 || appId != caller.appId || !name.equals(caller.packageName)
                    || !certificates.equals(caller.certificateSha256)) {
                throw new SecurityException("Account UI ticket belongs to another real consumer");
            }
        }
    }

    private static final class Ticket {
        final Binder capability = new Binder();
        final Principal consumer, owner;
        final Intent snapshot;
        final AccountUiTicketState state = new AccountUiTicketState(SystemClock.elapsedRealtime() + TTL);
        Ticket(Principal consumer, Principal owner, Intent snapshot) {
            this.consumer = consumer; this.owner = owner; this.snapshot = snapshot;
        }
    }

    static final class Launch {
        private final Ticket ticket;
        private final Engine engine;
        final Intent intent;
        final String targetGeneration;
        private AccountUiTicketState.Binding binding;
        Launch(Ticket ticket, Engine engine) {
            this.ticket = ticket; this.engine = engine;
            intent = copy(ticket.snapshot); targetGeneration = ticket.owner.generation;
        }
        void reserve(IBinder record) { reserve(record, null, 0); }
        void dispatched(IBinder record) { dispatched(record, null, 0); }
        void consume(IBinder record) { consume(record, null, 0); }
        void cancel(IBinder record, boolean definiteBeforeClaim) {
            cancel(record, null, 0, definiteBeforeClaim);
        }
        synchronized void reserve(IBinder record, IBinder operation, long operationId) {
            requireCurrentPrincipals();
            if (binding != null) throw new SecurityException("Account UI launch was already bound");
            AccountUiTicketState.Binding next = new AccountUiTicketState.Binding(record, operation, operationId);
            ticket.state.reserve(next, SystemClock.elapsedRealtime()); binding = next;
        }
        void pending(IBinder record, IBinder operation, long operationId) {
            requireBinding(record, operation, operationId); requireCurrentPrincipals();
            ticket.state.pending(binding, SystemClock.elapsedRealtime());
        }
        void dispatched(IBinder record, IBinder operation, long operationId) {
            requireBinding(record, operation, operationId); requireCurrentPrincipals();
            ticket.state.dispatched(binding, SystemClock.elapsedRealtime());
        }
        void consume(IBinder record, IBinder operation, long operationId) {
            requireBinding(record, operation, operationId); requireCurrentPrincipals();
            ticket.state.consume(binding, SystemClock.elapsedRealtime());
        }
        void cancel(IBinder record, IBinder operation, long operationId, boolean definiteBeforeClaim) {
            requireBinding(record, operation, operationId);
            if (definiteBeforeClaim) requireCurrentPrincipals();
            ticket.state.cancel(binding, definiteBeforeClaim, SystemClock.elapsedRealtime());
        }
        private void requireBinding(IBinder record, IBinder operation, long operationId) {
            if (binding == null) throw new SecurityException("Account UI launch was not reserved");
            binding.require(record, operation, operationId);
        }
        private void requireCurrentPrincipals() {
            try {
                ticket.consumer.matches(engine.packages().get(ticket.consumer.name));
                ticket.owner.matches(engine.packages().get(ticket.owner.name));
            } catch (IOException | SecurityException changed) {
                ticket.state.revoke();
                throw new SecurityException("Account UI installation generation or real signer changed before delivery", changed);
            }
        }
    }

    ServerAccountUiTickets(Engine engine, ProcessRegistry processes) { this.engine = engine; this.processes = processes; }
    void setTaskPolicy(TaskPolicy policy) { taskPolicy = policy; }

    static boolean requiresFreshInstance(android.content.ComponentName component) {
        return component.getPackageName().equals("com.google.android.gms")
                && component.getClassName().equals("org.microg.gms.auth.login.LoginActivity");
    }

    @Override public Scope begin(CallerIdentity caller, AuthenticatorRegistration authenticator,
            String operation, boolean expectActivity) throws IOException {
        Principal consumer = principal(caller.packageName), owner = principal(authenticator.packageName);
        consumer.matches(caller);
        ProcessRegistry.Identity issuing = live(caller);
        if (!consumer.generation.equals(issuing.packageGeneration)
                || !owner.certificates.equals(authenticator.certificateSha256)) {
            throw new SecurityException("Account UI request installation changed");
        }
        return new Scope() {
            private final ArrayList<WeakReference<Ticket>> issued = new ArrayList<>();
            private boolean closed, detached;
            @Override public synchronized Intent publish(CallerIdentity actualOwner, Intent validated) throws IOException {
                if (closed) throw new SecurityException("Account UI request is closed");
                if (!(operation.equals("addAccount") || operation.equals("getAuthToken")
                        || operation.equals("confirmCredentials") || operation.equals("updateCredentials")
                        || operation.equals("editProperties") || operation.equals("removeAccount"))) {
                    throw new SecurityException("This account operation cannot authorize UI");
                }
                consumer.matches(engine.packages().get(consumer.name)); owner.matches(engine.packages().get(owner.name));
                if (!issuing.same(live(caller))) throw new SecurityException("Account UI requesting process changed");
                owner.matches(actualOwner);
                if (!owner.generation.equals(live(actualOwner).packageGeneration)) throw new SecurityException("Authenticator process changed");
                Intent snapshot = validateAndCopy(validated, owner.name);
                snapshot.removeExtra(EXTRA);
                if (consumer.name.equals(owner.name)) return snapshot; // Existing package-local permission is unchanged.
                ActivityInfo activity = activity(snapshot);
                if ((activity.launchMode != ActivityInfo.LAUNCH_MULTIPLE && activity.launchMode != ActivityInfo.LAUNCH_SINGLE_TASK)
                        || activity.targetActivity != null
                        || activity.documentLaunchMode != ActivityInfo.DOCUMENT_LAUNCH_NONE
                        || (snapshot.getFlags() & taskFlags()) != 0) {
                    throw new SecurityException("Cross-package account UI requires a standard or singleTask Activity without task modifiers");
                }
                Ticket ticket = new Ticket(consumer, owner, snapshot);
                synchronized (tickets) {
                    prune();
                    if (requiresFreshInstance(snapshot.getComponent())) {
                        if (taskPolicy == null) throw new SecurityException("Account UI task policy is unavailable");
                        taskPolicy.requireFresh(snapshot.getComponent(), owner.generation);
                        for (Ticket pending : tickets.values()) {
                            if (pending.owner.generation.equals(owner.generation)
                                    && pending.snapshot.getComponent().equals(snapshot.getComponent())) {
                                throw new SecurityException("microG login already has an outstanding account UI request");
                            }
                        }
                    }
                    if (tickets.size() >= LIMIT) throw new SecurityException("Too many outstanding account UI tickets");
                    tickets.put(ticket.capability, ticket);
                }
                issued.add(new WeakReference<>(ticket));
                expiry.postDelayed(() -> { synchronized (tickets) { prune(); } }, TTL);
                Intent carrier = copy(snapshot); Bundle extras = carrier.getExtras();
                if (extras == null) extras = new Bundle();
                extras.putBinder(EXTRA, ticket.capability); carrier.replaceExtras(extras); return carrier;
            }
            @Override public synchronized void close(boolean retainReturnedIntent) {
                // A detached return survives normal session close, but a failed delivery can still revoke it.
                if (closed && !detached) return;
                closed = true;
                detached = retainReturnedIntent && !expectActivity;
                if (!detached) {
                    for (WeakReference<Ticket> reference : issued) {
                        Ticket ticket = reference.get(); if (ticket != null) ticket.state.revoke();
                    }
                    issued.clear();
                }
            }
        };
    }

    Launch authorize(Intent supplied, ProcessRegistry.Identity source) throws IOException {
        if (source == null) throw new SecurityException("An account UI ticket requires its actual guest consumer");
        Bundle extras = supplied.getExtras();
        IBinder capability = extras == null ? null : extras.getBinder(EXTRA);
        Ticket ticket;
        synchronized (tickets) { prune(); ticket = tickets.get(capability); }
        if (ticket == null) throw new SecurityException("Account UI ticket is unknown or expired");
        ticket.consumer.matches(engine.packages().get(source.packageName));
        ticket.consumer.matches(liveCaller(source.pid));
        if (!ticket.consumer.generation.equals(source.packageGeneration)) throw new SecurityException("Account UI consumer generation changed");
        ticket.owner.matches(engine.packages().get(ticket.owner.name));
        ticket.state.available(SystemClock.elapsedRealtime());
        Intent checked = validateAndCopy(supplied, ticket.owner.name); checked.removeExtra(EXTRA);
        if (!ticket.snapshot.filterEquals(checked) || ticket.snapshot.getFlags() != checked.getFlags()) {
            throw new SecurityException("Account UI target/filter/flags were changed");
        }
        activity(ticket.snapshot); // Re-check the committed, enabled component before any process or task side effect.
        return new Launch(ticket, engine); // All extras, including the response, come from the private snapshot.
    }

    static boolean hasTicket(Intent intent) { return intent != null && intent.hasExtra(EXTRA); }

    private Principal principal(String name) throws IOException {
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) throw new SecurityException("Account UI principal is not installed");
        return new Principal(installed);
    }

    private ProcessRegistry.Identity live(CallerIdentity caller) {
        try { return processes.identity(Process.myUid(), caller.pid); }
        catch (Exception failure) { throw new SecurityException("Account UI request process is no longer registered", failure); }
    }

    private CallerIdentity liveCaller(int pid) {
        try { return processes.resolveCaller(Process.myUid(), pid); }
        catch (Exception failure) { throw new SecurityException("Account UI consumer is no longer registered", failure); }
    }

    private ActivityInfo activity(Intent intent) throws IOException {
        PackageInfo info = engine.packages().getPackageInfo(intent.getComponent().getPackageName(), PackageManager.GET_ACTIVITIES);
        if (info != null && info.applicationInfo != null && info.applicationInfo.enabled && info.activities != null) {
            for (ActivityInfo activity : info.activities) if (activity.enabled && activity.name.equals(intent.getComponent().getClassName())) return activity;
        }
        throw new SecurityException("Account UI Activity is absent or disabled");
    }

    private void prune() {
        Iterator<Ticket> iterator = tickets.values().iterator();
        while (iterator.hasNext()) if (iterator.next().state.terminal(SystemClock.elapsedRealtime())) iterator.remove();
    }

    private static int taskFlags() {
        return Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_FORWARD_RESULT | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
    }

    private static Intent validateAndCopy(Intent supplied, String owner) {
        if (supplied.getClass() != Intent.class) throw new SecurityException("Account UI requires a plain Intent");
        int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        if (supplied.getComponent() == null || !owner.equals(supplied.getComponent().getPackageName())
                || (supplied.getPackage() != null && !owner.equals(supplied.getPackage())) || supplied.getSelector() != null
                || supplied.getClipData() != null || (supplied.getFlags() & grants) != 0
                || (supplied.getExtras() != null && supplied.getExtras().hasFileDescriptors())) {
            throw new SecurityException("Invalid account UI Intent authority or payload");
        }
        Intent copy = copy(supplied);
        if (copy.getClass() != Intent.class || !supplied.filterEquals(copy) || copy.getSelector() != null
                || copy.getClipData() != null || (copy.getFlags() & grants) != 0) {
            throw new SecurityException("Account UI Intent changed during parceling");
        }
        return copy;
    }

    private static Intent copy(Intent intent) {
        Parcel parcel = Parcel.obtain();
        try { intent.writeToParcel(parcel, 0); parcel.setDataPosition(0); return Intent.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }
}
