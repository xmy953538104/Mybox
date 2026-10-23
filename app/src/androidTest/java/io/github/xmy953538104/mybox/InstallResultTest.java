package io.github.xmy953538104.mybox;

import android.content.pm.PackageInstaller;
import android.test.AndroidTestCase;
import top.niunaijun.blackbox.entity.pm.InstallResult;

public final class InstallResultTest extends AndroidTestCase {
    public void testNullErrorKeepsExplicitFailureStatusAndPackage() {
        InstallResult result = new InstallResult();
        result.packageName = "fixture.test";
        result.status = PackageInstaller.STATUS_FAILURE_INVALID;
        assertSame(result, result.installError(null));
        assertFalse(result.success);
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, result.status);
        assertEquals("fixture.test", result.packageName);
        assertNotNull(result.msg);
        result.installError("other.fixture", null);
        assertEquals("other.fixture", result.packageName);
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, result.status);
        assertNotNull(result.msg);
        InstallResult generic = new InstallResult().installError(null);
        assertFalse(generic.success);
        assertEquals(PackageInstaller.STATUS_FAILURE, generic.status);
    }
}
