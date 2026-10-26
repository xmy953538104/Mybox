package io.github.xmy953538104.mybox.engine;

import android.content.pm.ApplicationInfo;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Imports an authentic Google Store into the container; never enables or changes the system copy. */
public final class GoogleComponents {
    public static InstalledPackage installStore() throws Exception {
        Engine engine = Engine.get();
        TrustedMicrog.require(engine.hostContext(), engine.packages().get("com.google.android.gms"));
        InstalledPackage installed = engine.packages().get("com.android.vending");
        if (installed != null) {
            if (!InstallerPolicy.isGoogleStore(engine.packages(), installed)) throw new SecurityException("Installed Store has an unexpected signer");
            return installed;
        }
        android.content.res.AssetManager assets = engine.hostContext().getAssets();
        GoogleStoreAssets.Copied selected = GoogleStoreAssets.fromAssets(new GoogleStoreAssets.Assets() {
            @Override public String[] list(String path) throws java.io.IOException { return assets.list(path); }
            @Override public java.io.InputStream open(String path) throws java.io.IOException { return assets.open(path); }
        }, engine.hostContext().getCacheDir());
        if (selected == null) {
            ApplicationInfo info;
            try { info = engine.hostContext().getPackageManager().getApplicationInfo("com.android.vending", android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS); }
            catch (android.content.pm.PackageManager.NameNotFoundException absent) {
                throw new StoreSourceRequiredException();
            }
            List<File> inputs = new ArrayList<>(); inputs.add(new File(info.sourceDir));
            if (info.splitSourceDirs != null) for (String split : info.splitSourceDirs) inputs.add(new File(split));
            selected = GoogleStoreAssets.fromFiles(inputs, engine.hostContext().getCacheDir());
        }
        return publish(selected);
    }
    public static InstalledPackage importStore(List<File> files) throws Exception {
        TrustedMicrog.require(Engine.get().hostContext(),Engine.get().packages().get("com.google.android.gms"));
        return publish(GoogleStoreAssets.fromFiles(files,Engine.get().hostContext().getCacheDir()));
    }
    public static InstalledPackage importReferenceStore(File reference) throws Exception {
        TrustedMicrog.require(Engine.get().hostContext(),Engine.get().packages().get("com.google.android.gms"));
        return publish(GoogleStoreAssets.fromReference(reference,Engine.get().hostContext().getCacheDir()));
    }
    public static final class StoreSourceRequiredException extends java.io.IOException {
        StoreSourceRequiredException() { super("microG 已安装；请选择 Google Play 原签名 APK 与全部分包，或从已有 GBox 安装包导入 Play"); }
    }
    private static InstalledPackage publish(GoogleStoreAssets.Copied selected) throws Exception {
        InstalledPackage published = null;
        Throwable failure = null;
        try {
            published = Engine.get().packages().installWithPublisher(selected.files(), "com.android.vending", InstallerPolicy::isGoogleStore);
            return published;
        } catch (Exception | Error error) {
            failure = error; throw error;
        } finally {
            Exception cleanup = selected.finish(published != null, failure);
            if (cleanup != null) android.util.Log.w("MyliteStore", "Store installed; temporary source cleanup failed", cleanup);
        }
    }
    static void verifyStoreSigner(File input) throws Exception {
        // PackageStore verifies every manifest's expected package and split set before publication.
        if (!InstallerPolicy.isGoogleStore(Engine.get().packages().verifyArchive(input)))
            throw new SecurityException("Play APK 的签名不在当前信任范围，已拒绝导入");
    }
    private GoogleComponents() { }
}
