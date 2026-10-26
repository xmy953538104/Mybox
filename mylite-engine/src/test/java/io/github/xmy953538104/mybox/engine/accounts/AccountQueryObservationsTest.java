package io.github.xmy953538104.mybox.engine.accounts;

import static org.junit.Assert.*;
import org.junit.Test;

public class AccountQueryObservationsTest {
    @Test public void enumerationCallsitesAreLimitedToTheFirstTwoOrdinaryRequests() {
        GuestAccounts.QueryObservations observations = new GuestAccounts.QueryObservations();
        assertFalse(observations.retainEnumerationCaller(0));
        assertTrue(observations.retainEnumerationCaller(observations.begin("getAccounts")));
        assertFalse(observations.retainEnumerationCaller(observations.begin("getAuthToken")));
        assertTrue(observations.retainEnumerationCaller(observations.begin("getAccounts")));
        assertFalse(observations.retainEnumerationCaller(observations.begin("getAccounts")));
        assertFalse(observations.retainEnumerationCaller(-1));
    }
    @Test public void snapshotsDistinguishRealEmptyVisibleAndFailedQueriesWithoutAccountValues() {
        GuestAccounts.QueryObservations observations = new GuestAccounts.QueryObservations();
        int ordinary = observations.begin("getAccounts"); observations.completed(ordinary, 0, -1);
        int owner = observations.begin("getAccountsByTypeForPackage"); observations.completed(owner, 1, -1);
        observations.failed(observations.begin("getAccountsByTypeForPackage"));
        observations.completed(observations.begin("getAccountVisibility"), -1, 4);
        observations.completed(observations.begin("getAuthToken"), -1, -1);
        assertEquals(-1, observations.begin("principal@example.invalid/private-token"));
        int[] values = observations.snapshot();
        assertArrayEquals(new int[]{1,1,0,1,0}, java.util.Arrays.copyOfRange(values, 0, 5));
        assertArrayEquals(new int[]{2,1,1,0,1}, java.util.Arrays.copyOfRange(values, 5, 10));
        assertArrayEquals(new int[]{1,1,0,0,0}, java.util.Arrays.copyOfRange(values, 25, 30));
        assertArrayEquals(new int[]{0,0,0,0,1}, observations.visibilitySnapshot());
        values[0] = 999; assertEquals(1, observations.snapshot()[0]);
        int[] visibility = observations.visibilitySnapshot(); visibility[4] = 999;
        assertEquals(1, observations.visibilitySnapshot()[4]);
    }

    @Test public void concurrentCountsRetainEveryActualOperation() throws Exception {
        GuestAccounts.QueryObservations observations = new GuestAccounts.QueryObservations();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<?>> workers = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) workers.add(pool.submit(() -> {
                for (int j = 0; j < 1000; j++) observations.completed(observations.begin("getAccounts"), 1, -1);
            }));
            for (java.util.concurrent.Future<?> worker : workers) worker.get();
        } finally { pool.shutdownNow(); }
        assertArrayEquals(new int[]{4000,4000,0,0,4000}, java.util.Arrays.copyOfRange(observations.snapshot(), 0, 5));
    }
}
