package top.niunaijun.blackbox.core.system.pm.installer;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * Created by Milk on 4/24/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 * 创建用户相关
 */
public class RemoveUserExecutor implements Executor {

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        try {
            AtomicPackageDirectory.delete(BEnvironment.getDataDir(packageName, userId));
            AtomicPackageDirectory.delete(BEnvironment.getDeDataDir(packageName, userId));
            if (BEnvironment.getExternalVirtualRoot() != null) {
                AtomicPackageDirectory.delete(BEnvironment.getExternalDataDir(packageName, userId));
            }
            return 0;
        } catch (java.io.IOException e) { return -1; }
    }
}
