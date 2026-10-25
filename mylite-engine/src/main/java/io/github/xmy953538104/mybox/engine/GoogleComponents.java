package io.github.xmy953538104.mybox.engine;

import android.content.pm.ApplicationInfo;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
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
        List<File> inputs = new ArrayList<>(); File temporary = null;
        try {
            String[] assets = engine.hostContext().getAssets().list("mybox");
            if (assets != null && java.util.Arrays.asList(assets).contains("google-play.apk")) {
                temporary = File.createTempFile("google-play-", ".apk", engine.hostContext().getCacheDir());
                try (InputStream input = engine.hostContext().getAssets().open("mybox/google-play.apk"); FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[65536]; long size = 0;
                    for (int count; (count = input.read(buffer)) != -1;) {
                        size += count; if (size > 512L * 1024 * 1024) throw new java.io.IOException("Store asset exceeds its size limit");
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                inputs.add(temporary);
            } else {
                ApplicationInfo info;
                try { info = engine.hostContext().getPackageManager().getApplicationInfo("com.android.vending", android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS); }
                catch (android.content.pm.PackageManager.NameNotFoundException absent) {
                    throw new java.io.IOException("microG 已安装；缺少 Google Play 安装包，请通过“导入 APK”选择 Google 原签名的商店 APK");
                }
                inputs.add(new File(info.sourceDir));
                if (info.splitSourceDirs != null) for (String split : info.splitSourceDirs) inputs.add(new File(split));
            }
            return engine.packages().installWithPublisher(inputs, "com.android.vending", InstallerPolicy::isGoogleStore);
        } finally { if (temporary != null && temporary.exists() && !temporary.delete()) android.util.Log.w("MyliteStore", "Cannot remove temporary Store APK"); }
    }
    static void verifyStoreSigner(File input) throws Exception {
        // PackageStore verifies every manifest's expected package and split set before publication.
        if (!InstallerPolicy.isGoogleStore(Engine.get().packages().verifyArchive(input)))
            throw new SecurityException("Play APK 的签名不在当前信任范围，已拒绝导入");
    }
    private GoogleComponents() { }
}
