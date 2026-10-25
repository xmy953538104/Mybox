package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.os.IBinder;
import android.test.InstrumentationTestCase;
import java.lang.reflect.Method;

public final class AutofillBoundaryTest extends InstrumentationTestCase {
    public static final class Backend {
        Object[] received; int calls;
        public void startSession(IBinder token, IBinder callback, Object id, Object bounds, Object value,
                int user, boolean hasCallback, int flags, ComponentName component, boolean compat, Object receiver) {
            calls++; received = new Object[]{token, callback, id, bounds, value, user, hasCallback, flags, component, compat, receiver};
        }
    }
    public void testOnlyVerifiedLiveTokenCanMapComponentAndRealCallbackIsPreserved() throws Throwable {
        Backend backend = new Backend();
        IBinder token = new android.os.Binder(), callback = new android.os.Binder();
        ComponentName guest = new ComponentName("guest", "guest.Login"), physical = new ComponentName("host", "host.Stub");
        GuestAutofillCalls boundary = new GuestAutofillCalls(backend, "guest", physical, (actual, claimed) -> {
            if (actual != token || !guest.equals(claimed)) throw new SecurityException("Unowned token");
        });
        Method method = Backend.class.getMethod("startSession", IBinder.class, IBinder.class, Object.class, Object.class, Object.class,
                int.class, boolean.class, int.class, ComponentName.class, boolean.class, Object.class);
        Object receiver = new Object(); Object[] args = {token, callback, new Object(), null, null, 0, true, 9, guest, false, receiver};
        boundary.invoke(null, method, args);
        assertEquals(1, backend.calls); assertEquals(physical, backend.received[8]); assertEquals(guest, args[8]);
        for (int i = 0; i < args.length; i++) if (i != 8) assertSame(args[i], backend.received[i]);
        args[0] = new android.os.Binder();
        try { boundary.invoke(null, method, args); fail("Unowned Activity token reached autofill"); } catch (SecurityException expected) { }
        args[0] = token; args[8] = new ComponentName("foreign", "foreign.Login");
        try { boundary.invoke(null, method, args); fail("Foreign component reached autofill"); } catch (SecurityException expected) { }
        args[8] = guest; args[5] = 1;
        try { boundary.invoke(null, method, args); fail("Foreign user reached autofill"); } catch (SecurityException expected) { }
        assertEquals(1, backend.calls);
    }
}
