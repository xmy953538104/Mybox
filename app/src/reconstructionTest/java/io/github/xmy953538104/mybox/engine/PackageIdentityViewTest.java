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
    public void testSharedLibraryQueryKeepsPlatformMetadataAndFiltersPhysicalGoogleWithoutMutatingIt() throws Exception {
        android.content.Context host = getInstrumentation().getTargetContext();
        Class<?> libraryType = android.content.pm.SharedLibraryInfo.class;
        java.lang.reflect.Constructor<?> create = libraryType.getDeclaredConstructor(String.class, String.class,
                java.util.List.class, String.class, long.class, int.class, android.content.pm.VersionedPackage.class,
                java.util.List.class, java.util.List.class, boolean.class);
        create.setAccessible(true);
        java.util.List<Object> source = new java.util.ArrayList<>();
        Object platform = create.newInstance("/system/framework/framework.jar", null, Collections.emptyList(),
                "fixture.platform", -1L, android.content.pm.SharedLibraryInfo.TYPE_BUILTIN,
                new android.content.pm.VersionedPackage("android", 1L), null, null, false);
        source.add(platform);
        source.add(create.newInstance(null, "com.google.android.gms", Collections.emptyList(), "fixture.google", 1L,
                android.content.pm.SharedLibraryInfo.TYPE_DYNAMIC, new android.content.pm.VersionedPackage("com.google.android.gms", 1L), null, null, false));
        source.add(create.newInstance(null, "com.google.android.trichromelibrary", Collections.emptyList(),
                "com.google.android.trichromelibrary", 1L, android.content.pm.SharedLibraryInfo.TYPE_STATIC,
                new android.content.pm.VersionedPackage("com.google.android.trichromelibrary", 1L), null, null, false));
        assertNull(Engine.get().packages().get("com.google.android.trichromelibrary"));
        Class<?> slice = Class.forName("android.content.pm.ParceledListSlice");
        Object originalResult = slice.getConstructor(java.util.List.class).newInstance(source);
        Class<?> contract = Class.forName("android.content.pm.IPackageManager"); Method read = named(contract, "getSharedLibraries");
        Object flags = read.getParameterTypes()[1] == long.class ? (Object) Long.valueOf(0) : Integer.valueOf(0);
        Object original = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
            assertEquals(read, method); assertEquals(host.getPackageName(), args[0]);
            assertEquals(0L, ((Number) args[1]).longValue()); assertEquals(android.os.Process.myUid() / 100000, args[2]);
            return originalResult;
        });
        Object result = query(new GuestPackageQueries(Engine.get(), original), read,
                host.getPackageName(), flags, android.os.Process.myUid() / 100000);
        java.util.List<?> retained = (java.util.List<?>) slice.getMethod("getList").invoke(result);
        assertEquals(1, retained.size()); assertSame(platform, retained.get(0)); assertEquals(3, source.size());
    }

    public void testHostIdentityResolutionDoesNotScanUnrelatedVirtualRecords() throws Exception {
        android.content.Context real = getInstrumentation().getTargetContext();
        File owned = new File(real.getCacheDir(), "identity-provider-" + java.util.UUID.randomUUID());
        File records = new File(owned, "vlite/records"), broken = new File(records, "org.mybox.corrupt.properties");
        assertTrue(records.mkdirs());
        try {
            java.nio.file.Files.write(broken.toPath(), "format=invalid\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            android.content.Context isolated = new android.content.ContextWrapper(real) {
                @Override public File getFilesDir() { return owned; }
                @Override public android.content.Context getApplicationContext() { return this; }
            };
            java.lang.reflect.Constructor<Engine> constructor = Engine.class.getDeclaredConstructor(android.content.Context.class);
            constructor.setAccessible(true);
            Engine engine = constructor.newInstance(isolated);
            String authority = real.getPackageName() + ".identity";
            assertNull(VirtualProviders.find(engine, authority));
            for (String rejected : new String[]{authority + ".lookalike", "another.provider"}) {
                try { VirtualProviders.find(engine, rejected); fail("Ordinary virtual lookup skipped its corrupt record"); }
                catch (java.io.IOException expected) { }
            }
            try { VirtualProviders.find(engine, "0@" + authority); fail("User-qualified bridge accepted"); }
            catch (SecurityException expected) { }
            android.content.pm.ProviderInfo physical = real.getPackageManager().resolveContentProvider(authority, 0);
            assertNotNull("Actual physical identity Provider is absent", physical);
            assertEquals(real.getPackageName(), physical.packageName);
            Class<?> contract = Class.forName("android.content.pm.IPackageManager");
            Method resolve = named(contract, "resolveContentProvider");
            Object flags;
            if (resolve.getParameterTypes()[1] == long.class) flags = Long.valueOf(0);
            else flags = Integer.valueOf(0);
            Object[] supplied = new Object[]{authority, flags, 0};
            java.util.concurrent.atomic.AtomicInteger delegated = new java.util.concurrent.atomic.AtomicInteger();
            Object original = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
                assertEquals(resolve, method); assertEquals(supplied.length, args.length);
                for (int i = 0; i < supplied.length; i++) assertEquals(supplied[i], args[i]);
                delegated.incrementAndGet(); return physical;
            });
            assertSame(physical, query(new GuestPackageQueries(engine, original), resolve, supplied));
            assertEquals("Reserved Provider did not delegate exactly once", 1, delegated.get());
        } finally {
            java.nio.file.Files.deleteIfExists(broken.toPath());
            java.nio.file.Files.deleteIfExists(records.toPath());
            java.nio.file.Files.deleteIfExists(new File(owned, "vlite").toPath());
            java.nio.file.Files.deleteIfExists(owned.toPath());
        }
    }

    public void testDisabledAndDirectBootFlagsKeepLockedUsersProtected() {
        android.content.pm.ServiceInfo service = new android.content.pm.ServiceInfo();
        service.enabled = false;
        service.applicationInfo = new android.content.pm.ApplicationInfo(); service.applicationInfo.enabled = true;
        int disabled = android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
        assertFalse(GuestPackageQueries.matchesComponent(service, 0, true));
        assertTrue(GuestPackageQueries.matchesComponent(service, disabled | 0x10000000, true));
        assertFalse(GuestPackageQueries.matchesComponent(service, disabled | 0x10000000, false));
        service.directBootAware = true;
        assertTrue(GuestPackageQueries.matchesComponent(service, disabled, false));
        assertFalse(GuestPackageQueries.matchesComponent(service, disabled | android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE, true));
        service.enabled = true;
        assertTrue(GuestPackageQueries.matchesComponent(service, android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE, false));
    }
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
        android.content.pm.ActivityInfo platformActivity = new android.content.pm.ActivityInfo();
        platformActivity.packageName = "android";
        android.content.pm.ResolveInfo platformResolution = new android.content.pm.ResolveInfo();
        platformResolution.activityInfo = platformActivity;
        java.util.concurrent.atomic.AtomicInteger normalGrant = new java.util.concurrent.atomic.AtomicInteger(android.content.pm.PackageManager.PERMISSION_GRANTED);
        Object physical = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, args) -> {
                    if (method.getName().equals("asBinder")) return new Binder();
                    if (method.getName().equals("checkPermission") && "android.permission.FOREGROUND_SERVICE".equals(args[0])
                            && getInstrumentation().getTargetContext().getPackageName().equals(args[1])) return normalGrant.get();
                    if (method.getName().equals("isInstantApp") && "android".equals(args[0])) return true;
                    if (method.getName().equals("getPackageUid") && "android".equals(args[0])) return 1000;
                    if (method.getName().equals("getApplicationEnabledSetting") && "android".equals(args[0])) return 2;
                    if (method.getName().equals("getComponentEnabledSetting") && args[0] instanceof android.content.ComponentName
                            && "android".equals(((android.content.ComponentName) args[0]).getPackageName())) return 2;
                    if (method.getName().equals("getActivityInfo") && args[0] instanceof android.content.ComponentName
                            && "android".equals(((android.content.ComponentName) args[0]).getPackageName())) return platformActivity;
                    if (method.getName().equals("resolveIntent") && args[0] instanceof android.content.Intent
                            && "android".equals(((android.content.Intent) args[0]).getPackage())) return platformResolution;
                    throw new AssertionError("Virtual UID query reached physical PM: " + method.getName());
                });
        GuestPackageQueries queries = new GuestPackageQueries(engine, physical);
        Method installer = named(contract, "getInstallerPackageName");
        assertNull(query(queries, installer, guest));
        for (String google : new String[]{"com.google.android.gms", "com.google.android.gsf", "com.android.vending"}) {
            InstalledPackage entry = engine.packages().get(google);
            assertEquals(entry == null ? null : entry.installerPackageName, query(queries, installer, google));
        }
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
        Method permission = named(contract, "checkPermission");
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                query(queries, permission, guest + ".permission.BROADCAST", guest, 0));
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, query(queries, permission, "android.permission.FOREGROUND_SERVICE", guest, 0));
        normalGrant.set(android.content.pm.PackageManager.PERMISSION_DENIED);
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, query(queries, permission, "android.permission.FOREGROUND_SERVICE", guest, 0));
        for (String denied : new String[]{guest + ".permission.NORMAL_BROADCAST", "android.permission.NFC", "android.permission.READ_CONTACTS", "missing.permission"}) {
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, query(queries, permission, denied, guest, 0));
        }
        try { query(queries, permission, guest + ".permission.BROADCAST", guest, 1); fail("Permission query escaped virtual user zero"); }
        catch (SecurityException expected) { }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Method source = named(contract, "getInstallSourceInfo");
            for (String target : new String[]{guest, "io.github.xmy953538104.mybox.installtarget", "com.google.android.gms", "com.google.android.gsf", "com.android.vending"}) {
                Object[] input = source.getParameterCount() == 1 ? new Object[]{target} : new Object[]{target, 0};
                android.content.pm.InstallSourceInfo info = (android.content.pm.InstallSourceInfo) query(queries, source, input);
                InstalledPackage entry = engine.packages().get(target);
                if (entry == null) assertNull(info);
                else {
                    assertNotNull(info); assertEquals(entry.installerPackageName, info.getInstallingPackageName());
                    assertNull(info.getInitiatingPackageName()); assertNull(info.getInitiatingPackageSigningInfo());
                    assertNull(info.getOriginatingPackageName());
                }
            }
            if (source.getParameterCount() == 2) {
                try { query(queries, source, guest, 1); fail("Install source escaped virtual user zero"); }
                catch (SecurityException expected) { }
            }
        }
        for (String google : new String[]{"com.google.android.gms", "com.google.android.gsf", "com.android.vending"}) {
            assertEquals(false, query(queries, instant, google, 0));
        }
        // The physical stub's distinct result proves unknown packages still delegate.
        assertEquals(true, query(queries, instant, "android", 0));
        assertEquals(1000, ((Number) query(queries, uid, "android", flags, 0)).intValue());
        assertSame(platformActivity, query(queries, named(contract, "getActivityInfo"),
                new android.content.ComponentName("android", "android.app.Activity"), flags, 0));
        assertSame(platformResolution, query(queries, named(contract, "resolveIntent"),
                new android.content.Intent("fixture.PLATFORM").setPackage("android"), null, flags, 0));
        boolean otherUserDenied = false;
        try { query(queries, instant, guest, 1); }
        catch (SecurityException expected) { otherUserDenied = true; }
        assertTrue("An instant-app read escaped virtual user 0", otherUserDenied);
        Method componentState = named(contract, "getComponentEnabledSetting"), applicationState = named(contract, "getApplicationEnabledSetting");
        android.content.ComponentName declared = new android.content.ComponentName(guest, guest + ".FixtureActivity");
        assertEquals(0, query(queries, componentState, declared, 0));
        assertEquals(0, query(queries, applicationState, guest, 0));
        assertEquals(2, query(queries, componentState, new android.content.ComponentName("android", "android.app.Activity"), 0));
        assertEquals(2, query(queries, applicationState, "android", 0));
        try { query(queries, componentState, new android.content.ComponentName(guest, guest + ".Missing"), 0); fail("Unknown component got a default state"); }
        catch (IllegalArgumentException expected) { }
        try { query(queries, componentState, declared, 1); fail("Enabled-state query escaped user zero"); }
        catch (SecurityException expected) { }
    }

    public void testCrossUserMetadataFlagsNeverReachPhysicalPm() throws Exception {
        Engine engine = Engine.get();
        android.content.pm.PackageManager platform = getInstrumentation().getTargetContext().getPackageManager();
        try {
            platform.getPackageInfo("android", 0x402040);
            fail("This regression requires an ordinary host without physical cross-user privilege");
        } catch (SecurityException expected) { }
        Class<?> contract = Class.forName("android.content.pm.IPackageManager");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SecurityException physicalFailure = new SecurityException("Unrelated physical metadata denial");
        Object physical = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    assertEquals(0x2040L, ((Number) args[1]).longValue());
                    assertEquals(0, args[2]);
                    if ("fixture.denied".equals(args[0])) throw physicalFailure;
                    try {
                        if (method.getName().equals("getPackageInfo")) return platform.getPackageInfo((String) args[0], 0x2040);
                        if (method.getName().equals("getApplicationInfo")) return platform.getApplicationInfo((String) args[0], 0x2040);
                    } catch (android.content.pm.PackageManager.NameNotFoundException absent) { return null; }
                    throw new AssertionError("Unexpected physical metadata operation");
                });
        GuestPackageQueries queries = new GuestPackageQueries(engine, physical);
        for (String name : new String[]{"getPackageInfo", "getApplicationInfo"}) {
            Method method = named(contract, name);
            Object flags = method.getParameterTypes()[1] == long.class ? (Object) Long.valueOf(0x402040) : Integer.valueOf(0x402040);
            Object[] input = {"android", flags, 0};
            Object result = query(queries, method, input);
            assertNotNull(result);
            assertEquals("android", result instanceof android.content.pm.PackageInfo
                    ? ((android.content.pm.PackageInfo) result).packageName : ((android.content.pm.ApplicationInfo) result).packageName);
            assertSame(flags, input[1]);
            assertNull(query(queries, method, "fixture.not.installed", flags, 0));
            try { query(queries, method, "fixture.denied", flags, 0); fail("Unrelated denial was swallowed"); }
            catch (SecurityException expected) { assertSame(physicalFailure, expected); }
            int previous = calls.get();
            query(queries, method, "com.google.android.gms", flags, 0);
            assertEquals("Physical Google metadata escaped the container", previous, calls.get());
            try { query(queries, method, "android", flags, 1); fail("Physical query escaped virtual user zero"); }
            catch (SecurityException expected) { }
            assertEquals(previous, calls.get());
        }
        assertEquals(6, calls.get());
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
