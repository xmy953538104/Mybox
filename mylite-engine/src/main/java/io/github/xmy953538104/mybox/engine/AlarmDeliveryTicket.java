package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

/** One OS delivery budget. Expiry revokes future admission, not an already-running Binder call. */
final class AlarmDeliveryTicket extends Binder {
    static final String DESCRIPTOR = "io.github.xmy953538104.mybox.engine.AlarmDelivery.v1";
    static final String DEADLINE = "alarm_deadline", TICKET = "alarm_ticket";
    final long deadline;
    private final OsPendingStore.Creator creator;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean admitted = new AtomicBoolean();
    private volatile ProcessRegistry.Identity target;

    AlarmDeliveryTicket(OsPendingStore.Creator creator, long deadline) {
        this.creator = creator; this.deadline = deadline; attachInterface(null, DESCRIPTOR);
    }
    void target(ProcessRegistry.Identity identity) {
        if (identity == null || identity.pid <= 0 || target != null) throw new SecurityException("Alarm target is already assigned");
        target = identity;
    }
    long remaining() { return Math.max(0, deadline - SystemClock.elapsedRealtime()); }
    void requireActive() {
        if (closed.get() || remaining() == 0) throw new EngineException("OS alarm delivery deadline expired");
    }
    boolean close() { return closed.compareAndSet(false, true); }
    void attach(Bundle input) { input.putLong(DEADLINE, deadline); input.putBinder(TICKET, this); }

    @Override protected boolean onTransact(int code, Parcel input, Parcel reply, int flags) {
        if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
        input.enforceInterface(DESCRIPTOR);
        input.readBundle(getClass().getClassLoader());
        requireTarget();
        requireActive();
        if (!admitted.compareAndSet(false, true)) throw new SecurityException("Alarm delivery was already admitted");
        reply.writeNoException(); reply.writeBundle(EngineProtocol.response()); return true;
    }

    void requireCompletion(boolean success) {
        requireTarget(); requireActive();
        if (success && !admitted.get()) throw new SecurityException("Receiver completion preceded actual admission");
    }
    private void requireTarget() {
        CallerIdentity caller = EngineServerProvider.resolveCaller(); creator.requireCaller(caller);
        ProcessRegistry.Identity actual = EngineServerProvider.registeredProcess(Binder.getCallingUid(), Binder.getCallingPid());
        if (target == null || !target.same(actual)) throw new SecurityException("Alarm belongs to another actual process generation");
        try { creator.requireCurrent(Engine.get()); }
        catch (Exception failure) { throw new SecurityException("Alarm creator changed before admission", failure); }
    }

    static long remaining(Bundle input, long ordinaryTimeout) {
        if (input == null || !input.containsKey(DEADLINE)) return ordinaryTimeout;
        long remaining = input.getLong(DEADLINE) - SystemClock.elapsedRealtime();
        IBinder ticket = input.getBinder(TICKET);
        if (remaining <= 0 || ticket == null || !ticket.isBinderAlive()) throw new EngineException("Alarm work expired before execution");
        return Math.min(remaining, ordinaryTimeout);
    }
    static void admit(Bundle input) {
        if (!input.containsKey(DEADLINE)) return;
        remaining(input, 8_000);
        EngineProtocol.request(input.getBinder(TICKET), DESCRIPTOR, IBinder.FIRST_CALL_TRANSACTION, EngineProtocol.response());
        remaining(input, 8_000);
    }
}
