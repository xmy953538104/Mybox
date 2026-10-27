package io.github.xmy953538104.mybox.engine;

import org.junit.Test;

import static org.junit.Assert.*;

public final class PackageQueryBoundaryTest {
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
