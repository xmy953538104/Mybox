package io.github.xmy953538104.mybox.engine;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

/** One-shot admission belongs to a capability, not to its replaceable PendingIntent key. */
final class ActivityDeliveryClaims {
    static final int MAX_CLAIMS = 65_536;
    private ActivityDeliveryClaims() { }

    static boolean valid(String cap) { return cap != null && cap.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }

    static Set<String> consume(Set<String> previous, String cap) throws IOException {
        if (!valid(cap)) throw new SecurityException("Invalid Activity capability ID");
        if (previous.contains(cap)) throw new SecurityException("One-shot Activity capability was already delivered");
        if (previous.size() >= MAX_CLAIMS) throw new IOException("One-shot Activity delivery ledger is full");
        // The caller publishes this copy only after the AtomicFile commit succeeds.
        Set<String> next = new TreeSet<>(previous);
        next.add(cap);
        return next;
    }
}
