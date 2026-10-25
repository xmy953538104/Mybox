package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import static org.junit.Assert.fail;

public final class PendingIntentDisplayPolicyTest {
    @Test public void acceptsTheActualDefaultRatherThanAssumingZero() {
        ServerPendingIntents.requireHostDisplay(37, 37);
        try { ServerPendingIntents.requireHostDisplay(0, 37); fail("An arbitrary zero display was accepted"); }
        catch (UnsupportedOperationException expected) { }
        try { ServerPendingIntents.requireHostDisplay(38, 37); fail("A valid secondary display was silently redirected"); }
        catch (UnsupportedOperationException expected) { }
        try { ServerPendingIntents.requireHostDisplay(-1, 37); fail("An invalid display was accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
