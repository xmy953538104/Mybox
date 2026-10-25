package io.github.xmy953538104.mybox.engine;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Real OS-bound ServiceRecord. The control Binder is never delivered as a guest interface. */
public abstract class BoundServiceStub extends Service {
    private final Map<IBinder, Endpoint> endpoints = new HashMap<>();
    private BoundCarrierLifecycle carrierToken;
    private BoundCarrierLifecycle carrierToken() {
        if (carrierToken == null) carrierToken = new BoundCarrierLifecycle(this, runtime().carrierOwner(), runtime().serverPid());
        return carrierToken;
    }
    private GuestRuntime runtime() {
        GuestRuntime result = Engine.get().runtime();
        if (result == null) throw new SecurityException("Bound Service has no prepared guest reservation");
        return result;
    }
    @Override public final IBinder onBind(Intent intent) {
        IBinder group = intent == null || intent.getExtras() == null ? null : intent.getExtras().getBinder(BoundServiceWire.EXTRA_GROUP);
        try {
            if (!runtime().hasPreparedServiceReservation()) {
                // shortcut: automatic cross-VM rebind needs fresh server preparation; never execute an old lease in an EMPTY VM.
                android.util.Log.w("MyliteBound", "Rejecting OS redelivery without a prepared guest reservation");
                Endpoint cancelled = new Endpoint(group); cancelled.retired = true; return cancelled;
            }
            if (group == null) throw new SecurityException("Bound Service group is absent");
            Bundle request = BoundServiceWire.operation("claim_group"); request.putBinder("group", group); request.putBinder("carrier", carrierToken());
            Bundle claim = BoundServiceWire.checked(runtime().requestBoundServer(EngineProtocol.BOUND_SERVICE, request));
            if (!claim.getBoolean("active")) {
                // A valid OS bind can reach main after its last consumer already unbound.
                Endpoint cancelled = new Endpoint(group); cancelled.retired = true; return cancelled;
            }
            Endpoint endpoint = endpoints.get(group);
            if (endpoint == null) { endpoint = new Endpoint(group); endpoints.put(group, endpoint); }
            for (int i = 0; i < claim.getInt("count"); i++) endpoint.add(claim.getBinder("lease" + i));
            return endpoint;
        } catch (Exception failure) { throw new EngineException("Bound Service admission could not publish its control endpoint", failure); }
    }
    @Override public final int onStartCommand(Intent intent, int flags, int startId) {
        boolean prepared = runtime().hasPreparedServiceReservation();
        if (!BoundCarrierLifecycle.acceptStart(this, prepared ? runtime().carrierOwner() : null, prepared, intent, startId)) {
            android.util.Log.w("MyliteBound", "Ignored stale carrier start without activating guest bindings");
            return START_NOT_STICKY;
        }
        carrierToken();
        for (Endpoint endpoint : new ArrayList<>(endpoints.values())) endpoint.activate();
        return START_NOT_STICKY;
    }
    @Override public final boolean onUnbind(Intent intent) {
        IBinder group = intent == null || intent.getExtras() == null ? null : intent.getExtras().getBinder(BoundServiceWire.EXTRA_GROUP);
        // The server lease owns guest unbind. AMS may retain this filter's control Binder
        // while another virtual filter keeps the same physical ServiceRecord alive.
        return false;
    }
    @Override public final void onDestroy() {
        for (Endpoint endpoint : new ArrayList<>(endpoints.values())) endpoint.removeAll();
        endpoints.clear();
        try {
            if (runtime().hasPreparedServiceReservation()) {
                Bundle request = BoundServiceWire.operation("carrier_gone"); request.putBinder("carrier", carrierToken);
                BoundServiceWire.checked(runtime().requestBoundServer(EngineProtocol.BOUND_SERVICE, request));
            }
        } finally { super.onDestroy(); }
    }

