package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class ServerGoogleServicesTest {
    @Test public void repeatedDeathsAndServerRestartsCannotResetTheRecoveryWindow() {
        int saved = 0;
        for (int event = 0; event < 100; event++) {
            int next = ServerGoogleServices.nextAttempt(7, 7, 1000, 1000 + event, saved);
            if (event < 3) { assertEquals(event + 1, next); saved = next; }
            else assertEquals(0, next);
        }
        assertEquals(1, ServerGoogleServices.nextAttempt(7, 7, 1000, 301000, saved));
        assertEquals(1, ServerGoogleServices.nextAttempt(7, 8, 1000, 1100, saved));
        assertEquals(1, ServerGoogleServices.nextAttempt(7, 7, 1000, 500, saved));
    }

    @Test(expected = IllegalStateException.class) public void corruptCounterDoesNotResetToUnlimitedRetries() {
        ServerGoogleServices.nextAttempt(7, 7, 1000, 1100, -1);
    }
}
