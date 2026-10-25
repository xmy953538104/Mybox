package io.github.xmy953538104.mybox.engine;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ContentResolver;
import android.content.ContentProviderClient;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IInterface;
import android.os.IBinder;
import android.os.DeadObjectException;
import android.os.Process;
import android.test.InstrumentationTestCase;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;

/** Exercises the actual platform provider contract and attribution objects. */
public final class PhysicalProviderCallsTest extends InstrumentationTestCase {
    private static final String GUEST = "io.github.xmy953538104.mybox.fixture";

    public void testSettingsAcquiredBeforeGuestBindingIsAdaptedExactlyOnce() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        Context host = getInstrumentation().getTargetContext();
        SettingsSnapshot saved = new SettingsSnapshot();
        Object[][] captured = new Object[1][]; Binder binder = new Binder();
        Object raw = Proxy.getInvocationHandler(fake(host, binder, captured));
        Object original = RuntimeReflection.get(raw, "original");
        try {
            for (String scope : settingsScopes()) setProvider(settingsHolder(scope), original);
            PhysicalProviderCalls.installCachedSettings(host, GUEST);
            Object wrapped = RuntimeReflection.get(settingsHolder("Config"), "mContentProvider");
            PhysicalProviderCalls.installCachedSettings(host, GUEST);
            for (String scope : settingsScopes()) {
                assertSame(wrapped, RuntimeReflection.get(settingsHolder(scope), "mContentProvider"));
                assertSame(settingsHolder(scope), RuntimeReflection.field(Class.forName("android.provider.Settings$" + scope), "sProviderHolder").get(null));
            }
            call(wrapped, new AttributionSource.Builder(Process.myUid()).setPackageName(GUEST).build());
            assertEquals(host.getPackageName(), ((AttributionSource) captured[0][0]).getPackageName());
            assertSame(binder, ((IInterface) wrapped).asBinder());
        } finally { saved.restore(); }
    }

    public void testRealConfigColdAndWarmReadsObserveOsChangesThroughCachedRegistry() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        Context host = getInstrumentation().getTargetContext();
        String key = "mylite_cache_probe_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String namespace = "autofill";
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                "android.permission.READ_DEVICE_CONFIG", "android.permission.WRITE_DEVICE_CONFIG");
        try {
            assertTrue(setConfig(namespace, key, "first"));
            try (ContentProviderClient client = host.getContentResolver().acquireContentProviderClient("settings")) {
                assertNotNull(client);
                // This is a real OS provider acquired before the guest boundary is installed.
                Object physical = RuntimeReflection.get(client, "mContentProvider");
                SettingsSnapshot saved = new SettingsSnapshot();
                Object config = settingsHolder("Config");
                Object reacquired = null;
                try {
                    setProvider(config, physical);
                    clearConfigValues();
                    ContentResolver guest = guestResolver(host, GUEST);
                    try { configValues(guest, namespace, key); fail("Raw Config provider accepted an unowned guest package"); }
                    catch (SecurityException expected) {
                        assertTrue("The negative must be the real package/UID mismatch", expected.getMessage().contains(GUEST));
                        assertTrue(expected.getMessage().contains(Integer.toString(Process.myUid())));
                    }
                    PhysicalProviderCalls.installCachedSettings(host, GUEST);
                    assertRegistryAliases(saved, ((IInterface) physical).asBinder());
                    assertEquals("first", configValues(guest, namespace, key).get(key));
                    assertEquals("first", configValues(guest, namespace, key).get(key));
                    // Empty Config holder must reacquire through the already-patched ActivityThread map.
                    setProvider(config, null); clearConfigValues();
                    Map<String, String> cold;
                    try { cold = configValues(guest, namespace, key); }
                    finally { reacquired = RuntimeReflection.get(config, "mContentProvider"); }
                    assertEquals("first", cold.get(key));
                    assertSame(((IInterface) physical).asBinder(), ((IInterface) reacquired).asBinder());
                    assertTrue(setConfig(namespace, key, "second"));
                    long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                    String actual;
                    do {
                        actual = configValues(guest, namespace, key).get(key);
                        if ("second".equals(actual)) break;
                        Thread.sleep(20);
                    } while (android.os.SystemClock.elapsedRealtime() < deadline);
                    assertEquals("OS generation changes must invalidate the real framework cache", "second", actual);
                    assertEquals("second", getConfig(namespace, key));
                    clearConfigValues();
                    try { configValues(guestResolver(host, "foreign.package"), namespace, key); fail("Foreign attribution reached SettingsProvider"); }
                    catch (SecurityException expected) { assertTrue(expected.getMessage().contains("bound guest")); }
                } finally {
                    try {
                        if (reacquired != null) {
                            Object thread = RuntimeReflection.activityThread();
                            assertEquals(Boolean.valueOf(saved.references.containsKey(((IInterface) reacquired).asBinder())),
                                    RuntimeReflection.invoke(thread, RuntimeReflection.method(thread.getClass(),
                                            "releaseProvider", Class.forName("android.content.IContentProvider"), boolean.class), reacquired, true));
                        }
                    } finally {
                        try { clearConfigValues(); }
                        finally { saved.restore(); }
                    }
                }
            }
        } finally {
            try { assertTrue(deleteConfig(namespace, key)); }
            finally { getInstrumentation().getUiAutomation().dropShellPermissionIdentity(); }
        }
    }

    public void testRealProviderExceptionsAreNotSwallowed() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        Class<?> contract = Class.forName("android.content.IContentProvider");
        DeadObjectException rejection = new DeadObjectException(); Binder binder = new Binder();
        Object target = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
            if (method.getName().equals("asBinder")) return binder;
            throw rejection;
        });
        Object wrapped = PhysicalProviderCalls.wrap(getInstrumentation().getTargetContext(), GUEST, target);
        try { call(wrapped, new AttributionSource.Builder(Process.myUid()).setPackageName(GUEST).build()); fail("OS provider death was swallowed"); }
        catch (DeadObjectException expected) { assertSame(rejection, expected); }
        assertSame(binder, ((IInterface) wrapped).asBinder());
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

    private static boolean setConfig(String namespace, String key, String value) throws Exception {
        return (Boolean) RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName("android.provider.DeviceConfig"),
                "setProperty", String.class, String.class, String.class, boolean.class), namespace, key, value, false);
    }

    private static boolean deleteConfig(String namespace, String key) throws Exception {
        return (Boolean) RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName("android.provider.DeviceConfig"),
                "deleteProperty", String.class, String.class), namespace, key);
    }

    private static String getConfig(String namespace, String key) throws Exception {
        return (String) RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName("android.provider.DeviceConfig"),
                "getProperty", String.class, String.class), namespace, key);
    }

    private static String[] settingsScopes() {
        return Build.VERSION.SDK_INT >= 29 ? new String[]{"System", "Secure", "Global", "Config"}
                : new String[]{"System", "Secure", "Global"};
    }

    private static Object settingsHolder(String scope) throws Exception {
        Object cache = RuntimeReflection.field(Class.forName("android.provider.Settings$" + scope), "sNameValueCache").get(null);
        return RuntimeReflection.get(cache, "mProviderHolder");
    }

    private static void setProvider(Object holder, Object provider) throws Exception {
        synchronized (RuntimeReflection.get(holder, "mLock")) { RuntimeReflection.set(holder, "mContentProvider", provider); }
    }

    private static void clearConfigValues() throws Exception {
        Object cache = RuntimeReflection.field(Class.forName("android.provider.Settings$Config"), "sNameValueCache").get(null);
        RuntimeReflection.invoke(cache, RuntimeReflection.method(cache.getClass(), "clearGenerationTrackerForTest"));
    }

    private static ContentResolver guestResolver(Context host, String claimedPackage) throws Exception {
        Context isolated = host.createAttributionContext(null);
        ContentResolver resolver = isolated.getContentResolver();
        assertNotSame("The probe must not alter the host resolver context", host.getContentResolver(), resolver);
        ContextWrapper guest = new ContextWrapper(isolated) {
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(Process.myUid()).setPackageName(claimedPackage).build();
            }
        };
        RuntimeReflection.set(resolver, "mContext", guest);
        return resolver;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> configValues(ContentResolver resolver, String namespace, String key) throws Exception {
        Method get = RuntimeReflection.method(Class.forName("android.provider.Settings$Config"), "getStrings",
                ContentResolver.class, String.class, List.class);
        return (Map<String, String>) RuntimeReflection.invoke(null, get, resolver, namespace, Collections.singletonList(key));
    }

    private static void assertRegistryAliases(SettingsSnapshot saved, IBinder binder) throws Exception {
        int records = 0;
        synchronized (saved.records) {
            for (Object record : saved.records.values()) {
                Object provider = RuntimeReflection.get(record, "mProvider");
                if (!binder.equals(((IInterface) provider).asBinder())) continue;
                records++;
                assertTrue(Proxy.getInvocationHandler(provider) instanceof PhysicalProviderCalls);
                Object holder = RuntimeReflection.get(record, "mHolder");
                assertSame(provider, RuntimeReflection.get(holder, "provider"));
                Object reference = saved.references.get(binder);
                if (reference != null) {
                    assertSame(provider, RuntimeReflection.get(RuntimeReflection.get(reference, "holder"), "provider"));
                    for (String count : new String[]{"stableCount", "unstableCount", "removePending"})
                        assertEquals(saved.values.get(reference).get(count), RuntimeReflection.get(reference, count));
                }
            }
        }
        assertTrue("No real settings record was checked", records > 0);
    }

    /** Restore all actual provider aliases; tests must not bind the instrumentation process to a guest. */
    private static final class SettingsSnapshot {
        final Map<?, ?> records, references;
        final Map<Object, Map<String, Object>> values = new IdentityHashMap<>();
        SettingsSnapshot() throws Exception {
            Object thread = RuntimeReflection.activityThread();
            records = (Map<?, ?>) RuntimeReflection.get(thread, "mProviderMap");
            references = (Map<?, ?>) RuntimeReflection.get(thread, "mProviderRefCountMap");
            synchronized (records) {
                for (Object record : records.values()) {
                    capture(record, "mProvider");
                    Object holder = RuntimeReflection.get(record, "mHolder");
                    if (holder != null) capture(holder, "provider");
                }
                for (Object reference : references.values()) {
                    capture(RuntimeReflection.get(reference, "holder"), "provider");
                    for (String count : new String[]{"stableCount", "unstableCount", "removePending"}) capture(reference, count);
                }
            }
            for (String scope : settingsScopes()) {
                Object holder = settingsHolder(scope);
                synchronized (RuntimeReflection.get(holder, "mLock")) { capture(holder, "mContentProvider"); }
            }
        }
        private void capture(Object target, String field) throws Exception {
            Map<String, Object> fields = values.get(target);
            if (fields == null) { fields = new LinkedHashMap<>(); values.put(target, fields); }
            fields.put(field, RuntimeReflection.get(target, field));
        }
        void restore() throws Exception {
            synchronized (records) {
                for (Map.Entry<Object, Map<String, Object>> entry : values.entrySet())
                    for (Map.Entry<String, Object> field : entry.getValue().entrySet())
                        if (field.getKey().equals("mProvider") || field.getKey().equals("provider"))
                            RuntimeReflection.set(entry.getKey(), field.getKey(), field.getValue());
            }
            // Never invert Settings holder.mLock -> ActivityThread.mProviderMap lock ordering.
            for (Map.Entry<Object, Map<String, Object>> entry : values.entrySet())
                if (entry.getValue().containsKey("mContentProvider"))
                    setProvider(entry.getKey(), entry.getValue().get("mContentProvider"));
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
