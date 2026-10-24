package io.github.xmy953538104.mybox.engine;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ContentObserverPolicyTest {
    @Test public void pathsUseSegmentsAndRespectAncestorDescendantAndSkipRules() {
        assertTrue(matches("authority/a", "authority/a", false, 0));
        assertFalse(matches("authority/a", "authority/a/b", false, 0));
        assertTrue(matches("authority/a", "authority/a/b", true, 0));
        assertTrue(matches("authority/a/b", "authority/a", false, 0));
        assertFalse(matches("authority/a", "other/a", true, 0));
        assertFalse(matches("authority/a", "authority/ab", true, 0));
        assertFalse(matches("authority/a", "authority/a", true, 2));
        assertFalse(matches("authority/a/b", "authority/a", true, 2));
        assertTrue(matches("authority/a", "authority/a/b", true, 2));
        assertTrue(matches("authority/a", "authority/a", false, 2 | 8));
    }
    private static boolean matches(String registered, String changed, boolean descendants, int flags) {
        return ContentObserverPolicy.matches(Arrays.asList(registered.split("/")), Arrays.asList(changed.split("/")), descendants, flags);
    }
}
