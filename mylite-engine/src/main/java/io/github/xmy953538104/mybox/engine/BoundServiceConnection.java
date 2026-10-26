package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** One real AMS connection and one revocable logical lease. No callback creates a new process. */
final class BoundServiceConnection extends Binder implements ServiceConnection, ServerBoundServices.Binding {
    interface Unbinder { void unbind() throws Exception; }
    private final Object guestCallback;
    private final ServerBoundServices.Connection listener;
    private final Executor executor;
    private final int serverPid;
    private IBinder lease;
    private ComponentName component, physicalComponent;
    private ProcessRegistry.Identity owner;
    private int targetPid;
    private long targetGeneration;
    private String targetPackageGeneration;
    private IBinder targetClient;
    private boolean closed, finished, admitted, carrierReady, emitted;
    private Bundle publication;
    private Unbinder unbinder;
    private boolean osUnbound, uncertainAdmission;
    private boolean pumping, terminalQueued, disconnectedQueued;
    private final Runnable pumpTask = this::pump;
    private final java.util.ArrayDeque<Runnable> events = new java.util.ArrayDeque<>();
    private final Binder relay = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString("android.app.IServiceConnection"); return true; }
            if (Binder.getCallingUid() != Process.SYSTEM_UID) throw new SecurityException("Service connection must come from Android");
            // A same-process carrier uses local Binder dispatch, which otherwise inherits AMS's UID/PID.
            long identity = Binder.clearCallingIdentity();
            try {
                if (code != connectedCode()) return false;
                data.enforceInterface("android.app.IServiceConnection");
                ComponentName name = data.readInt() != 0 ? ComponentName.CREATOR.createFromParcel(data) : null;
                IBinder service = data.readStrongBinder();
                boolean dead = data.readInt() != 0;
                if (data.dataAvail() != 0) throw new EngineException("Unsupported IServiceConnection payload");
                if (dead) onBindingDied(name); else if (service == null) onNullBinding(name); else onServiceConnected(name, service);
                return true;
            } catch (Exception failure) { fail(failure); return true; }
            finally { Binder.restoreCallingIdentity(identity); }
        }
    };

    BoundServiceConnection(Engine engine, Object guestCallback, ServerBoundServices.Connection listener, Executor executor) {
        this.guestCallback = guestCallback; this.listener = listener; this.executor = executor;
        GuestRuntime runtime = engine.runtime(); serverPid = listener != null ? Process.myPid() : runtime.serverPid();
        attachInterface(null, BoundServiceWire.CALLBACK);
    }
    void prepare(Bundle prepared, ProcessRegistry.Identity owner) throws Exception {
        IBinder ready = prepared.getBinder("lease"); boolean dispose;
        synchronized (this) {
            lease = ready; targetPid = prepared.getInt("pid"); targetGeneration = prepared.getLong("generation");
            targetPackageGeneration = prepared.getString("package_generation"); targetClient = prepared.getBinder("client");
            if (owner != null) assignOwner(owner);
            if (publication != null && publication.getInt("owner_pid") != targetPid) throw new SecurityException("Early binding owner changed");
            component = prepared.getParcelable("component"); Intent outer = prepared.getParcelable("outer"); physicalComponent = outer == null ? null : outer.getComponent(); dispose = closed;
        }
        if (ready == null || component == null || targetPid <= 0) throw new EngineException("Incomplete binding reservation");
        if (dispose) closeLease(ready);
    }
    synchronized void assignOwner(ProcessRegistry.Identity assigned) {
        if (assigned == null || assigned.pid != targetPid || assigned.generation != targetGeneration
                || !assigned.client.equals(targetClient) || !assigned.packageGeneration.equals(targetPackageGeneration)
                || (owner != null && !owner.same(assigned))) throw new SecurityException("Binding reservation changed");
        owner = assigned;
    }
    synchronized boolean isClosed() { return closed; }
    synchronized void unbinder(Unbinder value) { unbinder = value; }
    Object relay() throws Exception {
        Method connected = RuntimeReflection.method(Class.forName("android.app.IServiceConnection"), "connected", ComponentName.class, IBinder.class, boolean.class);
        if (connected.getParameterTypes().length != 3) throw new EngineException("Unsupported IServiceConnection signature");
        return RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName("android.app.IServiceConnection$Stub"), "asInterface", IBinder.class), relay);
    }
    private static int connectedCode() throws Exception {
        return RuntimeReflection.field(Class.forName("android.app.IServiceConnection$Stub"), "TRANSACTION_connected").getInt(null);
    }
    void accepted(boolean accepted) throws Exception {
        boolean dispose;
        synchronized (this) { finished = true; admitted = accepted; dispose = closed || !accepted; }
        if (dispose) close(); else {
            IBinder current;
            synchronized (this) { current = lease; }
            leaseCall(current, BoundServiceWire.operation("admitted"));
            drain();
        }
    }
    void failedAttempt() { synchronized (this) { finished = true; uncertainAdmission = true; } }
    @Override public synchronized ProcessRegistry.Identity owner() { return owner; }
    @Override public synchronized boolean admitted() { return admitted; }
    @Override public void close() throws Exception {
        IBinder current; Unbinder cleanup;
        synchronized (this) {
            closed = true; publication = null; events.clear(); current = lease;
            cleanup = finished && (admitted || uncertainAdmission) && !osUnbound ? unbinder : null;
            if (cleanup != null) osUnbound = true;
        }
        if (executor instanceof java.util.concurrent.ThreadPoolExecutor) ((java.util.concurrent.ThreadPoolExecutor) executor).remove(pumpTask);
        Exception error = null;
        if (cleanup != null) try { cleanup.unbind(); } catch (Exception failure) { error = failure; }
        if (current != null) try { closeLease(current); } catch (Exception failure) { if (error == null) error = failure; else error.addSuppressed(failure); }
        if (error != null) throw error;
    }
    private Bundle leaseCall(IBinder current, Bundle request) {
        // Internal handles may close inside a different incoming Binder transaction. Restore the server's own identity.
        long identity = listener == null ? 0 : Binder.clearCallingIdentity();
        try { return BoundServiceWire.call(current, BoundServiceWire.LEASE, request); }
        finally { if (listener != null) Binder.restoreCallingIdentity(identity); }
    }
    private void closeLease(IBinder current) { leaseCall(current, BoundServiceWire.operation("close")); }
    @Override public void onServiceConnected(ComponentName name, IBinder control) {
        try {
            if (!name.equals(physicalComponent)) throw new SecurityException("Unexpected physical bound Service component");
            if (!BoundServiceWire.CARRIER.equals(control.getInterfaceDescriptor())) throw new SecurityException("Unexpected bound Service control descriptor");
            IBinder current;
            synchronized (this) { if (closed) return; carrierReady = true; current = lease; }
            Bundle request = BoundServiceWire.operation("add"); request.putBinder("lease", current);
            BoundServiceWire.call(control, BoundServiceWire.CARRIER, request);
            drain();
        } catch (Exception failure) { fail(failure); }
    }
    @Override public void onServiceDisconnected(ComponentName name) { event("disconnected", null); }
    @Override public void onBindingDied(ComponentName name) { event("binding_died", null); }
    @Override public void onNullBinding(ComponentName name) { fail(new EngineException("Physical bound Service returned no control endpoint")); }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(BoundServiceWire.CALLBACK); return true; }
        if (code != BoundServiceWire.CALL) return false;
        Bundle result;
        try {
            EngineProtocol.requireCaller(); data.enforceInterface(BoundServiceWire.CALLBACK);
            Bundle input = data.readBundle(getClass().getClassLoader());
            int caller = Binder.getCallingPid();
            synchronized (this) {
                if (caller != targetPid && caller != serverPid) throw new SecurityException("Unexpected binding publisher");
                if (targetPid == 0 && caller == serverPid && "binding_died".equals(input.getString("operation"))) {
                    publication = input;
                } else if (input.getInt("owner_pid") != targetPid) throw new SecurityException("Binding owner changed");
                if (!closed) publication = input;
            }
            drain(); result = EngineProtocol.response();
        } catch (Exception failure) { result = BoundServiceWire.failure(failure); }
        reply.writeNoException(); reply.writeBundle(result); return true;
    }
    private void drain() {
        Bundle pending;
        synchronized (this) {
            if (closed || !finished || !admitted || publication == null) return;
            String kind = publication.getString("operation");
            if ("connected".equals(kind) && !carrierReady) return;
            pending = publication; publication = null;
            if ("connected".equals(kind)) { if (emitted) return; emitted = true; }
        }
        event(pending.getString("operation"), pending);
    }
    private void event(String kind, Bundle payload) {
        boolean start = false, full;
        synchronized (this) {
            if (closed || terminalQueued) return;
            if ("binding_died".equals(kind) || "failure".equals(kind)) terminalQueued = true;
            if ("disconnected".equals(kind)) { if (disconnectedQueued) return; disconnectedQueued = true; }
            full = events.size() >= 8;
            if (!full) { events.add(() -> deliver(kind, payload)); start = !pumping; pumping = true; }
        }
        if (full) { fail(new EngineException("Bound Service callback queue is full")); return; }
        if (start) try { executor.execute(pumpTask); }
        catch (RejectedExecutionException failure) { fail(failure); }
    }
    private void pump() {
        for (;;) {
            Runnable event;
            synchronized (this) {
                if (closed) { events.clear(); pumping = false; return; }
                event = events.poll(); if (event == null) { pumping = false; return; }
            }
            event.run();
        }
    }
    private void deliver(String kind, Bundle payload) {
        try {
            if ("connected".equals(kind)) {
                Bundle confirmation = BoundServiceWire.operation("delivery"); confirmation.putLong("incarnation", payload.getLong("incarnation"));
                if (!leaseCall(lease, confirmation).getBoolean("active")) {
                    fail(new EngineException("Bound Service publication expired before delivery")); return;
                }
                synchronized (this) { if (closed) return; }
            }
            IBinder service = payload == null ? null : payload.getBinder("service");
            if (listener != null) {
                if ("connected".equals(kind)) { if (service == null) listener.onNullBinding(owner); else listener.onConnected(service, owner); }
                else if ("binding_died".equals(kind)) listener.onBindingDied(owner);
                else if ("disconnected".equals(kind)) listener.onDisconnected(owner);
                else listener.onFailure(new EngineException(payload == null ? "Bound Service failed" : payload.getString("message", "Bound Service failed")));
            } else if (guestCallback != null) {
                RuntimeReflection.invoke(guestCallback, RuntimeReflection.method(Class.forName("android.app.IServiceConnection"),
                        "connected", ComponentName.class, IBinder.class, boolean.class), component, service, !"connected".equals(kind));
            }
            if ("binding_died".equals(kind) || "failure".equals(kind)) close();
        } catch (Exception failure) { fail(failure); }
    }
    private void fail(Exception failure) {
        boolean notify;
        synchronized (this) { notify = !closed; }
        try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
        if (notify) {
            // Rejection cannot queue a second task. The failure callback only records failure/wakes its bounded owner.
            if (listener != null) {
                try { listener.onFailure(failure); }
                catch (RuntimeException callbackFailure) { failure.addSuppressed(callbackFailure); android.util.Log.e("MyliteBound", "Binding failure callback failed", failure); }
            } else {
                try { RuntimeReflection.invoke(guestCallback, RuntimeReflection.method(Class.forName("android.app.IServiceConnection"),
                        "connected", ComponentName.class, IBinder.class, boolean.class), component, null, true); }
                catch (Exception callbackFailure) { failure.addSuppressed(callbackFailure); }
                android.util.Log.e("MyliteBound", "Guest binding failed", failure);
            }
        }
    }
}
