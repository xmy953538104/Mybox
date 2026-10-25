package io.github.xmy953538104.mybox.engine;

/** Google startup retains real publisher trust independently of compatibility signature views. */
final class GoogleLaunchPolicy {
    static void require(Engine engine, String packageName) {
        if (!GuestPackageQueries.isGoogleIdentity(packageName)) return;
        try {
            TrustedMicrog.require(engine.hostContext(), engine.packages().get("com.google.android.gms"));
            if ("com.google.android.gms".equals(packageName)) return;
            io.github.xmy953538104.mybox.engine.packages.InstalledPackage installed = engine.packages().get(packageName);
            if (InstallerPolicy.isGoogleStore(engine.packages(), installed)) return;
            throw new SecurityException("Store requires its real Google signer; GSF startup is not implemented");
        }
        catch (java.io.IOException | SecurityException failure) {
            throw new EngineException("Google startup requires approved microG and the target's real signing identity", failure);
        }
    }
}
