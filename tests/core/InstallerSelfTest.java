import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import top.niunaijun.blackbox.core.system.pm.installer.AtomicPackageDirectory;
import top.niunaijun.blackbox.core.system.pm.installer.InstallSessionState;
import top.niunaijun.blackbox.core.system.pm.installer.PackageSetValidator;

public final class InstallerSelfTest {
    public static void main(String[] args) throws Exception {
        validatePackages();
        validateSessions();
        validateAtomicUpdate();
        System.out.println("Installer self-check: signature, split, session, rollback and data preservation passed");
    }

    private static void validatePackages() {
        PackageSetValidator.check("test.app", 2, new String[]{"feature", "config.arm64_v8a"},
                new String[]{"real-key"}, "test.app", 1, new String[]{"real-key"});
        rejected(SecurityException.class, () -> PackageSetValidator.check("test.app", 2, null,
                new String[]{"fake-key"}, "test.app", 1, new String[]{"real-key"}));
        rejected(SecurityException.class, () -> PackageSetValidator.check("test.app", 1, null,
                new String[0], null, 0, null));
        rejected(IllegalArgumentException.class, () -> PackageSetValidator.check("../outside", 1, null,
                new String[]{"key"}, null, 0, null));
        rejected(IllegalArgumentException.class, () -> PackageSetValidator.check("test.app", 0, null,
                new String[]{"key"}, "test.app", 1, new String[]{"key"}));
        rejected(IllegalArgumentException.class, () -> PackageSetValidator.check("test.app", 1,
                new String[]{"feature", "feature"}, new String[]{"key"}, null, 0, null));
        rejected(IllegalArgumentException.class, () -> PackageSetValidator.checkSplitRequirements(null, true, null));
        rejected(IllegalArgumentException.class, () -> PackageSetValidator.checkSplitRequirements(
                new String[]{"config.arm64_v8a"}, true, new String[]{"feature"}));
        PackageSetValidator.checkSplitRequirements(new String[]{"feature", "config.arm64_v8a"}, true, new String[]{"feature"});
        assert !PackageSetValidator.isOfficialGoogleSigner(new String[]{"arbitrary-key"});
        assert !PackageSetValidator.isOfficialGoogleSigner(new String[0]);
        assert PackageSetValidator.isOfficialGoogleSigner(new String[]{"f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"});
    }

    private static void validateSessions() throws Exception {
        InstallSessionState state = new InstallSessionState();
        state.openWriter();
        rejected(IllegalStateException.class, state::seal);
        Thread close = new Thread(() -> state.closeWriter());
        close.start();
        state.awaitClosed(1000);
        close.join();
        state.seal();
        rejected(IllegalStateException.class, state::openWriter);
        rejected(IllegalStateException.class, state::seal);
        state.finish();
        assert state.isFinished();
        rejected(IllegalStateException.class, state::requireMutable);
        InstallSessionState abandoned = new InstallSessionState();
        abandoned.finish();
        rejected(IllegalStateException.class, abandoned::openWriter);
    }

    private static void validateAtomicUpdate() throws Exception {
        Path temp = Files.createTempDirectory("mybox-installer-test");
        try {
            File root = temp.resolve("app").toFile();
            File data = temp.resolve("data/test.app/account.txt").toFile();
            data.getParentFile().mkdirs();
            write(data, "keep-account-and-app-data");
            File current = new File(root, "test.app");
            current.mkdirs();
            write(new File(current, "base.apk"), "version-1");

            AtomicPackageDirectory interrupted = new AtomicPackageDirectory(root, "test.app");
            write(new File(interrupted.stage, "base.apk"), "version-2");
            AtomicPackageDirectory.delete(interrupted.stage);
            rejected(java.io.IOException.class, interrupted::commit);
            assert read(new File(current, "base.apk")).equals("version-1");
            assert read(data).equals("keep-account-and-app-data");

            AtomicPackageDirectory good = new AtomicPackageDirectory(root, "test.app");
            write(new File(good.stage, "base.apk"), "version-2");
            write(new File(good.stage, "split_feature.apk"), "feature-2");
            write(new File(good.stage, "package.conf"), "settings-2");
            good.commit();
            assert read(new File(current, "base.apk")).equals("version-2");
            assert read(new File(current, "package.conf")).equals("settings-2");
            assert read(data).equals("keep-account-and-app-data");
            AtomicPackageDirectory.recover(root, "test.app");
            assert !new File(root, ".backup-test.app").exists();

            assert current.renameTo(new File(root, ".backup-test.app"));
            AtomicPackageDirectory.recover(root, "test.app");
            assert read(new File(current, "base.apk")).equals("version-2");
            assert read(data).equals("keep-account-and-app-data");
            rejected(java.io.IOException.class, () -> new AtomicPackageDirectory(root, "../../outside"));
        } finally { AtomicPackageDirectory.delete(temp.toFile()); }
    }

    private interface Checked { void run() throws Exception; }
    private static void rejected(Class<? extends Exception> type, Checked action) {
        try { action.run(); }
        catch (Exception e) { if (type.isInstance(e)) return; throw new AssertionError(e); }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
