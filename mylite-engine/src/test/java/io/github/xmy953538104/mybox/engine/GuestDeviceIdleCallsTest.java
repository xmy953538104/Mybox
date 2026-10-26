package io.github.xmy953538104.mybox.engine;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import org.junit.Test;

public class GuestDeviceIdleCallsTest {
    public interface Idle {
        boolean isPowerSaveWhitelistApp(String name);
        boolean isPowerSaveWhitelistExceptIdleApp(String name);
        boolean isPowerSaveWhitelistApp(String name, int user);
        void addPowerSaveWhitelistApp(String name);
    }
    public static final class Physical implements Idle {
        String last;
        boolean hostAllowed;
        int calls;
        final SecurityException denial = new SecurityException("Actual platform mutation denied");
        public boolean isPowerSaveWhitelistApp(String name) {
            calls++; last = name; return name.equals("com.google.android.gms") || hostAllowed;
        }
        public boolean isPowerSaveWhitelistExceptIdleApp(String name) { return isPowerSaveWhitelistApp(name); }
        public boolean isPowerSaveWhitelistApp(String name, int user) { throw new AssertionError("Unknown query reached the delegate"); }
        public void addPowerSaveWhitelistApp(String name) { last = name; throw denial; }
    }
    private static GuestDeviceIdleCalls mapping(Physical physical) {
        return new GuestDeviceIdleCalls(physical, "com.google.android.gms", "physical.host", "org.fixture.other"::equals);
    }
    private static Method query(String name) throws Exception { return Idle.class.getMethod(name, String.class); }

    @Test public void physicalGoogleExemptionDoesNotBecomeTheHostExemption() throws Throwable {
        Physical physical = new Physical(); GuestDeviceIdleCalls calls = mapping(physical);
        assertTrue(physical.isPowerSaveWhitelistApp("com.google.android.gms"));
        for (String method : new String[]{"isPowerSaveWhitelistApp", "isPowerSaveWhitelistExceptIdleApp"}) {
            Object[] args = {"com.google.android.gms"};
            assertEquals(false, calls.invoke(null, query(method), args));
            assertEquals("physical.host", physical.last); assertEquals("com.google.android.gms", args[0]);
            physical.hostAllowed = true;
            assertEquals(true, calls.invoke(null, query(method), args));
            physical.hostAllowed = false;
        }
        assertEquals(false, calls.invoke(null, query("isPowerSaveWhitelistApp"), new Object[]{"physical.host"}));
    }

    @Test public void foreignVirtualTargetsAreNotResolvedAsPhysicalPackages() throws Throwable {
        Physical physical = new Physical(); GuestDeviceIdleCalls calls = mapping(physical);
        try { calls.invoke(null, query("isPowerSaveWhitelistApp"), new Object[]{"org.fixture.other"}); fail(); }
        catch (UnsupportedOperationException expected) { }
        assertEquals(0, physical.calls);
        assertEquals(false, calls.invoke(null, query("isPowerSaveWhitelistApp"), new Object[]{"physical.other"}));
        assertEquals("physical.other", physical.last);
    }

    @Test public void unknownShapesAndActualMutationErrorsRemainVisible() throws Throwable {
        Physical physical = new Physical(); GuestDeviceIdleCalls calls = mapping(physical);
        for (Object[] args : new Object[][]{null, new Object[]{null}, new Object[]{123}, new Object[]{"com.google.android.gms", 0}}) {
            try { calls.invoke(null, query("isPowerSaveWhitelistApp"), args); fail(); }
            catch (UnsupportedOperationException expected) { }
        }
        try { calls.invoke(null, Idle.class.getMethod("isPowerSaveWhitelistApp", String.class, int.class), new Object[]{"com.google.android.gms", 0}); fail(); }
        catch (UnsupportedOperationException expected) { }
        assertEquals(0, physical.calls);
        try { calls.invoke(null, Idle.class.getMethod("addPowerSaveWhitelistApp", String.class), new Object[]{"com.google.android.gms"}); fail(); }
        catch (SecurityException expected) { assertSame(physical.denial, expected); }
        assertEquals("com.google.android.gms", physical.last);
    }
}
