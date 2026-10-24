package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.test.InstrumentationTestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

public final class PackageIdentityViewTest extends InstrumentationTestCase {
    public void testUidRoundTripDoesNotFallBackToPhysicalAppIdentity() throws Exception {
        Engine engine = Engine.get();
        String guest = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(guest);
        if (installed == null) {
            File fixture = new File(getInstrumentation().getTargetContext().getCacheDir(), "uid-fixture.apk");
            try (InputStream source = getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                 FileOutputStream destination = new FileOutputStream(fixture)) {
                byte[] buffer = new byte[16384];
                for (int count; (count = source.read(buffer)) != -1;) destination.write(buffer, 0, count);
            }
            installed = engine.install(Collections.singletonList(fixture));
        }
        Class<?> contract = Class.forName("android.content.pm.IPackageManager");
        Object physical = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, args) -> {
                    if (method.getName().equals("asBinder")) return new Binder();
                    if (method.getName().equals("isInstantApp") && "android".equals(args[0])) return true;
                    throw new AssertionError("Virtual UID query reached physical PM: " + method.getName());
                });
        GuestPackageQueries queries = new GuestPackageQueries(engine, physical);
        Method uid = named(contract, "getPackageUid");
        Object flags = uid.getParameterTypes()[1] == long.class ? (Object) Long.valueOf(0) : Integer.valueOf(0);
        assertEquals(installed.appId, ((Number) query(queries, uid, guest, flags, 0)).intValue());
        assertEquals(-1, ((Number) query(queries, uid, guest, flags, 1)).intValue());
        String[] names = (String[]) query(queries, named(contract, "getPackagesForUid"), installed.appId);
        assertEquals(1, names.length);
        assertEquals(guest, names[0]);
        assertEquals(guest, query(queries, named(contract, "getNameForUid"), installed.appId));
        assertNull(query(queries, named(contract, "getPackagesForUid"), 99999));
        assertNull(query(queries, named(contract, "getNameForUid"), 99999));
        Method instant = named(contract, "isInstantApp");
        assertEquals(false, query(queries, instant, guest, 0));
        for (String google : new String[]{"com.google.android.gms", "com.google.android.gsf", "com.android.vending"}) {
            assertEquals(false, query(queries, instant, google, 0));
        }
        // The physical stub's distinct result proves unknown packages still delegate.
        assertEquals(true, query(queries, instant, "android", 0));
        boolean otherUserDenied = false;
        try { query(queries, instant, guest, 1); }
        catch (SecurityException expected) { otherUserDenied = true; }
        assertTrue("An instant-app read escaped virtual user 0", otherUserDenied);
    }

    private static Method named(Class<?> contract, String name) throws NoSuchMethodException {
        for (Method method : contract.getMethods()) if (method.getName().equals(name)) return method;
        throw new NoSuchMethodException(name);
    }

    private static Object query(GuestPackageQueries queries, Method method, Object... args) throws Exception {
        try { return queries.invoke(null, method, args); }
        catch (Exception error) { throw error; }
        catch (Error error) { throw error; }
        catch (Throwable error) { throw new AssertionError(error); }
    }
}
