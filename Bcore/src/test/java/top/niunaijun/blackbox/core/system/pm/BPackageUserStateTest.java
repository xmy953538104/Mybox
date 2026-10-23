package top.niunaijun.blackbox.core.system.pm;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BPackageUserStateTest {
    @Test public void componentOverridesRespectManifestAndCopyIndependence() {
        BPackageUserState original = BPackageUserState.create();
        assertTrue(original.componentEnabled("service", true));
        assertFalse(original.componentEnabled("service", false));
        original.componentStates.put("service", 1);
        assertTrue(original.componentEnabled("service", false));
        BPackageUserState copy = new BPackageUserState(original);
        copy.componentStates.put("service", 2);
        assertTrue(original.componentEnabled("service", false));
        assertFalse(copy.componentEnabled("service", true));
        copy.componentStates.remove("service");
        assertEquals(0, copy.componentState("service"));
        assertTrue(copy.componentEnabled("service", true));
    }
}
