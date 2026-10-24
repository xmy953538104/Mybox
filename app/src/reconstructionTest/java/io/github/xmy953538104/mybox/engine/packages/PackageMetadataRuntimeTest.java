package io.github.xmy953538104.mybox.engine.packages;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.test.InstrumentationTestCase;
import android.test.mock.MockPackageManager;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Actual Android archive parsing and Parcel isolation; the observing PM delegates every parse. */
public final class PackageMetadataRuntimeTest extends InstrumentationTestCase {
    public void testMetadataReusePreservesFlagsCopiesAndCommittedGenerations() throws Exception {
        Context host = getInstrumentation().getTargetContext();
        File directory = Files.createTempDirectory(host.getCacheDir().toPath(), "mylite-metadata-").toFile();
        try {
            CountingManager manager = new CountingManager(host.getPackageManager());
            Context observed = new ContextWrapper(host) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return directory; }
                @Override public PackageManager getPackageManager() { return manager; }
            };
            PackageStore store = new PackageStore(observed);
            File apk = new File(directory, "auth.apk");
            copyAsset("account-ui-authenticator-v1.apk", apk);
            InstalledPackage installed = store.install(Collections.singletonList(apk));
            String name = installed.packageName;
            int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                    | PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNATURES;

            int before = manager.parses.get();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] readers = new Thread[3];
            for (int i = 0; i < readers.length; i++) {
                readers[i] = new Thread(() -> {
                    try {
                        start.await();
                        for (int query = 0; query < 5; query++) assertEquals(name, store.getPackageInfo(name, flags).packageName);
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                });
                readers[i].start();
            }
            start.countDown();
            for (Thread reader : readers) {
                reader.join(15000);
                assertFalse("Metadata query thread did not finish", reader.isAlive());
            }
            if (failure.get() != null) throw new AssertionError("Concurrent real metadata query failed", failure.get());
            assertEquals("Concurrent reads reparsed an unchanged archive", before + 1, manager.parses.get());

            PackageInfo first = store.getPackageInfo(name, flags);
            assertTrue(first.activities.length > 0 && first.services.length > 0 && first.requestedPermissions.length > 0);
            byte[] signer = first.signatures[0].toByteArray();
            String activity = first.activities[0].name;
            int authenticator = first.services[0].metaData.getInt("android.accounts.AccountAuthenticator");
            assertTrue(authenticator != 0);
            first.signatures[0] = new Signature(new byte[]{1, 2, 3});
            first.activities[0].name = "modified.by.caller";
            first.applicationInfo.dataDir = "/modified/by/caller";
            first.services[0].metaData.putInt("android.accounts.AccountAuthenticator", -1);
            first.requestedPermissions[0] = "modified.by.caller";
            PackageInfo fresh = store.getPackageInfo(name, flags);
            assertTrue(Arrays.equals(signer, fresh.signatures[0].toByteArray()));
            assertEquals(activity, fresh.activities[0].name);
            assertEquals(installed.dataDir.getPath(), fresh.applicationInfo.dataDir);
            assertEquals(authenticator, fresh.services[0].metaData.getInt("android.accounts.AccountAuthenticator"));
            assertFalse("modified.by.caller".equals(fresh.requestedPermissions[0]));
            assertSame(fresh.applicationInfo, fresh.activities[0].applicationInfo);

            PackageInfo plain = store.getPackageInfo(name, 0);
            assertNull(plain.activities); assertNull(plain.services); assertNull(plain.signatures);
            assertEquals(before + 2, manager.parses.get());
            assertNotNull(store.getPackageInfo(name, flags).services);
            assertEquals(before + 2, manager.parses.get());

            // A different store publishes updates: no local invalidation callback may be assumed.
            PackageStore writer = new PackageStore(observed);
            InstalledPackage reinstalled = writer.install(Collections.singletonList(apk));
            assertFalse(installed.baseApk.equals(reinstalled.baseApk));
            int updateBefore = manager.parses.get();
            PackageInfo sameVersion = store.getPackageInfo(name, flags);
            assertEquals(reinstalled.baseApk.getPath(), sameVersion.applicationInfo.sourceDir);
            assertEquals(updateBefore + 1, manager.parses.get());
            copyAsset("account-ui-authenticator-v2.apk", apk);
            InstalledPackage updated = writer.install(Collections.singletonList(apk));
            updateBefore = manager.parses.get();
            PackageInfo secondVersion = store.getPackageInfo(name, flags);
            assertEquals(2, secondVersion.versionCode);
            assertEquals(updated.baseApk.getPath(), secondVersion.applicationInfo.sourceDir);
            assertEquals(installed.dataDir, updated.dataDir);
            assertTrue(Arrays.equals(signer, secondVersion.signatures[0].toByteArray()));
            assertEquals(updateBefore + 1, manager.parses.get());

            int[] variants = {0, PackageManager.GET_ACTIVITIES, PackageManager.GET_RECEIVERS,
                    PackageManager.GET_SERVICES, PackageManager.GET_PROVIDERS, PackageManager.GET_PERMISSIONS,
                    PackageManager.GET_META_DATA, PackageManager.GET_SIGNATURES, PackageManager.GET_CONFIGURATIONS};
            for (int variant : variants) assertNotNull(store.getPackageInfo(name, variant));
            int filled = manager.parses.get();
            store.getPackageInfo(name, 0);
            assertEquals("The bounded cache retained more than eight archive views", filled + 1, manager.parses.get());

            File record = new File(directory, "vlite/records/" + name + ".properties");
            Files.write(record.toPath(), "format=invalid\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try { store.getPackageInfo(name, 0); fail("Cached metadata hid a corrupt committed record"); }
            catch (java.io.IOException expected) { }
            Files.delete(record.toPath());
            assertNull("Cached metadata hid a missing committed record", store.getPackageInfo(name, 0));
            System.out.println("MYLITE_METADATA_CACHE realParse=true; concurrentReads=15; initialParses=1; "
                    + "deepCopies=true; flagsSeparated=true; externalGenerations=true; boundedViews=8; freshRecordChecks=true");
        } finally {
            try (Stream<Path> paths = Files.walk(directory.toPath())) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    private void copyAsset(String asset, File destination) throws Exception {
        try (InputStream input = getInstrumentation().getContext().getAssets().open(asset)) {
            Files.copy(input, destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class CountingManager extends MockPackageManager {
        final PackageManager actual;
        final AtomicInteger parses = new AtomicInteger();
        CountingManager(PackageManager actual) { this.actual = actual; }
        @Override public PackageInfo getPackageArchiveInfo(String path, int flags) {
            parses.incrementAndGet();
            return actual.getPackageArchiveInfo(path, flags);
        }
    }
}
