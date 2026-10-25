package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

public final class ActivityDeliveryClaimsTest {
    @Test public void claimSurvivesKeyReplacementAndCannotBeReplayed() throws Exception {
        String oldCap = UUID.randomUUID().toString(), replacement = UUID.randomUUID().toString();
        Set<String> empty = Collections.emptySet();
        Set<String> committed = ActivityDeliveryClaims.consume(empty, oldCap);
        assertTrue("Uncommitted copy mutated the durable state", empty.isEmpty());
        try { ActivityDeliveryClaims.consume(committed, oldCap); fail("One-shot replay was accepted"); }
        catch (SecurityException expected) { }
        Set<String> afterReplacement = ActivityDeliveryClaims.consume(committed, replacement);
        assertTrue(afterReplacement.contains(oldCap));
        assertTrue(afterReplacement.contains(replacement));
        assertEquals(1, committed.size());
    }

    @Test public void failedCommitDoesNotConsumeTheInMemoryCapability() throws Exception {
        String cap = UUID.randomUUID().toString();
        Set<String> previous = Collections.emptySet();
        ActivityDeliveryClaims.consume(previous, cap); // Simulate an AtomicFile failure before publishing the copy.
        assertTrue(previous.isEmpty());
        assertTrue(ActivityDeliveryClaims.consume(previous, cap).contains(cap));
        try { ActivityDeliveryClaims.consume(previous, "untrusted"); fail("Invalid capability ID was accepted"); }
        catch (SecurityException expected) { }
    }
}
