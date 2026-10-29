package io.github.xmy953538104.mybox.engine;

import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuestShortcutCallsTest {
    public static class Physical {
        int calls; String packageName;
        public int getIconMaxDimensions(String name, int user) { calls++; packageName = name; return 123; }
        public int getMaxShortcutCountPerActivity(String name, int user) { calls++; packageName = name; return 5; }
        public void removeAllDynamicShortcuts(String name, int user) { throw new AssertionError("Mutation reached OS"); }
        public int getIconMaxDimensions(String name) { throw new AssertionError("Unknown shape reached OS"); }
    }
    @Test public void selfQueriesDelegateActualResultWithoutChangingArguments() throws Throwable {
        Physical physical = new Physical();
        GuestShortcutCalls calls = new GuestShortcutCalls(physical, "guest.app", "host.app", 0);
        Object[] args = {"guest.app", 0};
        for (String name : new String[]{"getIconMaxDimensions", "getMaxShortcutCountPerActivity"}) {
            Method method = Physical.class.getMethod(name, String.class, int.class);
            assertEquals(name.startsWith("getIcon") ? 123 : 5, calls.invoke(null, method, args));
            assertEquals("host.app", physical.packageName); assertEquals("guest.app", args[0]);
            calls.invoke(null, method, new Object[]{"host.app", 0});
        }
        assertEquals(4, physical.calls);
    }
    @Test public void foreignClaimsUsersMutationAndUnknownShapesNeverReachOs() throws Throwable {
        Physical physical = new Physical();
        GuestShortcutCalls calls = new GuestShortcutCalls(physical, "guest.app", "host.app", 0);
        Method query = Physical.class.getMethod("getIconMaxDimensions", String.class, int.class);
        for (Object[] args : new Object[][]{{"other.app", 0}, {"guest.app", 10}, {null, 0}}) {
            try { calls.invoke(null, query, args); fail(); } catch (SecurityException expected) { }
        }
        try { calls.invoke(null, Physical.class.getMethod("removeAllDynamicShortcuts", String.class, int.class), new Object[]{"guest.app", 0}); fail(); }
        catch (UnsupportedOperationException expected) { }
        try { calls.invoke(null, Physical.class.getMethod("getIconMaxDimensions", String.class), new Object[]{"guest.app"}); fail(); }
        catch (UnsupportedOperationException expected) { }
        assertEquals(0, physical.calls);
    }
}
