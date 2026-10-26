package io.github.xmy953538104.mybox.engine;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual Android scheduling and callbacks, with separate virtual creator and physical executor identities. */
final class ServerJobs extends Binder {
    static final String DESCRIPTOR = EngineProtocol.SERVER + ".Jobs.v1";
    static final String CALLBACK = "android.app.job.IJobCallback";
    static final String NO_ACTIVE_EXECUTION = "NO_ACTIVE_EXECUTION";
    private final Engine engine;
    private final OsPendingStore store;
    private final ComponentRouter components;
    private final JobScheduler system;
    private final Object scheduling = new Object();
    private final Map<IBinder, Session> sessions = new HashMap<>();
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
    private final Map<Integer, String> callbacks = new HashMap<>();

    ServerJobs(Engine engine, OsPendingStore store, ComponentRouter components, ProcessRegistry processes) throws Exception {
        this.engine = engine; this.store = store; this.components = components;
        system = engine.hostContext().getSystemService(JobScheduler.class);
        for (Field field : Class.forName(CALLBACK + "$Stub").getDeclaredFields()) {
            if (field.getName().startsWith("TRANSACTION_")) { field.setAccessible(true); callbacks.put(field.getInt(null), field.getName().substring(12)); }
        }
        attachInterface(null, DESCRIPTOR);
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        if (code != FIRST_CALL_TRANSACTION) return false;
        data.enforceInterface(DESCRIPTOR);
        try {
            CallerIdentity caller = EngineServerProvider.resolveCaller();
            Bundle input = data.readBundle(getClass().getClassLoader());
            if (input == null || input.getInt("protocol") != EngineProtocol.VERSION || !caller.packageName.equals(input.getString("package"))) {
                throw new SecurityException("Job caller differs from its registered process");
            }
            Bundle result;
            long identity = Binder.clearCallingIdentity();
            try { synchronized (scheduling) { result = route(input, caller); } }
            finally { Binder.restoreCallingIdentity(identity); }
            reply.writeNoException(); reply.writeBundle(result);
        } catch (SecurityException | IllegalArgumentException | UnsupportedOperationException failure) {
            reply.writeException(failure);
        } catch (Exception failure) { reply.writeNoException(); reply.writeBundle(EngineProtocol.failure(failure)); }
        return true;
    }
    private Bundle route(Bundle input, CallerIdentity caller) throws Exception {
        String operation = input.getString("operation"), namespace = input.getString("namespace");
        JobEnvelope.namespace(namespace); Bundle result = EngineProtocol.response();
        List<JobInfo> actual = system.getAllPendingJobs();
        if (actual == null) throw new EngineException("Android returned no job inventory");
        if ("schedule".equals(operation) || "enqueue".equals(operation)) {
            JobInfo original = input.getParcelable("job"); JobEnvelope.validate(original);
            if (!caller.packageName.equals(original.getService().getPackageName())) throw new SecurityException("Cannot schedule another virtual package's service");
            JobWorkItem work = input.getParcelable("work");
            if ("enqueue".equals(operation)) JobEnvelope.validate(work);
            int physicalId = -1; Set<Integer> used = new HashSet<>();
            for (JobInfo job : actual) {
                used.add(job.getId()); JobEnvelope existing = owned(job);
                if (existing != null && existing.belongs(caller, namespace) && existing.original.getId() == original.getId()) physicalId = job.getId();
            }
            if (physicalId < 0) for (int id = 0x4d000000; id < 0x4d001000; id++) if (!used.contains(id)) { physicalId = id; break; }
            if (physicalId < 0) throw new IllegalStateException("No free physical job identity");
            JobEnvelope envelope = new JobEnvelope(caller, namespace, physicalId, JobEnvelope.copy(original)); envelope.service(engine);
            JobInfo physical = envelope.physical(engine, store);
            result.putInt("result", "enqueue".equals(operation) ? system.enqueue(physical, work) : system.schedule(physical));
            return result;
        }
        ArrayList<JobInfo> visible = new ArrayList<>(); ArrayList<String> namespaces = new ArrayList<>();
        boolean allNamespaces = "cancelAll".equals(operation) || "getAllPendingJobs".equals(operation) && Build.VERSION.SDK_INT >= 34;
        for (JobInfo job : actual) {
            JobEnvelope envelope = owned(job);
            if (envelope == null || !envelope.belongs(caller, allNamespaces ? envelope.namespace : namespace)) continue;
            if ("cancelAll".equals(operation) || "cancelAllInNamespace".equals(operation)
                    || "cancel".equals(operation) && envelope.original.getId() == input.getInt("job_id")) system.cancel(job.getId());
            else if ("getPendingJob".equals(operation) && envelope.original.getId() == input.getInt("job_id")) result.putParcelable("job", envelope.original);
            else if ("getPendingJobReason".equals(operation) && envelope.original.getId() == input.getInt("job_id")) {
                result.putInt("result", (Integer) RuntimeReflection.invoke(system,
                        RuntimeReflection.method(system.getClass(), "getPendingJobReason", int.class), job.getId()));
            }
            visible.add(envelope.original); namespaces.add(envelope.namespace);
        }
        if ("getAllPendingJobs".equals(operation) || "getAllPendingJobsInNamespace".equals(operation)) {
            result.putParcelableArrayList("jobs", visible); result.putStringArrayList("namespaces", namespaces);
        } else if ("getPendingJobReason".equals(operation)) {
            if (!result.containsKey("result")) result.putInt("result", 0); // Android PENDING_JOB_REASON_INVALID_JOB_ID.
        } else if (!"cancel".equals(operation) && !"cancelAll".equals(operation)
                && !"cancelAllInNamespace".equals(operation) && !"getPendingJob".equals(operation)) {
            throw new UnsupportedOperationException("Job operation is not implemented: " + operation);
        }
        return result;
    }
    private JobEnvelope owned(JobInfo job) throws Exception {
        return new ComponentName(engine.hostContext(), OsJobService.class).equals(job.getService()) ? JobEnvelope.read(job.getExtras(), job.getId(), store) : null;
    }

