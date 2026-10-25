package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class IdentityRpcStatsTest {
    @Test public void overlappingCallsRetainFastSlowTotalsAndBoundInflightSamplesAndSnapshots() {
        IdentityRpcStats stats = new IdentityRpcStats();
        assertNull(stats.snapshot(999));
        IdentityRpcStats.Ticket[] calls = new IdentityRpcStats.Ticket[17];
        for (int index = 0; index < calls.length; index++) calls[index] = stats.enter(100 + index, 1000);
        stats.exit(calls[0], 1200);
        stats.exit(calls[1], 1201);
        String snapshot = stats.snapshot(1250);
        assertTrue(snapshot, snapshot.contains("entered=17 exited=2 le200ms=1 gt200ms=1 exitedTotalMs=401 exitedMaxMs=201 inflight=15 omitted=1"));
        assertTrue(snapshot, snapshot.contains("samplesPidAgeMs=[102:250,"));
        assertFalse(snapshot, snapshot.contains("116:250"));
        for (int index = 2; index < calls.length; index++) stats.exit(calls[index], 1300);
        snapshot = stats.snapshot(1350);
        assertTrue(snapshot, snapshot.contains("entered=17 exited=17 le200ms=1 gt200ms=16 exitedTotalMs=4901 exitedMaxMs=300 inflight=0 omitted=0 samplesPidAgeMs=[]"));
        assertNotNull(stats.snapshot(1400));
        assertNull(stats.snapshot(1500));
    }
}
