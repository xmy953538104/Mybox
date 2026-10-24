package io.github.xmy953538104.mybox.engine.packages;

import java.io.File;

/** Shares the existing real APK-signature fixture with installer-layer tests. */
public final class InstallerFixturePackages {
    public static PackageStore store(File root) { return new PackageStore(null, root, 35, new String[]{"arm64-v8a"}); }
    public static File apk(File inputs, String packageName, int version, String split, String signer) throws Exception {
        SignedApkFixture fixture = new SignedApkFixture().packageName(packageName).version(version);
        if (split != null) fixture.split(split);
        return fixture.write(inputs, signer);
    }
    private InstallerFixturePackages() { }
}
