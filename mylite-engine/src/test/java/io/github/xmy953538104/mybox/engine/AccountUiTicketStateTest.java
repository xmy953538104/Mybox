package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class AccountUiTicketStateTest {
    @Test public void sameActivityCannotBorrowAnotherOperationGrant() {
        Object record = new Object(), operation = new Object();
        AccountUiTicketState.Binding binding = new AccountUiTicketState.Binding(record, operation, 11);
        binding.require(record, operation, 11);
        denied(() -> binding.require(record, new Object(), 11));
        denied(() -> binding.require(record, operation, 12));
        denied(() -> binding.require(new Object(), operation, 11));
        denied(() -> binding.require(record, null, 0));
        denied(() -> new AccountUiTicketState.Binding(record, null, 11));
        AccountUiTicketState state = new AccountUiTicketState(100);
        state.reserve(binding, 1); state.pending(binding, 2); state.dispatched(binding, 3);
        state.consume(binding, 4); denied(() -> state.pending(binding, 5));
        denied(() -> state.consume(new AccountUiTicketState.Binding(record, operation, 11), 5));
    }
    @Test public void queuedFailureAndPartialClearNeverReturnAuthority() {
        Object binding = new Object();
        AccountUiTicketState queued = new AccountUiTicketState(100);
        queued.reserve(binding, 1); queued.cancel(binding, false, 2);
        denied(() -> queued.available(3)); denied(() -> queued.pending(binding, 3));
        AccountUiTicketState clearing = new AccountUiTicketState(10);
        clearing.reserve(binding, 1); clearing.dispatched(binding, 2);
        denied(() -> clearing.pending(binding, 10));
        clearing.cancel(binding, true, 11);
        denied(() -> clearing.available(12)); denied(() -> clearing.consume(binding, 12));
    }
    @Test public void definiteFailureRetiresReservationBeforeRetry() {
        AccountUiTicketState state = new AccountUiTicketState(100);
        Object old = new Object(), next = new Object();
        state.reserve(old, 1); state.cancel(old, true, 2); state.reserve(next, 3);
        denied(() -> state.consume(old, 4)); state.consume(next, 4);
        denied(() -> state.reserve(new Object(), 5));
    }
    @Test public void uncertainOrDispatchedFailureCannotBeRetried() {
        AccountUiTicketState unknown = new AccountUiTicketState(100);
        Object record = new Object(); unknown.reserve(record, 1); unknown.cancel(record, false, 2);
        denied(() -> unknown.available(3));
        AccountUiTicketState dispatched = new AccountUiTicketState(100);
        dispatched.reserve(record, 1); dispatched.dispatched(record, 2); dispatched.cancel(record, true, 3);
        denied(() -> dispatched.available(3));
    }
    @Test public void claimIsOneShotEvenWhenCompletionAckIsLost() {
        AccountUiTicketState state = new AccountUiTicketState(100); Object record = new Object();
        state.reserve(record, 1); state.consume(record, 2); state.cancel(record, true, 3);
        denied(() -> state.available(4)); denied(() -> state.consume(record, 4));
    }
    @Test public void expiryAndRevocationApplyToReservedRecords() {
        AccountUiTicketState expired = new AccountUiTicketState(10); Object record = new Object();
        expired.reserve(record, 1); denied(() -> expired.consume(record, 10));
        expired.cancel(record, true, 11); denied(() -> expired.available(12));
        AccountUiTicketState revoked = new AccountUiTicketState(100);
        revoked.reserve(record, 1); revoked.revoke(); denied(() -> revoked.consume(record, 2));
        denied(() -> revoked.dispatched(record, 2)); denied(() -> expired.dispatched(record, 12));
    }
    @Test public void simultaneousReservationsHaveExactlyOneWinner() throws Exception {
        AccountUiTicketState state = new AccountUiTicketState(100);
        CountDownLatch start = new CountDownLatch(1); AtomicInteger accepted = new AtomicInteger();
        Runnable action = () -> {
            try { start.await(); state.reserve(new Object(), 1); accepted.incrementAndGet(); }
            catch (SecurityException expected) { }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        };
        Thread a = new Thread(action), b = new Thread(action); a.start(); b.start(); start.countDown(); a.join(); b.join();
        assertEquals(1, accepted.get());
    }
    private static void denied(Runnable action) {
        try { action.run(); fail("Invalid ticket transition succeeded"); } catch (SecurityException expected) { }
    }
}
