package io.github.xmy953538104.mybox.engine;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PackageQueryBoundaryTest {
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
