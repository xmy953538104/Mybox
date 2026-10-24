package io.github.xmy953538104.mybox.engine;

/** One launch can be retried only after a definite failure before any guest claim. */
final class AccountUiTicketState {
    static final class Binding {
        final Object record, operation;
        final long operationId;
        Binding(Object record, Object operation, long operationId) {
            if (record == null || (operation == null ? operationId != 0 : operationId <= 0)) {
                throw new SecurityException("Incomplete account UI record/operation binding");
            }
            this.record = record; this.operation = operation; this.operationId = operationId;
        }
        void require(Object record, Object operation, long operationId) {
            if (this.record != record || this.operation != operation || this.operationId != operationId) {
                throw new SecurityException("Account UI ticket belongs to another record or operation");
            }
        }
    }
    final long expiresAt;
    private Object reservation;
    private String state = "AVAILABLE";
    AccountUiTicketState(long expiresAt) { this.expiresAt = expiresAt; }
    synchronized void available(long now) {
        expire(now);
        if (!state.equals("AVAILABLE")) throw new SecurityException("Account UI ticket is " + state);
    }
    synchronized void reserve(Object record, long now) {
        available(now); reservation = record; state = "RESERVED";
    }
    synchronized void dispatched(Object record, long now) {
        expire(now); require(record);
        if (state.equals("RESERVED")) state = "DISPATCHED";
        else if (!state.equals("DISPATCHED") && !state.equals("CONSUMED")) {
            throw new SecurityException("Account UI ticket cannot be dispatched: " + state);
        }
    }
    synchronized void pending(Object record, long now) {
        expire(now); require(record);
        if (!state.equals("RESERVED") && !state.equals("DISPATCHED")) {
            throw new SecurityException("Account UI ticket is no longer pending: " + state);
        }
    }
    synchronized void consume(Object record, long now) {
        expire(now); require(record);
        if (!state.equals("RESERVED") && !state.equals("DISPATCHED")) {
            throw new SecurityException("Account UI ticket cannot be consumed twice");
        }
        state = "CONSUMED";
    }
    synchronized void cancel(Object record, boolean definiteBeforeClaim, long now) {
        if (reservation != record || state.equals("CONSUMED")) return;
        expire(now);
        if (state.equals("RESERVED") && definiteBeforeClaim) { state = "AVAILABLE"; reservation = null; }
        else revoke();
    }
    synchronized void revoke() { if (!state.equals("CONSUMED")) state = "REVOKED"; }
    synchronized boolean terminal(long now) {
        expire(now); return state.equals("CONSUMED") || state.equals("REVOKED") || state.equals("EXPIRED");
    }
    private void expire(long now) {
        if (now >= expiresAt && !state.equals("CONSUMED") && !state.equals("REVOKED")) state = "EXPIRED";
    }
    private void require(Object record) {
        if (reservation != record) throw new SecurityException("Account UI ticket belongs to another Activity record");
    }
}
