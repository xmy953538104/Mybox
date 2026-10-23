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
public class CreateUserExecutor implements Executor {

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        java.io.File[] directories = {BEnvironment.getDataDir(packageName, userId),
                BEnvironment.getDataCacheDir(packageName, userId), BEnvironment.getDataFilesDir(packageName, userId),
                BEnvironment.getDataDatabasesDir(packageName, userId), BEnvironment.getDeDataDir(packageName, userId)};
        for (java.io.File directory : directories) {
            if (!directory.isDirectory() && !directory.mkdirs()) return -1;
        }
        return 0;
    }
}
