package io.github.xmy953538104.mybox.engine;

import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IInterface;
import android.os.Process;
import android.test.InstrumentationTestCase;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Exercises the actual platform provider contract and attribution objects. */
public final class PhysicalProviderCallsTest extends InstrumentationTestCase {
    private static final String GUEST = "io.github.xmy953538104.mybox.fixture";

    public void testSettingsAcquiredBeforeGuestBindingIsAdaptedExactlyOnce() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        Context host = getInstrumentation().getTargetContext();
        Object cache = RuntimeReflection.field(Class.forName("android.provider.Settings$Global"), "sNameValueCache").get(null);
        Object holder = RuntimeReflection.get(cache, "mProviderHolder");
        java.util.Map<Object, Object> saved = new java.util.IdentityHashMap<>();
        for (String scope : new String[]{"System", "Secure", "Global"}) {
            Object value = RuntimeReflection.field(Class.forName("android.provider.Settings$" + scope), "sNameValueCache").get(null);
            Object current = RuntimeReflection.get(value, "mProviderHolder");
            saved.put(current, RuntimeReflection.get(current, "mContentProvider"));
        }
        Object[][] captured = new Object[1][]; Binder binder = new Binder();
        Object raw = Proxy.getInvocationHandler(fake(host, binder, captured));
        Object original = RuntimeReflection.get(raw, "original");
        try {
            RuntimeReflection.set(holder, "mContentProvider", original);
            PhysicalProviderCalls.installCachedSettings(host, GUEST);
            Object wrapped = RuntimeReflection.get(holder, "mContentProvider");
            PhysicalProviderCalls.installCachedSettings(host, GUEST);
            assertSame(wrapped, RuntimeReflection.get(holder, "mContentProvider"));
            call(wrapped, new AttributionSource.Builder(Process.myUid()).setPackageName(GUEST).build());
            assertEquals(host.getPackageName(), ((AttributionSource) captured[0][0]).getPackageName());
            assertSame(binder, ((IInterface) wrapped).asBinder());
        } finally { for (java.util.Map.Entry<Object, Object> entry : saved.entrySet()) RuntimeReflection.set(entry.getKey(), "mContentProvider", entry.getValue()); }
    }

    public void testPhysicalAttributionIsCopiedAndBinderIsPreserved() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        Context host = getInstrumentation().getTargetContext();
        AttributionSource next = new AttributionSource.Builder(Process.myUid())
                .setPackageName(host.getPackageName()).build();
        AttributionSource.Builder guestBuilder = new AttributionSource.Builder(Process.myUid())
                .setPackageName(GUEST).setAttributionTag("guest-tag").setNext(next);
        if (Build.VERSION.SDK_INT >= 33) guestBuilder.setPid(Process.myPid());
        AttributionSource guest = guestBuilder.build();
        Binder attributionToken = new Binder();
        Object sourceState = RuntimeReflection.get(guest, "mAttributionSourceState");
        RuntimeReflection.set(sourceState, "token", attributionToken);
        RuntimeReflection.set(sourceState, "renouncedPermissions", new String[]{"android.permission.CAMERA"});
        Binder realBinder = new Binder();
        Object[][] captured = new Object[1][];
        Object wrapped = fake(host, realBinder, captured);
        call(wrapped, guest);
        AttributionSource actual = (AttributionSource) captured[0][0];
        assertEquals(host.getPackageName(), actual.getPackageName());
        assertEquals(Process.myUid(), actual.getUid());
        assertEquals(host.getAttributionTag(), actual.getAttributionTag());
        assertEquals(next, actual.getNext());
        Object actualState = RuntimeReflection.get(actual, "mAttributionSourceState");
        assertSame(attributionToken, RuntimeReflection.get(actualState, "token"));
        if (Build.VERSION.SDK_INT >= 33) assertEquals(Process.myPid(), RuntimeReflection.get(actualState, "pid"));
        assertEquals("android.permission.CAMERA", ((String[])
                RuntimeReflection.get(actualState, "renouncedPermissions"))[0]);
        assertEquals(GUEST, guest.getPackageName());
        assertEquals("guest-tag", guest.getAttributionTag());
        assertSame(realBinder, ((IInterface) wrapped).asBinder());
    }

    public void testForeignAttributionCannotBecomeHostIdentity() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        Context host = getInstrumentation().getTargetContext();
        Object[][] captured = new Object[1][];
        Object wrapped = fake(host, new Binder(), captured);
        for (AttributionSource source : new AttributionSource[]{
                new AttributionSource.Builder(Process.myUid() + 1).setPackageName(GUEST).build(),
                new AttributionSource.Builder(Process.myUid()).setPackageName("com.foreign.app").build()}) {
            try {
                call(wrapped, source);
                fail("Foreign attribution was accepted");
            } catch (SecurityException expected) {
                assertNull(captured[0]);
            }
        }
    }

    private static Object fake(Context host, Binder binder, Object[][] captured) throws Exception {
        Class<?> contract = Class.forName("android.content.IContentProvider");
        Object original = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, args) -> {
                    if (method.getName().equals("asBinder")) return binder;
                    if (method.getName().equals("call")) {
                        captured[0] = args;
                        return new Bundle();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return PhysicalProviderCalls.wrap(host, GUEST, original);
    }

    private static void call(Object provider, AttributionSource source) throws Exception {
        Class<?> contract = Class.forName("android.content.IContentProvider");
        Method method = contract.getMethod("call", AttributionSource.class, String.class,
                String.class, String.class, Bundle.class);
        try { method.invoke(provider, source, "settings", "test", null, Bundle.EMPTY); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure;
        }
    }
}
