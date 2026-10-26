package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SharedLibraryQueryTest {
    @Test public void mapsTheBoundGuestAndCopiesBothFlagWidthsWithoutLosingOtherBits() {
        Object[] legacy = {"com.android.vending", 0x00420000, 0};
        Object[] mapped = GuestSharedLibraries.queryArguments(legacy, "host", "com.android.vending", 10);
        assertArrayEquals(new Object[]{"host", 0x00020000, 10}, mapped);
        assertArrayEquals(new Object[]{"com.android.vending", 0x00420000, 0}, legacy);
        assertTrue(mapped[1] instanceof Integer);
        long flags = (1L << 40) | 0x00420000L;
        Object[] modern = {"com.google.android.gms", flags, 0};
        Object[] wide = GuestSharedLibraries.queryArguments(modern, "host", "com.google.android.gms", 0);
        assertEquals((1L << 40) | 0x00020000L, wide[1]); assertTrue(wide[1] instanceof Long);
        assertEquals(flags, modern[1]);
    }

    @Test public void keepsRealHostQueriesOnTheirActualUserEvenBeforeGuestBinding() {
        assertArrayEquals(new Object[]{"host", 0L, 10},
                GuestSharedLibraries.queryArguments(new Object[]{"host", 0L, 10}, "host", null, 10));
    }

    @Test public void rejectsForeignAttributionUsersAndUnknownShapes() {
        for (Object[] request : new Object[][]{{"other", 0L, 0}, {"guest", 0L, 10}, {"host", 0L, 0}}) {
            try { GuestSharedLibraries.queryArguments(request, "host", "guest", 10); fail("Foreign query accepted"); }
            catch (SecurityException expected) { }
        }
        try { GuestSharedLibraries.queryArguments(new Object[]{"guest", 0L, 0}, "host", null, 0); fail("Unbound guest accepted"); }
        catch (SecurityException expected) { }
        for (Object[] request : new Object[][]{null, {}, {"guest", 0L}, {"guest", 0.0, 0}, {"guest", 0L, 0L}}) {
            try { GuestSharedLibraries.queryArguments(request, "host", "guest", 0); fail("Unknown shape accepted"); }
            catch (EngineException expected) { }
        }
    }
}
