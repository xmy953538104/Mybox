package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.test.InstrumentationTestCase;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class TelephonyRegistryBoundaryTest extends InstrumentationTestCase {
    public static final class WrongShape {
        public void addOnSubscriptionsChangedListener(String pkg, Object callback) { }
        public void removeOnSubscriptionsChangedListener(String pkg, String tag, Object callback) { }
        public void listenForSubscriber(int id, String pkg) { }
    }

    public void testExactAttributionCallbackIdentityDenialAndActualFailure() throws Throwable {
        Class<?> contract = Class.forName("com.android.internal.telephony.ITelephonyRegistry");
        Class<?> callbackType = Class.forName("com.android.internal.telephony.IOnSubscriptionsChangedListener");
        Binder backendBinder = new Binder(), callbackBinder = new Binder();
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (p, m, a) -> m.getName().equals("asBinder") ? callbackBinder : null);
        Object[][] received = new Object[1][]; int[] calls = {0}; Throwable[] failure = {null};
        Object original = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (p, m, a) -> {
            if (m.getName().equals("asBinder")) return backendBinder;
            calls[0]++; received[0] = a;
            if (failure[0] != null) throw failure[0];
            return null;
        });
        IInterface proxy = GuestTelephonyRegistryCalls.wrap(original, "host", "guest", "host-feature");
        InvocationHandler boundary = Proxy.getInvocationHandler(proxy);
        assertNotSame(backendBinder, proxy.asBinder());
        assertSame(proxy, GuestTelephonyRegistryCalls.wrap(proxy, "host", "guest", "host-feature"));
        try { GuestTelephonyRegistryCalls.wrap(proxy, "host", "other", "host-feature"); fail("Rebound registry"); }
        catch (SecurityException expected) { }
        for (Method method : contract.getMethods()) {
            String name = method.getName();
            if (!name.equals("addOnSubscriptionsChangedListener") && !name.equals("addOnOpportunisticSubscriptionsChangedListener")
                    && !name.equals("removeOnSubscriptionsChangedListener")) continue;
            Object[] args = method.getParameterCount() == 3 ? new Object[]{"guest", "guest-feature", callback} : new Object[]{"guest", callback};
            assertNull(boundary.invoke(proxy, method, args));
            assertEquals("host", received[0][0]); assertEquals("guest", args[0]);
            assertSame(callback, received[0][args.length - 1]);
            if (args.length == 3) { assertEquals("host-feature", received[0][1]); assertEquals("guest-feature", args[1]); }
            args[0] = "host"; boundary.invoke(proxy, method, args);
            int before = calls[0]; args[0] = "foreign";
            try { boundary.invoke(proxy, method, args); fail("Foreign registry attribution"); } catch (SecurityException expected) { }
            assertEquals(before, calls[0]);
            args[0] = null;
            try { boundary.invoke(proxy, method, args); fail("Missing registry attribution"); } catch (SecurityException expected) { }
            assertEquals(before, calls[0]);
            args[0] = "guest"; DeadObjectException dead = new DeadObjectException(); failure[0] = dead;
            try { boundary.invoke(proxy, method, args); fail("Lost backend death"); } catch (DeadObjectException expected) { assertSame(dead, expected); }
            SecurityException denied = new SecurityException("Actual OS rejection"); failure[0] = denied;
            try { boundary.invoke(proxy, method, args); fail("Lost backend denial"); } catch (SecurityException expected) { assertSame(denied, expected); }
            failure[0] = null;
        }
        int before = calls[0];
        for (Method method : WrongShape.class.getDeclaredMethods()) {
            Object[] args = method.getName().equals("listenForSubscriber") ? new Object[]{0, "guest"}
                    : method.getParameterCount() == 3 ? new Object[]{"guest", "tag", callback} : new Object[]{"guest", callback};
            try { boundary.invoke(proxy, method, args); fail("Unknown guest telephony shape"); }
            catch (UnsupportedOperationException expected) { }
            if (method.getName().equals("listenForSubscriber")) {
                args[1] = "host";
                try { boundary.invoke(proxy, method, args); fail("Host attribution borrowed radio permission"); }
                catch (UnsupportedOperationException expected) { }
            }
        }
        assertEquals(before, calls[0]);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            try { proxy.asBinder().transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0); reply.readException(); fail("Raw registry transaction reached OS"); }
            catch (SecurityException expected) { }
        } finally { reply.recycle(); data.recycle(); }
        assertEquals(before, calls[0]);
    }
}
