package top.niunaijun.blackbox.core.system.pm;

import android.content.pm.PackageParser;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class ApkLiteInspector {
    final String packageName;
    final String splitName;

    ApkLiteInspector(File apk) throws Exception {
        Method parse = PackageParser.class.getDeclaredMethod("parseApkLite", File.class, int.class);
        parse.setAccessible(true);
        Object lite = parse.invoke(null, apk, 0);
        packageName = (String) value(lite, "packageName");
        splitName = (String) value(lite, "splitName");
    }

    private static Object value(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
