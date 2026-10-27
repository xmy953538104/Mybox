package io.github.xmy953538104.mybox.engine;

import org.junit.Test;

import static org.junit.Assert.*;

public final class PackageQueryBoundaryTest {
    @Test public void inventoryFlagsRequireTheCurrentUserAndDoNotTruncateNewBits() {
        for (Number flags : new Number[]{Integer.valueOf(0x402040), Long.valueOf(0x402040)}) {
            Object[] input = {flags, 0};
            assertEquals(0x2040, GuestPackageQueries.inventoryFlags(input));
            assertSame(flags, input[0]);
        }
        assertEquals(0x80000000, GuestPackageQueries.inventoryFlags(new Object[]{0x80000000, 0}));
        assertEquals(0x80000000, GuestPackageQueries.inventoryFlags(new Object[]{0x80000000L, 0}));
        for (Object user : new Object[]{1, -1, Long.valueOf(0), null}) {
            try { GuestPackageQueries.inventoryFlags(new Object[]{0, user}); fail(); }
            catch (SecurityException expected) { }
        }
        for (long flags : new long[]{0x100000000L, -0x100000000L}) {
            try { GuestPackageQueries.inventoryFlags(new Object[]{flags, 0}); fail(); }
            catch (UnsupportedOperationException expected) { }
        }
        for (Object[] bad : new Object[][]{null, {}, {0}, {"flags", 0}, {0, 0, "unexpected"}}) {
            try { GuestPackageQueries.inventoryFlags(bad); fail(); }
            catch (EngineException expected) { }
        }
    }
    @Test public void metadataFlagsStayInOneUserWithoutLosingTypeOrOtherBits() {
        for (Number flags : new Number[]{Integer.valueOf(0x402040), Long.valueOf(0x100402040L)}) {
            Object[] input = {"fixture.package", flags, 0};
            Object[] mapped = GuestPackageQueries.currentUserMetadataArguments(input);
            assertNotSame(input, mapped);
            assertSame(flags, input[1]);
            assertSame(flags.getClass(), mapped[1].getClass());
            assertEquals(flags.longValue() & ~0x400000L, ((Number) mapped[1]).longValue());
            assertEquals(input[0], mapped[0]); assertEquals(input[2], mapped[2]);
        }
        Object[] ordinary = {"fixture.package", Long.valueOf(0x100002040L), 0};
        assertSame(ordinary, GuestPackageQueries.currentUserMetadataArguments(ordinary));
        for (Object user : new Object[]{1, -1, Long.valueOf(0), null}) {
            try { GuestPackageQueries.currentUserMetadataArguments(new Object[]{"fixture.package", 0x402040, user}); fail(); }
            catch (SecurityException expected) { }
        }
        for (Object[] bad : new Object[][]{null, {}, {"fixture.package", 0}, {"fixture.package", "flags", 0}}) {
            try { GuestPackageQueries.currentUserMetadataArguments(bad); fail(); }
            catch (EngineException expected) { }
        }
    }
    @Test public void authoritiesAndPermissionsAreNotInterpretedAsPackageNames() {
        assertFalse(GuestPackageQueries.isPackageRead("resolveContentProvider"));
        assertFalse(GuestPackageQueries.isPackageRead("getPermissionGroupInfo"));
        assertFalse(GuestPackageQueries.isPackageRead("queryPermissionsByGroup"));
        assertTrue(GuestPackageQueries.isPackageRead("getPackageInfo"));
        assertTrue(GuestPackageQueries.isPackageRead("getApplicationInfo"));
    }

    @Test public void coreGoogleIdentitiesHaveNoPhysicalFallback() {
        assertTrue(GuestPackageQueries.isGoogleIdentity("com.google.android.gms"));
        assertTrue(GuestPackageQueries.isGoogleIdentity("com.google.android.gsf"));
        assertTrue(GuestPackageQueries.isGoogleIdentity("com.android.vending"));
        assertFalse(GuestPackageQueries.isGoogleIdentity("com.google.android.webview"));
        assertFalse(GuestPackageQueries.isGoogleIdentity(null));
    }
}
