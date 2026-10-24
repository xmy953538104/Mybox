package io.github.xmy953538104.mybox.engine;

/** The source-built service may run after core/account acceptance; other Google identities remain gated. */
final class GoogleLaunchPolicy {
    static void require(Engine engine, String packageName) {
        if (!GuestPackageQueries.isGoogleIdentity(packageName)) return;
        if (!"com.google.android.gms".equals(packageName)) {
            throw new EngineException("Google Store/GSF startup awaits installer and runtime acceptance");
        }
        try { TrustedMicrog.require(engine.hostContext(), engine.packages().get(packageName)); }
        catch (java.io.IOException | SecurityException failure) {
            throw new EngineException("Only the exact approved source-built microG may start", failure);
        }
    }
}
