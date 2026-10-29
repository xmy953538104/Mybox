package io.github.xmy953538104.mybox.engine.packages;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class PublicationNotificationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void onlyVerifiedPublicationNotifiesAfterTheInstallLockIsReleased() throws Exception {
        File root = temporary.newFolder("store"), inputs = temporary.newFolder("inputs");
        List<InstalledPackage> previous = new ArrayList<>(), current = new ArrayList<>();
        PackageStore store = new PackageStore(null, root, 35, new String[]{"arm64-v8a"}, (before, after) -> {
            try (RandomAccessFile lock = new RandomAccessFile(new File(root, "install.lock"), "rw");
                 FileLock acquired = lock.getChannel().tryLock()) {
                assertNotNull("Publication observer ran under the install file lock", acquired);
                assertTrue(after.baseApk.isFile());
                assertTrue(new File(root, "records/" + after.packageName + ".properties").isFile());
                previous.add(before); current.add(after);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        String name = "org.mybox.published";
        InstalledPackage first = store.install(Collections.singletonList(new SignedApkFixture().packageName(name).write(inputs, "signer-a")));
        InstalledPackage second = store.install(Collections.singletonList(new SignedApkFixture().packageName(name).version(2).write(inputs, "signer-a")));
        assertEquals(2, current.size()); assertNull(previous.get(0));
        assertEquals(first.baseApk, previous.get(1).baseApk); assertEquals(second.baseApk, current.get(1).baseApk);
        try { store.install(Collections.singletonList(new SignedApkFixture().packageName(name).version(3).write(inputs, "signer-b"))); fail("Foreign signer published"); }
        catch (java.io.IOException expected) { }
        assertEquals(2, current.size()); assertEquals(second.baseApk, store.get(name).baseApk);
    }

    @Test public void notificationFailureDoesNotInventAnInstallationFailureAfterPublication() throws Exception {
        File root = temporary.newFolder("store"), inputs = temporary.newFolder("inputs");
        PackageStore store = new PackageStore(null, root, 35, new String[]{"arm64-v8a"}, (before, after) -> {
            throw new IllegalStateException("Controlled notification failure");
        });
        InstalledPackage installed = store.install(Collections.singletonList(new SignedApkFixture().packageName("org.mybox.published").write(inputs, "signer-a")));
        assertTrue(installed.baseApk.isFile()); assertEquals(installed.baseApk, store.get(installed.packageName).baseApk);
    }
}
