package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.test.InstrumentationTestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

public final class PackageIntentRoutingTest extends InstrumentationTestCase {
    public void testServiceResolutionUsesTheVirtualManifestAndNeverPhysicalPm() throws Exception {
        Engine engine = Engine.get();
        String guest = "io.github.xmy953538104.mybox.fixture";
        if (engine.packages().get(guest) == null) {
            File fixture = new File(getInstrumentation().getTargetContext().getCacheDir(), "resolve-fixture.apk");
            try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                 FileOutputStream output = new FileOutputStream(fixture)) {
                byte[] buffer = new byte[16384];
                for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            }
            engine.install(Collections.singletonList(fixture));
        }
        Class<?> contract = Class.forName("android.content.pm.IPackageManager");
        Object physical = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, args) -> { throw new AssertionError("Virtual resolution reached physical PM"); });
        GuestPackageQueries queries = new GuestPackageQueries(engine, physical);
        Method resolve = null;
        for (Method method : contract.getMethods()) if (method.getName().equals("resolveService")) resolve = method;
        assertNotNull(resolve);
        Object flags = resolve.getParameterTypes()[2] == long.class ? (Object) Long.valueOf(0) : Integer.valueOf(0);
        try {
            ResolveInfo found = (ResolveInfo) queries.invoke(null, resolve,
                    new Object[]{new Intent(guest + ".REMOTE").setPackage(guest), null, flags, 0});
            assertNotNull(found);
            assertEquals(guest + ".RemoteService", found.serviceInfo.name);
            assertEquals(7, found.priority);
            assertNull(queries.invoke(null, resolve,
                    new Object[]{new Intent(guest + ".UNKNOWN").setPackage(guest), null, flags, 0}));
            assertNull(queries.invoke(null, resolve,
                    new Object[]{new Intent(guest + ".REMOTE").setPackage(guest).addCategory("not.declared"), null, flags, 0}));
        } catch (Exception failure) { throw failure; }
        catch (Error failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError(failure); }
    }
}
