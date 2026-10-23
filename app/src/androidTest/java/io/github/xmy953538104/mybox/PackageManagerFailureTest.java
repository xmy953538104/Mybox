// SPDX-License-Identifier: Apache-2.0
package io.github.xmy953538104.mybox;

import android.os.DeadObjectException;
import android.test.AndroidTestCase;

import java.lang.reflect.Proxy;
import java.util.Collections;

import top.niunaijun.blackbox.core.system.pm.IBPackageManagerService;
import top.niunaijun.blackbox.fake.frameworks.BPackageManager;

public final class PackageManagerFailureTest extends AndroidTestCase {
    public void testTransportFailureIsNotPackageAbsenceOrSuccessfulMutation() {
        DeadObjectException failure = new DeadObjectException();
        BPackageManager manager = manager((proxy, method, args) -> { throw failure; });
        assertRpcFailure(failure, () -> manager.getApplicationInfo("fixture.test", 0, 0));
        assertRpcFailure(failure, () -> manager.resolveContentProvider("fixture.test.provider", 0, 0));
        assertRpcFailure(failure, () -> manager.getInstalledApplications(0, 0));
        assertRpcFailure(failure, () -> manager.getInstalledPackages(0, 0));
        assertRpcFailure(failure, () -> manager.getInstalledPackagesAsUser(0));
        assertRpcFailure(failure, () -> manager.isInstalled("fixture.test", 0));
        assertRpcFailure(failure, () -> manager.getPackagesForUid(10001));
        assertRpcFailure(failure, () -> manager.clearPackage("fixture.test", 0));
        assertRpcFailure(failure, () -> manager.stopPackage("fixture.test", 0));
        assertRpcFailure(failure, () -> manager.uninstallPackageAsUser("fixture.test", 0));
        assertRpcFailure(failure, () -> manager.uninstallPackage("fixture.test"));
        assertFalse("A failed trust check must remain denied", manager.isTrustedMicroG("fixture.test", 0));
    }

    public void testSuccessfulAbsentResponsesKeepTheirNormalMeaning() {
        BPackageManager manager = manager((proxy, method, args) -> {
            if (method.getName().equals("isInstalled")) return false;
            if (method.getName().equals("getInstalledApplications")) return Collections.emptyList();
            return null;
        });
        assertNull(manager.getApplicationInfo("absent.test", 0, 0));
        assertNull(manager.resolveContentProvider("absent.test.provider", 0, 0));
        assertFalse(manager.isInstalled("absent.test", 0));
        assertTrue(manager.getInstalledApplications(0, 0).isEmpty());
    }

    private static BPackageManager manager(java.lang.reflect.InvocationHandler handler) {
        IBPackageManagerService service = (IBPackageManagerService) Proxy.newProxyInstance(
                IBPackageManagerService.class.getClassLoader(), new Class<?>[]{IBPackageManagerService.class}, handler);
        return new BPackageManager() {
            @Override public IBPackageManagerService getService() { return service; }
        };
    }

    private static void assertRpcFailure(DeadObjectException expected, Runnable operation) {
        try { operation.run(); fail("A failed PM RPC returned an absence/success result"); }
        catch (IllegalStateException failure) { assertSame(expected, failure.getCause()); }
    }
}
