package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PackageUseHintTest {
    @Test public void physicalDependencyAndReasonRemainAvailableToActualPlatformPolicy() {
        for (String dependency : new String[]{"com.google.android.webview", "com.android.settings", "host.app"}) {
            Object[] input = {dependency, 6};
            assertSame(input, GuestPackageQueries.packageUseArguments(input, "host.app", false));
            assertEquals(dependency, input[0]); assertEquals(6, input[1]);
        }
    }
    @Test public void onlyKnownVirtualTargetsMapWithoutMutatingCallerArguments() {
        Object[] input = {"virtual.app", 0};
        Object[] mapped = GuestPackageQueries.packageUseArguments(input, "host.app", true);
        assertNotSame(input, mapped); assertEquals("host.app", mapped[0]); assertEquals(0, mapped[1]);
        assertEquals("virtual.app", input[0]);
    }
    @Test public void unknownShapesDoNotGetReinterpretedAsAnAttributionContract() {
        for (Object[] input : new Object[][]{null, {}, {"virtual.app"}, {"virtual.app", 0L}, {"virtual.app", 0, 1}, {null, 0}}) {
            try { GuestPackageQueries.packageUseArguments(input, "host.app", true); fail(); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
