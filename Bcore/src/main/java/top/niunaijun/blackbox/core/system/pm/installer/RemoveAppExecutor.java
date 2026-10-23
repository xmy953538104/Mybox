package top.niunaijun.blackbox.core.system.pm.installer;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * Created by Milk on 4/27/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class RemoveAppExecutor implements Executor {
    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        try {
            String name = ps.pkg.packageName;
            AtomicPackageDirectory.delete(new java.io.File(BEnvironment.getAppRootDir(), ".backup-" + name));
            AtomicPackageDirectory.delete(new java.io.File(BEnvironment.getAppRootDir(), ".stage-" + name));
            AtomicPackageDirectory.delete(BEnvironment.getAppDir(name));
            return 0;
        } catch (java.io.IOException e) { return -1; }
    }
}
