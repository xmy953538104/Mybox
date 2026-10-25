package io.github.xmy953538104.mybox.engine;

import java.util.LinkedHashSet;
import java.util.Set;

/** Process-local timing only: never retains arguments, results or virtual identities. */
final class IdentityRpcStats {
    static final IdentityRpcStats GLOBAL = new IdentityRpcStats();
    private static final int MAX_SAMPLES = 16, MAX_SNAPSHOTS = 3;
    private final Set<Ticket> active = new LinkedHashSet<>();
    private long entered, exited, fast, slow, totalElapsedMs, maxElapsedMs;
    private int snapshots;

    static final class Ticket {
        final int callerPid;
        final long enteredMs;
        Ticket(int callerPid, long enteredMs) { this.callerPid = callerPid; this.enteredMs = enteredMs; }
    }

    synchronized Ticket enter(int callerPid, long nowMs) {
        Ticket ticket = new Ticket(callerPid, nowMs);
        entered++;
        if (active.size() < MAX_SAMPLES) active.add(ticket);
        return ticket;
    }

    synchronized void exit(Ticket ticket, long nowMs) {
        long elapsed = Math.max(0, nowMs - ticket.enteredMs);
        exited++;
        if (elapsed <= 200) fast++; else slow++;
        totalElapsedMs += elapsed;
        maxElapsedMs = Math.max(maxElapsedMs, elapsed);
        active.remove(ticket);
    }

    synchronized String snapshot(long nowMs) {
        if (entered == 0 || snapshots >= MAX_SNAPSHOTS) return null;
        snapshots++;
        StringBuilder result = new StringBuilder("entered=").append(entered).append(" exited=").append(exited)
                .append(" le200ms=").append(fast).append(" gt200ms=").append(slow)
                .append(" exitedTotalMs=").append(totalElapsedMs).append(" exitedMaxMs=").append(maxElapsedMs)
                .append(" inflight=").append(entered - exited).append(" omitted=").append(entered - exited - active.size())
                .append(" samplesPidAgeMs=[");
        boolean first = true;
        for (Ticket ticket : active) {
            if (!first) result.append(',');
            first = false;
            result.append(ticket.callerPid).append(':').append(Math.max(0, nowMs - ticket.enteredMs));
        }
        return result.append(']').toString();
    }
}