    @Override public final void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config); runtime().components().boundConfiguration(config);
    }
    @Override public final void onLowMemory() { super.onLowMemory(); runtime().components().boundMemory(-1); }
    @Override public final void onTrimMemory(int level) { super.onTrimMemory(level); runtime().components().boundMemory(level); }

    private final class Endpoint extends Binder {
        final IBinder group;
        final Map<IBinder, Subscriber> subscribers = new HashMap<>();
        volatile boolean retired;
        Endpoint(IBinder group) { this.group = group; attachInterface(null, BoundServiceWire.CARRIER); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(BoundServiceWire.CARRIER); return true; }
            if (code != BoundServiceWire.CALL) return false;
            Bundle result;
            try {
                EngineProtocol.requireCaller(); data.enforceInterface(BoundServiceWire.CARRIER);
                Bundle input = data.readBundle(getClass().getClassLoader()); IBinder lease = input.getBinder("lease");
                if (lease == null) throw new SecurityException("Missing bound Service lease");
                String operation = input.getString("operation");
                if (retired) throw new SecurityException("Bound carrier incarnation retired");
                if ("remove".equals(operation)) {
                    if (Binder.getCallingPid() != runtime().serverPid()) throw new SecurityException("Only the engine server may retire a binding");
                    // Marking is immediate, even if guest onBind currently blocks the main thread.
                    synchronized (subscribers) { Subscriber old = subscribers.get(lease); if (old != null) old.closed = true; }
                    runtime().post(() -> remove(lease));
                } else if ("activate".equals(operation)) {
                    if (Binder.getCallingPid() != runtime().serverPid()) throw new SecurityException("Only the engine server may activate a binding");
                    runtime().post(() -> {
                        Subscriber subscriber; synchronized (subscribers) { subscriber = subscribers.get(lease); }
                        if (subscriber != null) activate(subscriber);
                    });
                } else if ("add".equals(operation)) {
                    // Server lease authentication, not caller-supplied component/sink/identity.
                    Bundle claim = claim(lease);
                    runtime().post(() -> addClaimed(lease, claim));
                } else throw new IllegalArgumentException("Unknown bound carrier operation");
                result = EngineProtocol.response();
            } catch (Exception failure) { result = BoundServiceWire.failure(failure); }
            reply.writeNoException(); reply.writeBundle(result); return true;
        }
        private Bundle claim(IBinder lease) {
            Bundle request = BoundServiceWire.operation("claim_lease"); request.putBinder("lease", lease); request.putBinder("endpoint", this);
            Bundle result = BoundServiceWire.checked(runtime().requestBoundServer(EngineProtocol.BOUND_SERVICE, request));
            if (result.getBoolean("active") && !group.equals(result.getBinder("group"))) throw new SecurityException("Binding belongs to a different OS filter");
            return result;
        }
        void add(IBinder lease) { addClaimed(lease, claim(lease)); }
        void addClaimed(IBinder lease, Bundle claim) {
            if (retired || !claim.getBoolean("active")) { retireIfUnused(); return; }
            Subscriber subscriber;
            synchronized (subscribers) {
                subscriber = subscribers.get(lease);
                if (subscriber == null) { subscriber = new Subscriber(lease, claim); subscribers.put(lease, subscriber); }
            }
            activate(subscriber);
        }
        void activate() { for (Subscriber subscriber : snapshot()) activate(subscriber); }
        private ArrayList<Subscriber> snapshot() { synchronized (subscribers) { return new ArrayList<>(subscribers.values()); } }
        private void activate(Subscriber subscriber) {
            if (retired || subscriber.closed || subscriber.delivered) return;
            try {
                Bundle authorized = claim(subscriber.lease);
                if (!authorized.getBoolean("active")) { remove(subscriber.lease); return; }
                if (!authorized.getBoolean("admitted")) return;
                boolean auto = (subscriber.claim.getLong("flags") & BIND_AUTO_CREATE) != 0;
                android.content.ComponentName component = subscriber.claim.getParcelable("component");
                if (!auto && !runtime().components().hasService(component)) return;
                runtime().ensureBoundForService();
                if (retired || subscriber.closed || !claim(subscriber.lease).getBoolean("active")) { remove(subscriber.lease); return; }
                Bundle request = new Bundle(subscriber.claim); request.putBinder("connection", subscriber.lease);
                Bundle publication = runtime().components().dispatch(EngineProtocol.BIND_SERVICE, request);
                if (!publication.getBoolean("bound")) return;
                subscriber.bound = true;
                Bundle publish = BoundServiceWire.operation("publish"); publish.putLong("incarnation", publication.getLong("incarnation"));
                Bundle checked = BoundServiceWire.call(subscriber.lease, BoundServiceWire.LEASE, publish);
                if (retired || subscriber.closed || !checked.getBoolean("active")) { remove(subscriber.lease); return; }
                subscriber.delivered = true;
                ServerBoundServices.send(subscriber.claim.getBinder("sink"), Process.myPid(), "connected",
                        publication.getBinder("service"), publication.getLong("incarnation"), null);
            } catch (Exception failure) {
                try { remove(subscriber.lease); }
                catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                try { ServerBoundServices.send(subscriber.claim.getBinder("sink"), Process.myPid(), "failure", null, 0, failure); }
                catch (Exception deliveryFailure) { failure.addSuppressed(deliveryFailure); android.util.Log.w("MyliteBound", "Failed publication receiver is unavailable", failure); }
            }
        }
        void remove(IBinder lease) {
            Subscriber subscriber;
            synchronized (subscribers) { subscriber = subscribers.remove(lease); if (subscriber != null) subscriber.closed = true; }
            if (subscriber != null && subscriber.bound) {
                try {
                    Bundle request = new Bundle(subscriber.claim); request.putBinder("connection", lease);
                    runtime().components().dispatch(EngineProtocol.UNBIND_SERVICE, request);
                } catch (Exception failure) { throw new EngineException("Bound Service lease cleanup failed", failure); }
            }
            if (!retired) retireIfUnused();
        }
        private void retireIfUnused() {
            synchronized (subscribers) { if (!subscribers.isEmpty()) return; }
            if (!group.isBinderAlive()) { retired = true; if (endpoints.get(group) == this) endpoints.remove(group); return; }
            Bundle state = BoundServiceWire.call(group, BoundServiceWire.GROUP, BoundServiceWire.operation("state"));
            if (!state.getBoolean("active") || state.getInt("count") == 0) {
                retired = true; if (endpoints.get(group) == this) endpoints.remove(group);
            }
        }
        void removeAll() { retired = true; for (Subscriber subscriber : snapshot()) remove(subscriber.lease); }
    }
    private static final class Subscriber {
        final IBinder lease;
        final Bundle claim;
        volatile boolean closed;
        boolean delivered, bound;
        Subscriber(IBinder lease, Bundle claim) { this.lease = lease; this.claim = claim; }
    }
    public static final class P0 extends BoundServiceStub { }
    public static final class P1 extends BoundServiceStub { }
    public static final class P2 extends BoundServiceStub { }
    public static final class P3 extends BoundServiceStub { }
    public static final class P4 extends BoundServiceStub { }
    public static final class P5 extends BoundServiceStub { }
    public static final class P6 extends BoundServiceStub { }
    public static final class P7 extends BoundServiceStub { }
}
