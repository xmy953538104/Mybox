package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.test.InstrumentationTestCase;
import java.lang.reflect.Method;

public final class DevicePolicyBoundaryTest extends InstrumentationTestCase {
    public static final class Backend {
        int queries, mutations;
        public ComponentName getDeviceOwnerComponent(boolean callingUserOnly) { queries++; throw new SecurityException("Privileged physical query"); }
        public void setCameraDisabled(ComponentName admin, boolean disabled) { mutations++; throw new SecurityException("Physical admin permission"); }
    }
    public void testVirtualOwnerReadDoesNotGrantPhysicalAdministration() throws Throwable {
        Backend backend = new Backend(); GuestDevicePolicyCalls calls = new GuestDevicePolicyCalls(backend);
        Method owner = Backend.class.getMethod("getDeviceOwnerComponent", boolean.class);
        assertNull(calls.invoke(null, owner, new Object[]{true})); assertNull(calls.invoke(null, owner, new Object[]{false}));
        assertEquals(0, backend.queries);
        try { calls.invoke(null, owner, new Object[]{0}); fail("Unknown device owner shape accepted"); }
        catch (UnsupportedOperationException expected) { }
        try { calls.invoke(null, Backend.class.getMethod("setCameraDisabled", ComponentName.class, boolean.class), new Object[]{new ComponentName("guest", "guest.Admin"), true}); fail("Real administrator denial was suppressed"); }
        catch (SecurityException expected) { }
        assertEquals(1, backend.mutations);
    }
}
