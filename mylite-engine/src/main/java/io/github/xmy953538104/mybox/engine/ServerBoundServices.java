package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Server-authenticated logical bindings backed by real Android ServiceRecord connections. */
final class ServerBoundServices {
    interface Connection {
        void onConnected(IBinder service, ProcessRegistry.Identity owner);
        void onNullBinding(ProcessRegistry.Identity owner);
        void onDisconnected(ProcessRegistry.Identity owner);
        void onBindingDied(ProcessRegistry.Identity owner);
        void onFailure(Exception failure);
    }
    interface Binding extends AutoCloseable {
        ProcessRegistry.Identity owner();
        boolean admitted();
        @Override void close() throws Exception;
    }
    static final ThreadPoolExecutor CALLBACKS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), runnable -> new Thread(runnable, "mylite-bound-callback"));
    private static final int MAX_BINDINGS = 128;
    private static android.os.Handler legacyConnections;
    private final Engine engine;
    private final ProcessRegistry processes;
    private final Map<IBinder, Lease> leases = new HashMap<>();
    private final ArrayList<Group> groups = new ArrayList<>();
    private final Map<IBinder, Map<ComponentName, Long>> instances = new HashMap<>();
    private final Map<IBinder, Set<ComponentName>> started = new HashMap<>();
    private final Map<IBinder, IBinder.DeathRecipient> ownerWatches = new HashMap<>();
    private final Map<IBinder, IBinder> carrierTokens = new HashMap<>();
    private final Map<IBinder, Object> carrierMutations = new HashMap<>();
    private final Map<IBinder, Keeper> keepers = new HashMap<>();

    ServerBoundServices(Engine engine, ProcessRegistry processes) { this.engine = engine; this.processes = processes; }

    CallerIdentity resolveMessageCaller(IBinder capability, ProcessRegistry.Identity querying) throws Exception {
        Lease lease;
        synchronized (this) { lease = leases.get(capability); }
        if (lease == null || lease.consumer == null || !lease.group.owner.same(querying)
                || !BoundServiceIdentity.isRegistration(lease.group.component,lease.intent)) {
            throw new SecurityException("Message identity is not this service's registered binding");
        }
        lease.requireCurrent();
        io.github.xmy953538104.mybox.engine.packages.InstalledPackage installed = engine.packages().get(lease.consumer.packageName);
        if (installed == null || !lease.consumer.packageGeneration.equals(installed.baseApk.getAbsolutePath())) {
            throw new SecurityException("Message consumer installation changed");
        }
        CallerIdentity result = new CallerIdentity(installed.packageName,installed.appId,lease.consumer.pid,installed.certificateSha256);
        lease.requireCurrent();
        synchronized (this) {
            Long live = instances.getOrDefault(lease.group.owner.client,java.util.Collections.emptyMap()).get(lease.group.component);
            if (leases.get(capability) != lease || lease.closed || !lease.admitted || lease.group.retired
                    || lease.endpoint == null || lease.incarnation <= 0 || live == null || live != lease.incarnation) {
                throw new SecurityException("Message binding is closed, unpublished or expired");
            }
        }
        return result;
    }

    Bundle route(Bundle input, CallerIdentity caller) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) throw new SecurityException("Invalid bound Service protocol");
        String operation = input.getString("operation");
        if ("prepare".equals(operation)) return prepare(input, caller, null);
        if ("claim_keeper".equals(operation)) {
            IBinder token = input.getBinder("keeper");
            if (!(token instanceof Keeper) || ((Keeper) token).server != this) throw new SecurityException("Unregistered bound Service keeper");
            Keeper keeper = (Keeper) token;
            ProcessRegistry.Identity claimant = processes.identityForRetirement(Binder.getCallingUid(), Binder.getCallingPid());
            boolean active;
            synchronized (this) { active = !keeper.closed && keepers.get(keeper.owner.client) == keeper; }
            if (active) {
                try { active = keeper.owner.same(processes.identity(Process.myUid(), keeper.owner.pid)); }
                catch (SecurityException expired) { active = false; }
            }
            if (!active) {
                if (claimant.slot != keeper.owner.slot) throw new SecurityException("Old keeper belongs to another physical slot");
            } else {
                if (!claimant.same(keeper.owner)) throw new SecurityException("Keeper belongs to another process incarnation");
                IBinder carrier = input.getBinder("carrier");
                if (carrier == null || !carrier.isBinderAlive()) throw new SecurityException("Missing keeper carrier instance");
                synchronized (this) { carrierTokens.put(keeper.owner.client, carrier); }
            }
            Bundle result = EngineProtocol.response(); result.putBoolean("active", active); return result;
        }
        if ("claim_group".equals(operation)) {
            IBinder token = input.getBinder("group");
            if (!(token instanceof Group) || ((Group) token).server != this) throw new SecurityException("Unregistered bound Service group");
            Group selected = (Group) token;
            ProcessRegistry.Identity claimant = processes.identityForRetirement(Binder.getCallingUid(), Binder.getCallingPid());
            boolean expired;
            synchronized (this) { expired = selected.retired; }
            if (!expired) {
                try { expired = !selected.owner.same(processes.identity(Process.myUid(), selected.owner.pid)); }
                catch (SecurityException stale) { expired = true; }
            }
            if (expired) {
                if (claimant.slot != selected.owner.slot) throw new SecurityException("Old bound group belongs to another physical slot");
                Bundle inactive = EngineProtocol.response(); inactive.putBoolean("active", false); inactive.putInt("count", 0); return inactive;
            }
            Bundle result = BoundServiceWire.call(token, BoundServiceWire.GROUP, BoundServiceWire.operation("claim"));
            if (result.getBoolean("active")) {
                IBinder carrier = input.getBinder("carrier");
                if (carrier == null || !carrier.isBinderAlive()) throw new SecurityException("Missing physical carrier instance");
                Group group = (Group) token;
                synchronized (this) { carrierTokens.put(group.owner.client, carrier); }
                refreshCarrier(group.owner);
            }
            return result;
        }
        if ("carrier_gone".equals(operation)) {
            ProcessRegistry.Identity owner = processes.identityForRetirement(Binder.getCallingUid(), Binder.getCallingPid());
            synchronized (this) { if (input.getBinder("carrier") != null && input.getBinder("carrier").equals(carrierTokens.get(owner.client))) carrierTokens.remove(owner.client); }
            return EngineProtocol.response();
        }
        if ("claim_lease".equals(operation)) {
            IBinder token = input.getBinder("lease");
            if (!(token instanceof Lease) || ((Lease) token).group.server != this) throw new SecurityException("Unregistered bound Service lease");
            Bundle claim = BoundServiceWire.operation("claim"); claim.putBinder("endpoint", input.getBinder("endpoint"));
            return BoundServiceWire.call(token, BoundServiceWire.LEASE, claim);
        }
        if ("component_state".equals(operation)) return componentState(input,
                processes.identityForRetirement(Binder.getCallingUid(), Binder.getCallingPid()));
        throw new IllegalArgumentException("Unknown bound Service operation");
    }

    Binding bindInternal(ComponentName target, Intent intent, long flags, Connection listener) throws Exception {
        if (listener == null) throw new IllegalArgumentException("Bound Service listener is missing");
        BoundServiceConnection connection = new BoundServiceConnection(engine, null, listener, CALLBACKS);
        Bundle request = request(target, intent, flags, connection);
        try {
            Bundle prepared = prepare(request, null, Process.myPid());
            connection.prepare(prepared, null);
            connection.assignOwner(processes.identity(Process.myUid(), prepared.getInt("pid")));
            Context context = engine.hostContext(); Intent outer = prepared.getParcelable("outer");
            connection.unbinder(() -> context.unbindService(connection));
            boolean accepted;
            if (android.os.Build.VERSION.SDK_INT >= 34) accepted = context.bindService(outer, Context.BindServiceFlags.of(flags), CALLBACKS, connection);
            else {
                if ((flags & ~0xffffffffL) != 0) throw new EngineException("Long Service binding flags require Android 14");
                if (android.os.Build.VERSION.SDK_INT >= 29) accepted = context.bindService(outer, (int) flags, CALLBACKS, connection);
                else accepted = (Boolean) RuntimeReflection.invoke(context,
                        RuntimeReflection.method(context.getClass(), "bindServiceAsUser", Intent.class, ServiceConnection.class,
                                int.class, android.os.Handler.class, android.os.UserHandle.class),
                        outer, connection, (int) flags, legacyConnectionHandler(), Process.myUserHandle());
            }
            connection.accepted(accepted);
            return connection;
        } catch (Exception failure) {
            connection.failedAttempt();
            try { connection.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static synchronized android.os.Handler legacyConnectionHandler() {
        if (android.os.Build.VERSION.SDK_INT >= 29) throw new IllegalStateException("Legacy connection handler is only for Android 8/9");
        if (legacyConnections == null) {
            android.os.HandlerThread thread = new android.os.HandlerThread("mylite-legacy-bound-connection");
            thread.start(); legacyConnections = new android.os.Handler(thread.getLooper());
        }
        return legacyConnections;
    }

    static Bundle request(ComponentName target, Intent intent, long flags, IBinder sink) {
        Bundle request = BoundServiceWire.operation("prepare");
        request.putParcelable("component", target); request.putParcelable("intent", new Intent(intent));
        request.putLong("flags", flags); request.putBinder("sink", sink); return request;
    }

    private Bundle prepare(Bundle input, CallerIdentity caller, Integer internalPid) throws Exception {
        ComponentName component = input.getParcelable("component");
        Intent intent = input.getParcelable("intent");
        long flags = input.getLong("flags");
        if (intent != null && (intent.getClipData() != null || (intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) != 0))
            throw new EngineException("Virtual bound Service URI grants are not implemented");
        if (component == null || intent == null || !component.equals(intent.getComponent()))
            throw new IllegalArgumentException("An explicit consistent bound Service is required");
        if ((flags & (0x80000000L | Context.BIND_EXTERNAL_SERVICE_LONG | Context.BIND_SHARED_ISOLATED_PROCESS | Context.BIND_PACKAGE_ISOLATED_PROCESS)) != 0)
            throw new EngineException("External or isolated guest Service binding is not implemented");
        IBinder sink = input.getBinder("sink");
        if (sink == null || !sink.isBinderAlive()) throw new IllegalArgumentException("Bound Service callback has died");
        ServiceInfo info = service(component);
        if (caller != null) ServiceAccess.require(engine, caller, info);
        Bundle acquire = EngineProtocol.response(); acquire.putString("package", info.packageName);
        acquire.putString("process", info.processName == null ? info.packageName : info.processName);
        Bundle prepared = processes.acquirePrepared(acquire);
        ProcessRegistry.Identity owner = processes.identity(Process.myUid(), prepared.getInt("pid"));
        ProcessRegistry.Identity consumer = caller == null ? null : processes.identity(Process.myUid(), caller.pid);
        if (caller != null && !caller.packageName.equals(consumer.packageName)) throw new SecurityException("Binding consumer changed");
        watchOwner(owner);
        Object serial;
        synchronized (this) { serial = carrierMutations.computeIfAbsent(owner.client, ignored -> new Object()); }
        synchronized (serial) {
            Group group; Lease lease;
            synchronized (this) {
                if (leases.size() >= MAX_BINDINGS) throw new EngineException("Too many live bound Service connections");
                group = null;
                Intent.FilterComparison filter = new Intent.FilterComparison(intent);
                for (Group candidate : groups) if (!candidate.retired && candidate.owner.same(owner)
                        && component.equals(candidate.component) && candidate.filter.equals(filter)) { group = candidate; break; }
                if (group == null) { group = new Group(owner, component, filter); groups.add(group); }
                if (group.retired || !owner.client.isBinderAlive()) throw new EngineException("Target process died during binding");
                lease = new Lease(group, consumer, internalPid == null ? caller.pid : internalPid, sink, intent, flags);
                leases.put(lease, lease); group.members.add(lease);
            }
            try { sink.linkToDeath(lease.consumerDeath, 0); }
            catch (Exception failure) { try { lease.close(false); } catch (Exception cleanup) { failure.addSuppressed(cleanup); } throw failure; }
            Bundle result = new Bundle(prepared); result.putBinder("lease", lease); result.putBinder("group", group);
            result.putParcelable("component", component); result.putParcelable("outer", outer(group));
            return result;
        }
    }

    private ServiceInfo service(ComponentName component) throws Exception {
        PackageInfo pkg = engine.packages().getPackageInfo(component.getPackageName(), PackageManager.GET_SERVICES);
        if (pkg != null && pkg.services != null) for (ServiceInfo service : pkg.services)
            if (component.getClassName().equals(service.name) && service.enabled && service.applicationInfo.enabled) {
                if ((service.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0) throw new EngineException("Isolated guest Services are not implemented");
                return service;
            }
        throw new EngineException("Virtual Service is absent or disabled: " + component);
    }

    private Intent outer(Group group) {
        return new Intent().setClassName(engine.hostContext(), BoundServiceStub.class.getName() + "$P" + group.owner.slot)
                .setData(Uri.parse("mybox-bound://connection/" + group.id))
                .replaceExtras(envelope(group));
    }
    private static Bundle envelope(Group group) { Bundle result = new Bundle(); result.putBinder(BoundServiceWire.EXTRA_GROUP, group); return result; }

    private void requireOwner(Group group) throws Exception {
        if (!group.owner.same(processes.identity(Process.myUid(), group.owner.pid))) throw new SecurityException("Bound Service owner is no longer current");
        ServiceInfo info = service(group.component);
        if (!group.owner.packageGeneration.equals(info.applicationInfo.sourceDir)) throw new SecurityException("Bound Service installation changed");
        if (!group.owner.processName.equals(info.processName == null ? info.packageName : info.processName)) throw new SecurityException("Bound Service process changed");
    }

    private final class Group extends Binder {
        final ServerBoundServices server = ServerBoundServices.this;
        final ProcessRegistry.Identity owner;
        final ComponentName component;
        final Intent.FilterComparison filter;
        final String id = java.util.UUID.randomUUID().toString();
        final Set<Lease> members = new HashSet<>();
        boolean retired;
        Group(ProcessRegistry.Identity owner, ComponentName component, Intent.FilterComparison filter) throws Exception {
            this.owner = owner; this.component = component; this.filter = filter;
            attachInterface(null, BoundServiceWire.GROUP);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(BoundServiceWire.GROUP); return true; }
            if (code != BoundServiceWire.CALL) return false;
            Bundle result;
            try {
                EngineProtocol.requireCaller(); data.enforceInterface(BoundServiceWire.GROUP); Bundle input = data.readBundle(getClass().getClassLoader());
                if (Binder.getCallingPid() != owner.pid) throw new SecurityException("Only the assigned Service process may claim its group");
                boolean snapshotOnly = "state".equals(input.getString("operation"));
                boolean inactive;
                synchronized (ServerBoundServices.this) { inactive = retired; }
                if (!inactive && !snapshotOnly) requireOwner(this);
                result = EngineProtocol.response();
                synchronized (ServerBoundServices.this) {
                    result.putBoolean("active", !retired); result.putParcelable("component", component);
                    result.putInt("count", retired ? 0 : members.size()); int index = 0;
                    if (!snapshotOnly && !retired) for (Lease lease : members) result.putBinder("lease" + index++, lease);
                }
            } catch (Exception failure) { result = BoundServiceWire.failure(failure); }
            reply.writeNoException(); reply.writeBundle(result); return true;
        }
    }

    private final class Lease extends Binder {
        final Group group;
        final ProcessRegistry.Identity consumer;
        final int consumerPid;
        final IBinder sink;
        final Intent intent;
        final long flags;
        final IBinder.DeathRecipient consumerDeath = () -> {
            try { close(false); } catch (Exception failure) { android.util.Log.e("MyliteBound", "Consumer death cleanup failed", failure); }
        };
        volatile boolean closed;
        volatile boolean admitted;
        IBinder endpoint;
        long incarnation;
        Lease(Group group, ProcessRegistry.Identity consumer, int consumerPid, IBinder sink, Intent intent, long flags) {
            this.group = group; this.consumer = consumer; this.consumerPid = consumerPid;
            this.sink = sink; this.intent = new Intent(intent); this.flags = flags; attachInterface(null, BoundServiceWire.LEASE);
        }
        private void requireCurrent() throws Exception {
            requireOwner(group);
            if (consumer != null) {
                if (!consumer.same(processes.identity(Process.myUid(), consumer.pid))) throw new SecurityException("Binding consumer is no longer current");
                ServiceAccess.require(engine, processes.resolveCaller(Process.myUid(), consumer.pid), service(group.component));
            }
        }
        void close(boolean died) throws Exception {
            IBinder cleanup;
            synchronized (ServerBoundServices.this) {
                if (closed) return; closed = true; leases.remove(this); group.members.remove(this); cleanup = endpoint;
                if (group.members.isEmpty()) { group.retired = true; groups.remove(group);  }
            }
            try { sink.unlinkToDeath(consumerDeath, 0); } catch (java.util.NoSuchElementException notLinked) { /* Failed link still retires the capability. */ }
            Exception error = null;
            if (cleanup != null && group.owner.client.pingBinder()) try {
                Bundle request = BoundServiceWire.operation("remove"); request.putBinder("lease", this);
                BoundServiceWire.call(cleanup, BoundServiceWire.CARRIER, request);
            } catch (Exception failure) {
                if (!confirmedDeadCarrierCleanup(group.owner.client, failure)) error = failure;
                else android.util.Log.i("MyliteBound", "Old carrier died during binding cleanup", failure);
            }
            try { refreshCarrier(group.owner); }
            catch (Exception failure) { if (error == null) error = failure; else error.addSuppressed(failure); }
            if (died) try { send(sink, group.owner.pid, "binding_died", null, 0, null); }
            catch (Exception failure) { if (error == null) error = failure; else error.addSuppressed(failure); }
            if (error != null) throw error;
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int transactionFlags) {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(BoundServiceWire.LEASE); return true; }
            if (code != BoundServiceWire.CALL) return false;
            Bundle result;
            try {
                EngineProtocol.requireCaller(); data.enforceInterface(BoundServiceWire.LEASE);
                Bundle input = data.readBundle(getClass().getClassLoader()); String operation = input.getString("operation");
                int pid = Binder.getCallingPid();
                if ("delivery".equals(operation)) {
                    if (pid != consumerPid) throw new SecurityException("Only the binding consumer may confirm delivery");
                    result = EngineProtocol.response();
                    if (!closed) {
                        requireCurrent();
                        synchronized (ServerBoundServices.this) {
                            Long live = instances.getOrDefault(group.owner.client, java.util.Collections.emptyMap()).get(group.component);
                            result.putBoolean("active", incarnation > 0 && incarnation == input.getLong("incarnation") && live != null && live == incarnation && !closed);
                        }
                    }
                } else if ("admitted".equals(operation)) {
                    if (pid != consumerPid) throw new SecurityException("Only the binding consumer may admit its lease");
                    if (!closed) {
                        requireCurrent(); admitted = true; activateCarrier(group.owner);
                        IBinder carrier; synchronized (ServerBoundServices.this) { carrier = endpoint; }
                        if (carrier != null && carrier.isBinderAlive()) {
                            Bundle activation = BoundServiceWire.operation("activate"); activation.putBinder("lease", this);
                            BoundServiceWire.call(carrier, BoundServiceWire.CARRIER, activation);
                        }
                    }
                    result = EngineProtocol.response();
                } else if ("close".equals(operation)) {
                    if (pid != consumerPid) throw new SecurityException("Only the binding consumer may close its lease");
                    close(false); result = EngineProtocol.response();
                } else {
                    if (pid != group.owner.pid) throw new SecurityException("Only the reserved target may publish a bound Service");
                    result = EngineProtocol.response(); result.putBoolean("active", !closed);
                    if (!closed) {
                        requireCurrent();
                        if ("claim".equals(operation)) {
                            IBinder requestedEndpoint = input.getBinder("endpoint");
                            if (requestedEndpoint == null || !requestedEndpoint.isBinderAlive()) throw new SecurityException("Bound carrier endpoint is missing");
                            synchronized (ServerBoundServices.this) {
                                if (endpoint != null && !endpoint.equals(requestedEndpoint)) throw new SecurityException("Binding is already owned by another carrier");
                                endpoint = requestedEndpoint;
                            }
                            result.putParcelable("component", group.component); result.putParcelable("intent", new Intent(intent));
                            result.putLong("flags", flags); result.putBoolean("admitted", admitted); result.putBinder("sink", sink); result.putBinder("group", group);
                        } else if ("publish".equals(operation)) {
                            if (!admitted || endpoint == null) throw new SecurityException("Binding has no admitted physical carrier");
                            long current = input.getLong("incarnation");
                            synchronized (ServerBoundServices.this) {
                                Long live = instances.getOrDefault(group.owner.client, java.util.Collections.emptyMap()).get(group.component);
                                if (live == null || live != current || current <= 0 || (incarnation != 0 && incarnation != current))
                                    throw new SecurityException("Bound Service incarnation expired");
                                incarnation = current;
                            }
                        } else throw new IllegalArgumentException("Unknown bound lease operation");
                    }
                }
            } catch (Exception failure) { result = BoundServiceWire.failure(failure); }
            reply.writeNoException(); reply.writeBundle(result); return true;
        }
    }

    private Bundle componentState(Bundle input, ProcessRegistry.Identity owner) throws Exception {
        ComponentName component = input.getParcelable("component");
        if (component == null || !owner.packageName.equals(component.getPackageName())) throw new SecurityException("Service state belongs to another guest");
        long incarnation = input.getLong("incarnation"); boolean alive = input.getBoolean("alive"), active = input.getBoolean("started");
        boolean known;
        synchronized (this) {
            Long previous = instances.getOrDefault(owner.client, java.util.Collections.emptyMap()).get(component);
            known = previous != null && previous == incarnation;
            if (!alive && !known) return EngineProtocol.response();
        }
        // An authenticated old instance may only end its own lifecycle after disable/update.
        if (alive && (active || !known)) requireCurrentStateOwner(owner, component);
        watchOwner(owner);
        ArrayList<Lease> retired = new ArrayList<>();
        synchronized (this) {
            Map<ComponentName, Long> live = instances.computeIfAbsent(owner.client, ignored -> new HashMap<>());
            Set<ComponentName> running = started.computeIfAbsent(owner.client, ignored -> new HashSet<>());
            if (alive) {
                if (incarnation <= 0) throw new SecurityException("Service incarnation is missing");
                Long previous = live.get(component);
                if (previous != null && previous != incarnation) throw new SecurityException("Previous Service incarnation has not retired");
                live.put(component, incarnation);
            }
            else { Long previous = live.get(component); if (previous != null && previous != incarnation) throw new SecurityException("Service incarnation changed"); live.remove(component); }
            if (active && alive) running.add(component); else running.remove(component);
            for (Group group : groups) if (group.owner.same(owner) && component.equals(group.component)) {
                if (!alive) for (Lease lease : group.members) if (lease.incarnation != 0) retired.add(lease);
            }
        }
        for (Lease lease : retired) lease.close(true);
        refreshCarrier(owner);
        return EngineProtocol.response();
    }

    private void requireCurrentStateOwner(ProcessRegistry.Identity owner, ComponentName component) throws Exception {
        if (!owner.same(processes.identity(Process.myUid(), owner.pid))) throw new SecurityException("Service owner changed");
        ServiceInfo info = service(component);
        if (!owner.processName.equals(info.processName == null ? info.packageName : info.processName)) throw new SecurityException("Service state belongs to another process");
    }

    private void watchOwner(ProcessRegistry.Identity owner) throws Exception {
        IBinder.DeathRecipient death = () -> ownerDied(owner);
        synchronized (this) { if (ownerWatches.containsKey(owner.client)) return; ownerWatches.put(owner.client, death); }
        try { owner.client.linkToDeath(death, 0); }
        catch (Exception failure) { ownerDied(owner); throw failure; }
    }
    private void ownerDied(ProcessRegistry.Identity owner) {
        ArrayList<Lease> pending = new ArrayList<>();
        synchronized (this) {
            instances.remove(owner.client); started.remove(owner.client); ownerWatches.remove(owner.client); carrierTokens.remove(owner.client);
            for (Group group : groups) if (group.owner.same(owner)) { group.retired = true; pending.addAll(group.members); }
        }
        for (Lease lease : pending) try { lease.close(true); }
        catch (Exception failure) { android.util.Log.e("MyliteBound", "Dead binding cleanup failed", failure); }
        try { refreshCarrier(owner); }
        catch (Exception failure) { android.util.Log.e("MyliteBound", "Dead carrier connection cleanup failed", failure); }
    }
    private void activateCarrier(ProcessRegistry.Identity owner) throws Exception { refreshCarrier(owner); }
    private void refreshCarrier(ProcessRegistry.Identity owner) throws Exception {
        Object serial;
        synchronized (this) { serial = carrierMutations.computeIfAbsent(owner.client, ignored -> new Object()); }
        // Serialize actual bind/unbind admission, without holding the ledger or registry across IPC.
        synchronized (serial) {
            ProcessRegistry.Identity current;
            try { current = processes.identityForRetirement(Process.myUid(), owner.pid); }
            catch (SecurityException expired) {
                retireKeeper(owner);
                synchronized (this) { carrierMutations.remove(owner.client, serial); }
                return; // No OS mutation may target a reused slot.
            }
            if (!owner.same(current) || !owner.client.pingBinder()) {
                retireKeeper(owner);
                synchronized (this) { carrierMutations.remove(owner.client, serial); }
                return;
            }
            boolean needed = false;
            synchronized (this) {
                Set<ComponentName> running = started.getOrDefault(owner.client, java.util.Collections.emptySet());
                for (Group group : groups) if (!group.retired && group.owner.same(owner) && (running.contains(group.component) || carrierTokens.containsKey(owner.client)) && !group.members.isEmpty()) { needed = true; break; }
            }
            if (!needed) { retireKeeper(owner); return; }
            Keeper keeper;
            synchronized (this) {
                keeper = keepers.get(owner.client);
                if (keeper == null) { keeper = new Keeper(owner); keepers.put(owner.client, keeper); }
            }
            try {
                if (!keeper.attempted) keeper.bind();
                keeper.wake();
            } catch (Exception failure) {
                try { retireKeeper(owner); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                failOwnerBindings(owner, failure);
            }
        }
    }
    private void retireKeeper(ProcessRegistry.Identity owner) {
        Keeper keeper;
        synchronized (this) { keeper = keepers.remove(owner.client); if (keeper != null) keeper.closed = true; }
        if (keeper != null && keeper.attempted) engine.hostContext().unbindService(keeper);
    }
    private void failOwnerBindings(ProcessRegistry.Identity owner, Exception failure) {
        ArrayList<Lease> pending = new ArrayList<>();
        synchronized (this) {
            for (Group group : new ArrayList<>(groups)) if (group.owner.same(owner)) {
                group.retired = true; pending.addAll(group.members); groups.remove(group);
            }
        }
        for (Lease lease : pending) {
            try { send(lease.sink, owner.pid, "failure", null, 0, failure); }
            catch (Exception delivery) { failure.addSuppressed(delivery); }
            try { lease.close(false); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
        }
        android.util.Log.e("MyliteBound", "Actual carrier binding failed", failure);
    }
    private final class Keeper extends Binder implements ServiceConnection {
        final ServerBoundServices server = ServerBoundServices.this;
        final ProcessRegistry.Identity owner;
        volatile boolean closed;
        boolean attempted;
        volatile IBinder endpoint;
        Keeper(ProcessRegistry.Identity owner) { this.owner = owner; }
        void bind() {
            Intent intent = new Intent().setClassName(engine.hostContext(), BoundServiceStub.class.getName() + "$P" + owner.slot)
                    .setData(Uri.parse("mybox-bound://keeper/" + java.util.UUID.randomUUID()));
            Bundle extras = new Bundle(); extras.putBinder(BoundServiceWire.EXTRA_KEEPER, this); intent.replaceExtras(extras);
            attempted = true;
            int flags = Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY | Context.BIND_NOT_FOREGROUND;
            boolean accepted = engine.hostContext().bindService(intent, this, flags);
            if (!accepted) throw new EngineException("Android rejected the actual bound Service keeper");
        }
        void wake() {
            IBinder ready = endpoint;
            if (closed || ready == null) return;
            Bundle result = BoundServiceWire.call(ready, BoundServiceWire.KEEPER, BoundServiceWire.operation("wake"));
            if (!result.getBoolean("active")) throw new EngineException("Bound Service keeper incarnation is inactive");
        }
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (closed) return;
            try {
                if (!BoundServiceWire.KEEPER.equals(service.getInterfaceDescriptor())) throw new SecurityException("Invalid bound Service keeper endpoint");
                endpoint = service; wake();
            } catch (Exception failure) { failed(failure); }
        }
        @Override public void onNullBinding(ComponentName name) { failed(new EngineException("Actual keeper returned a null binding")); }
        @Override public void onServiceDisconnected(ComponentName name) { failed(new EngineException("Actual keeper process disconnected")); }
        @Override public void onBindingDied(ComponentName name) { failed(new EngineException("Actual keeper binding died")); }
        private void failed(Exception failure) {
            if (closed) return;
            Object serial;
            synchronized (ServerBoundServices.this) { serial = carrierMutations.computeIfAbsent(owner.client, ignored -> new Object()); }
            synchronized (serial) {
                if (closed) return;
                try { retireKeeper(owner); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                failOwnerBindings(owner, failure);
            }
        }
    }

    static boolean confirmedDeadCarrierCleanup(IBinder owner, Exception failure) {
        // Binder can report another transport error during process exit. Only the fixed old owner may prove death.
        if (!(failure instanceof EngineException) || !(failure.getCause() instanceof android.os.RemoteException)) return false;
        return failure.getCause() instanceof android.os.DeadObjectException || !owner.pingBinder();
    }

    static void state(GuestRuntime runtime, ComponentName component, long incarnation, boolean alive, boolean started) {
        Bundle request = BoundServiceWire.operation("component_state"); request.putParcelable("component", component);
        request.putLong("incarnation", incarnation); request.putBoolean("alive", alive); request.putBoolean("started", started);
        BoundServiceWire.checked(runtime.requestBoundServer(EngineProtocol.BOUND_SERVICE, request));
    }
    static void send(IBinder sink, int ownerPid, String event, IBinder service, long incarnation, Exception failure) {
        Bundle payload = BoundServiceWire.operation(event); payload.putInt("owner_pid", ownerPid);
        payload.putBinder("service", service); payload.putLong("incarnation", incarnation);
        if (failure != null) payload.putString("message", failure.toString());
        BoundServiceWire.call(sink, BoundServiceWire.CALLBACK, payload);
    }
}
