package io.github.xmy953538104.mybox.engine;

import android.test.InstrumentationTestCase;
import java.lang.reflect.Method;

public final class BiometricBoundaryTest extends InstrumentationTestCase {
    public static final class Backend {
        int calls; String owner; int user, mask;
        public int canAuthenticate(String owner, int user, int mask) {
            calls++; this.owner=owner; this.user=user; this.mask=mask;
            if (mask == -1) throw new SecurityException("Actual backend denial");
            return 12;
        }
        public CharSequence getButtonLabel(int user, String owner, int mask) {
            canAuthenticate(owner,user,mask); return "Actual label";
        }
        public long[] getAuthenticatorIds(int user) { calls++; this.user=user; return new long[]{73}; }
        public void authenticate() { calls++; }
    }
    public void testQueriesPreserveBackendResultsMasksAndCallerArguments() throws Throwable {
        Backend backend = new Backend(); GuestBiometricCalls calls = new GuestBiometricCalls(backend,"guest","host",10,true);
        Method method = Backend.class.getMethod("canAuthenticate",String.class,int.class,int.class);
        Object[] original = {"guest",0,255};
        assertEquals(12,calls.invoke(null,method,original));
        assertEquals("host",backend.owner); assertEquals(10,backend.user); assertEquals(255,backend.mask);
        assertEquals("guest",original[0]); assertEquals(0,original[1]);
        assertEquals("Actual label",calls.invoke(null,Backend.class.getMethod("getButtonLabel",int.class,String.class,int.class),new Object[]{0,"guest",32768}));
        assertEquals(32768,backend.mask);
        try { calls.invoke(null,method,new Object[]{"guest",0,-1}); fail("Actual OS denial was swallowed"); }
        catch (SecurityException expected) { assertEquals("Actual backend denial",expected.getMessage()); }
        GuestBiometricCalls noPermission = new GuestBiometricCalls(backend,"guest","host",10,false);
        long[] ids = (long[]) noPermission.invoke(null,Backend.class.getMethod("getAuthenticatorIds",int.class),new Object[]{0});
        assertEquals(73L,ids[0]); assertEquals(10,backend.user);
    }
    public void testUnknownIdentityUserShapeAndUndeclaredPermissionDoNotReachBackend() throws Throwable {
        Backend backend = new Backend(); GuestBiometricCalls calls = new GuestBiometricCalls(backend,"guest","host",0,true);
        Method method = Backend.class.getMethod("canAuthenticate",String.class,int.class,int.class);
        for (Object[] args : new Object[][]{{"foreign",0,255},{"guest",1,255}}) {
            try { calls.invoke(null,method,args); fail("Foreign identity was accepted"); } catch (SecurityException expected) { }
        }
        for (Object[] args : new Object[][]{null,{"guest",0L,255},{"guest",0,255L},{"guest",0}}) {
            try { calls.invoke(null,method,args); fail("Unknown signature was accepted"); } catch (UnsupportedOperationException expected) { }
        }
        for (String owner : new String[]{"guest","host"}) {
            try { new GuestBiometricCalls(backend,"guest","host",0,false).invoke(null,method,new Object[]{owner,0,255}); fail("Undeclared permission was accepted"); }
            catch (SecurityException expected) { }
        }
        try { calls.invoke(null,Backend.class.getMethod("authenticate"),new Object[0]); fail("Unsupported authentication executed"); }
        catch (UnsupportedOperationException expected) { }
        assertEquals(0,backend.calls);
    }
}