    Bundle dispatch(Bundle input) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) throw new SecurityException("Invalid OS job dispatch protocol");
        JobParameters parameters = input.getParcelable("parameters");
        if (parameters == null) throw new SecurityException("Missing actual OS job parameters");
        JobEnvelope envelope = JobEnvelope.read(parameters.getExtras(), parameters.getJobId(), store);
        IBinder callback = (IBinder) RuntimeReflection.get(parameters, "callback");
        if (callback == null || !CALLBACK.equals(callback.getInterfaceDescriptor())) throw new SecurityException("Invalid OS job callback");
        String operation = input.getString("operation");
        Session session;
        if ("startJob".equals(operation)) {
            IBinder guard = input.getBinder("guard");
            if (guard == null || !OsJobService.GUARD.equals(guard.getInterfaceDescriptor())) throw new SecurityException("Missing physical job executor guard");
            session = new Session(envelope, callback, guard);
            synchronized (sessions) {
                if (sessions.containsKey(callback)) throw new IllegalStateException("Job callback already executing");
                if (sessions.size() >= 32) throw new IllegalStateException("Too many concurrent guest jobs");
                sessions.put(callback, session);
            }
            try {
                session.bind(input.getLong("deadline_elapsed", 0));
                if (!session.send(operation, parameters, null)) throw new EngineException("Job execution closed before start delivery");
            }
            catch (Exception failure) { session.close(); throw failure; }
        } else {
            if (!isContinuation(operation)) throw new UnsupportedOperationException("Unsupported job executor message: " + operation);
            JobWorkItem work = input.getParcelable("work");
            if (work != null) JobEnvelope.validate(work);
            synchronized (sessions) { session = sessions.get(callback); }
            if (session == null) return notDelivered();
            if (!session.envelope.capability.equals(envelope.capability)
                    || session.envelope.physicalId != parameters.getJobId()) throw new SecurityException("Job callback is not its active execution");
            if (!session.send(operation, parameters, work)) return notDelivered();
        }
        Bundle result = EngineProtocol.response(); result.putBoolean("delivered", true); return result;
    }
    static boolean isContinuation(String operation) {
        return "stopJob".equals(operation) || "onNetworkChanged".equals(operation)
                || "getTransferredDownloadBytes".equals(operation) || "getTransferredUploadBytes".equals(operation);
    }
    private static Bundle notDelivered() {
        Bundle result = EngineProtocol.response(); result.putBoolean("delivered", false);
        result.putString("rejection", NO_ACTIVE_EXECUTION); return result;
    }
    private final class Session extends Binder {
        final JobEnvelope envelope;
        final IBinder systemCallback, guard;
        final AtomicBoolean closed = new AtomicBoolean();
        final Object connectionState = new Object();
        final CountDownLatch connected = new CountDownLatch(1);
        ServerBoundServices.Binding binding;
        volatile IBinder service, owner;
        volatile ProcessRegistry.Identity identity;
        ProcessRegistry.Identity connectedOwner;
        Exception connectionFailure;
        boolean startSent;
        long startDeadline;
        String failureCategory;
        final IBinder.DeathRecipient proxyDeath = this::close;
        Session(JobEnvelope envelope, IBinder callback, IBinder guard) {
            this.envelope = envelope; systemCallback = callback; this.guard = guard;
            attachInterface(null, CALLBACK);
        }
        void bind(long deadline) throws Exception {
            startDeadline = deadline;
            long remaining = deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0 || remaining > 8000) throw new EngineException("Invalid or expired actual job dispatch deadline");
            ServiceInfo target = envelope.service(engine);
            guard.linkToDeath(proxyDeath, 0);
            if (!guard.isBinderAlive() || closed.get()) throw new android.os.DeadObjectException();
            ComponentName component = new ComponentName(target.packageName, target.name);
            ServerBoundServices.Binding admitted = components.bindInternal(component,
                    new Intent("android.app.job.JobService").setComponent(component), Context.BIND_AUTO_CREATE,
                    new ServerBoundServices.Connection() {
                        @Override public void onConnected(IBinder actual, ProcessRegistry.Identity candidate) {
                            synchronized (connectionState) {
                                if (closed.get() || connectionFailure != null) return;
                                if (service != null) return;
                                service = actual; connectedOwner = candidate; connected.countDown();
                            }
                        }
                        @Override public void onNullBinding(ProcessRegistry.Identity candidate) { connectionFailed("NULL_BINDING", new EngineException("Actual JobService returned null binding")); }
                        @Override public void onDisconnected(ProcessRegistry.Identity candidate) { connectionFailed("DISCONNECTED", new EngineException("Actual JobService disconnected")); }
                        @Override public void onBindingDied(ProcessRegistry.Identity candidate) { connectionFailed("BINDING_DIED", new EngineException("Actual JobService binding died")); }
                        @Override public void onFailure(Exception failure) { connectionFailed("BIND_FAILURE", failure); }
                    });
            boolean cancel;
            synchronized (connectionState) {
                binding = admitted; identity = admitted.owner(); owner = identity == null ? null : identity.client;
                cancel = closed.get();
            }
            if (cancel) {
                android.os.DeadObjectException failure = new android.os.DeadObjectException();
                try { admitted.close(); } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
            if (!admitted.admitted()) throw new EngineException("Android rejected JobService bind admission");
            if (identity == null || owner == null || !envelope.packageName.equals(identity.packageName)) throw new SecurityException("Job executor reservation differs");
            remaining = deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0 || !connected.await(remaining, TimeUnit.MILLISECONDS)) throw new EngineException("Actual JobService connection exceeded its original dispatch deadline");
            synchronized (connectionState) {
                if (closed.get()) throw new android.os.DeadObjectException();
                if (connectionFailure != null) throw connectionFailure;
                if (!identity.same(connectedOwner)) throw new SecurityException("Connected JobService reservation differs from admission");
            }
            if (service == null || !"android.app.job.IJobService".equals(service.getInterfaceDescriptor())) throw new EngineException("Guest returned no actual JobService Binder");
            ProcessRegistry.Identity current = EngineServerProvider.registeredProcess(android.os.Process.myUid(), identity.pid);
            if (!identity.same(current) || !owner.isBinderAlive()) throw new SecurityException("Job executor changed before start delivery");
            Bundle watch = EngineProtocol.response(); watch.putBinder("owner", owner);
            EngineProtocol.request(guard, OsJobService.GUARD, OsJobService.WATCH, watch);
            requireStartBudget();
            synchronized (connectionState) {
                if (closed.get()) throw new android.os.DeadObjectException();
                if (connectionFailure != null) throw connectionFailure;
            }
        }
        void connectionFailed(String category, Exception failure) {
            boolean dispose = false;
            synchronized (connectionState) {
                if (closed.get()) return;
                if (connectionFailure == null) { connectionFailure = failure; failureCategory = category; }
                connected.countDown();
                if (startSent) dispose = closed.compareAndSet(false, true);
            }
            if (dispose) dispose(true);
        }
        boolean send(String operation, JobParameters parameters, JobWorkItem work) throws Exception {
            if (closed.get()) return false;
            if ("startJob".equals(operation)) requireStartBudget();
            Parcel parcel = Parcel.obtain(); JobParameters guest;
            try { parameters.writeToParcel(parcel, 0); parcel.setDataPosition(0); guest = JobParameters.CREATOR.createFromParcel(parcel); }
            finally { parcel.recycle(); }
            RuntimeReflection.set(guest, "jobId", envelope.original.getId());
            RuntimeReflection.set(guest, "extras", new android.os.PersistableBundle(envelope.original.getExtras()));
            RuntimeReflection.set(guest, "callback", this);
            if (Build.VERSION.SDK_INT >= 34) RuntimeReflection.set(guest, "mJobNamespace", envelope.namespace);
            Class<?> contract = Class.forName("android.app.job.IJobService");
            Object endpoint = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName(contract.getName() + "$Stub"), "asInterface", IBinder.class), service);
            // close can win after map lookup or parcel cloning; never substitute a newer execution.
            if (closed.get()) return false;
            synchronized (connectionState) { if (connectionFailure != null) throw connectionFailure; }
            if ("startJob".equals(operation)) requireStartBudget();
            if (operation.equals("getTransferredDownloadBytes") || operation.equals("getTransferredUploadBytes")) {
                RuntimeReflection.invoke(endpoint, RuntimeReflection.method(contract, operation, JobParameters.class, JobWorkItem.class), guest, work);
            } else RuntimeReflection.invoke(endpoint, RuntimeReflection.method(contract, operation, JobParameters.class), guest);
            if ("startJob".equals(operation)) synchronized (connectionState) {
                if (connectionFailure != null && !closed.get()) throw connectionFailure;
                startSent = true;
            }
            return true;
        }
        private void requireStartBudget() {
            if (startDeadline <= android.os.SystemClock.elapsedRealtime()) throw new EngineException("Actual JobService start exceeded its original dispatch deadline");
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(CALLBACK); return true; }
            String operation = callbacks.get(code);
            if (operation == null) return false;
            if (!operation.equals("acknowledgeStartMessage") && !operation.equals("acknowledgeStopMessage")
                    && !operation.equals("dequeueWork") && !operation.equals("completeWork") && !operation.equals("jobFinished")
                    && !operation.equals("updateEstimatedNetworkBytes") && !operation.equals("updateTransferredNetworkBytes")
                    && !operation.equals("acknowledgeGetTransferredDownloadBytesMessage") && !operation.equals("acknowledgeGetTransferredUploadBytesMessage")) {
                throw new UnsupportedOperationException("Job callback is not implemented: " + operation);
            }
            EngineProtocol.requireCaller();
            ProcessRegistry.Identity caller = EngineServerProvider.registeredProcess(Binder.getCallingUid(), Binder.getCallingPid());
            if (identity == null || !identity.same(caller)) throw new SecurityException("Job callback caller is not its live executor");
            data.enforceInterface(CALLBACK);
            if (data.readInt() != envelope.original.getId()) throw new SecurityException("Job callback ID differs from its execution");
            int rest = data.dataPosition();
            boolean terminal = operation.equals("jobFinished") || operation.equals("acknowledgeStopMessage")
                    || operation.equals("acknowledgeStartMessage") && data.readInt() == 0;
            data.setDataPosition(rest);
            Parcel translated = Parcel.obtain(), actualReply = Parcel.obtain();
            try {
                // A valid executor may finish late; Android's original callback decides whether its execution is still current.
                translated.writeInterfaceToken(CALLBACK); translated.writeInt(envelope.physicalId);
                translated.appendFrom(data, rest, data.dataSize() - rest); translated.setDataPosition(0);
                long token = Binder.clearCallingIdentity(); boolean accepted;
                try { accepted = systemCallback.transact(code, translated, actualReply, flags); }
                finally { Binder.restoreCallingIdentity(token); }
                if (!accepted) throw new android.os.RemoteException("OS rejected job callback transaction");
                actualReply.setDataPosition(0); actualReply.readException();
                if (operation.equals("acknowledgeStartMessage") && !terminal) {
                    // Only after the exact original OS callback has returned without an error.
                    try { EngineProtocol.request(guard, OsJobService.GUARD, OsJobService.START_CONFIRMED, EngineProtocol.response()); }
                    catch (EngineException unavailable) {
                        // A late proxy failure cannot roll back the actual OS acknowledgment.
                        android.util.Log.w("MyliteJobs", "Physical guard unavailable after the real start acknowledgment");
                    }
                }
                if (operation.equals("dequeueWork")) {
                    JobWorkItem item = actualReply.readInt() == 0 ? null : JobWorkItem.CREATOR.createFromParcel(actualReply);
                    if (item != null) JobEnvelope.validate(item);
                }
                actualReply.setDataPosition(0); reply.appendFrom(actualReply, 0, actualReply.dataSize());
                if (terminal) close();
                return true;
            } finally { translated.recycle(); actualReply.recycle(); }
        }
        void close() {
            synchronized (connectionState) { if (!closed.compareAndSet(false, true)) return; }
            dispose(false);
        }
        private void dispose(boolean failed) {
            connected.countDown();
            synchronized (sessions) { if (sessions.get(systemCallback) == this) sessions.remove(systemCallback); }
            try { guard.unlinkToDeath(proxyDeath, 0); }
            catch (java.util.NoSuchElementException notLinked) { /* Binding may fail before linkToDeath. */ }
            // Guest callbacks can be synchronous on its main thread; never unbind from that Binder call.
            cleanup.execute(() -> {
                Bundle command = EngineProtocol.response();
                if (failed) command.putString("reason", failureCategory);
                try { EngineProtocol.request(guard, OsJobService.GUARD, failed ? OsJobService.FAIL : OsJobService.RELEASE, command); }
                catch (Exception gone) { android.util.Log.w("MyliteJobs", "Job proxy release unavailable", gone); }
                ServerBoundServices.Binding released;
                synchronized (connectionState) { released = binding; }
                if (released != null) {
                    try { released.close(); }
                    catch (Exception failure) { android.util.Log.e("MyliteJobs", "Guest JobService unbind failed", failure); }
                }
            });
        }
    }
}
