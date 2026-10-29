package io.github.xmy953538104.mybox.engine.packages;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CategoryHintTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void recordedInstallerOwnsDurableHintAcrossUpdateAndReopen() throws Exception {
        File root = temporary.newFolder("store"), inputs = temporary.newFolder("inputs");
        PackageStore store = new PackageStore(null, root, 35, new String[]{"arm64-v8a"});
        InstalledPackage installer = store.install(Collections.singletonList(new SignedApkFixture().packageName("org.mybox.installer").write(inputs, "signer-a")));
        java.lang.reflect.Constructor<io.github.xmy953538104.mybox.engine.CallerIdentity> constructor =
                io.github.xmy953538104.mybox.engine.CallerIdentity.class.getDeclaredConstructor(String.class, int.class, int.class, java.util.List.class);
        constructor.setAccessible(true);
        io.github.xmy953538104.mybox.engine.CallerIdentity caller = constructor.newInstance(installer.packageName, installer.appId, 123, installer.certificateSha256);
        String target = "org.mybox.target";
        store.install(Collections.singletonList(new SignedApkFixture().packageName(target).write(inputs, "signer-b")), target, caller);
        assertEquals(-1, store.categoryHint(target));
        store.setCategoryHint(installer.packageName, installer.baseApk.getAbsolutePath(), target, 7);
        store = new PackageStore(null, root, 35, new String[]{"arm64-v8a"}); assertEquals(7, store.categoryHint(target));
        store.install(Collections.singletonList(new SignedApkFixture().packageName(target).version(2).write(inputs, "signer-b")), target, caller);
        assertEquals(7, store.categoryHint(target));
        try { store.setCategoryHint(target, store.get(target).baseApk.getAbsolutePath(), target, 0); fail("Target claimed installer authority"); }
        catch (IllegalArgumentException expected) { }
        try { store.setCategoryHint(installer.packageName, "stale-generation", target, 0); fail("Stale installer accepted"); }
        catch (SecurityException expected) { }
        try { store.setCategoryHint(installer.packageName, installer.baseApk.getAbsolutePath(), "org.mybox.missing", 0); fail(); }
        catch (IllegalArgumentException expected) { }
        assertEquals(7, store.categoryHint(target));
        store.setCategoryHint(installer.packageName, installer.baseApk.getAbsolutePath(), target, -1); assertEquals(-1, store.categoryHint(target));
    }

    @Test public void malformedOrEscapingHintCannotMasqueradeAsValidMetadata() throws Exception {
        File root = temporary.newFolder("store");
        PackageStore store = new PackageStore(null, root, 35, new String[]{"arm64-v8a"});
        File directory = new File(root, "category-hints"); assertTrue(directory.mkdir());
        File file = new File(directory, "org.mybox.target.properties");
        for (String text : new String[]{"format=2\ncategory=7\n", "format=1\ncategory=invalid\n", "format=1\ncategory=7\nextra=1\n"}) {
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            try { store.categoryHint("org.mybox.target"); fail("Corrupt hint accepted"); } catch (IOException expected) { }
        }
        try { store.categoryHint("../escape"); fail("Unsafe target accepted"); } catch (IOException expected) { }
        Files.delete(file.toPath());
        File foreign = temporary.newFile("outside"); Files.write(foreign.toPath(), "format=1\ncategory=7\n".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(file.toPath(), foreign.toPath());
        try { store.categoryHint("org.mybox.target"); fail("Foreign hint read"); } catch (IOException expected) { }
    }
}
