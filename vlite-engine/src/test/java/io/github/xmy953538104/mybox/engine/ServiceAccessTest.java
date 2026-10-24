package io.github.xmy953538104.mybox.engine;

import android.content.pm.PermissionInfo;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public final class ServiceAccessTest {
    @Test public void signaturePermissionDoesNotInheritHostPrivilegeOrCompatibilityCertificate() {
        assertTrue(ServiceAccess.permissionGranted(PermissionInfo.PROTECTION_SIGNATURE, true,
                Collections.singletonList("actual-owner-cert"), Collections.singletonList("actual-owner-cert")));
        assertFalse(ServiceAccess.permissionGranted(PermissionInfo.PROTECTION_SIGNATURE | 0x10, true,
                Collections.singletonList("actual-owner-cert"), Collections.singletonList("displayed-google-cert")));
        assertFalse(ServiceAccess.permissionGranted(PermissionInfo.PROTECTION_SIGNATURE, false,
                Collections.singletonList("actual-owner-cert"), Collections.singletonList("actual-owner-cert")));
    }

    @Test public void multipleSignersMustMatchAsACompleteSet() {
        assertTrue(ServiceAccess.permissionGranted(PermissionInfo.PROTECTION_SIGNATURE, true,
                Arrays.asList("one", "two"), Arrays.asList("two", "one")));
        assertFalse(ServiceAccess.permissionGranted(PermissionInfo.PROTECTION_SIGNATURE, true,
                Arrays.asList("one", "two"), Collections.singletonList("one")));
        assertFalse(ServiceAccess.permissionGranted(PermissionInfo.PROTECTION_SIGNATURE, true,
                Collections.emptyList(), Collections.emptyList()));
    }

    @Test public void onlyDeclaredNormalPermissionsReceiveAnAutomaticGrant() {
        assertTrue(ServiceAccess.permissionGranted(PermissionInfo.PROTECTION_NORMAL, true, null, null));
        assertFalse(ServiceAccess.permissionGranted(PermissionInfo.PROTECTION_NORMAL, false, null, null));
        assertFalse(ServiceAccess.permissionGranted(PermissionInfo.PROTECTION_DANGEROUS, true, null, null));
        assertFalse(ServiceAccess.permissionGranted(PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM, true,
                Collections.singletonList("same"), Collections.singletonList("same")));
    }
}
