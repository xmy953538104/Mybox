package top.niunaijun.blackbox.core.system.pm;

import android.content.pm.PackageParser;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class ApkLiteInspector {
    final String packageName;
    final String splitName;
    final boolean splitRequired;
    final String usesSplitName;
    final String configForSplit;

    ApkLiteInspector(File apk) throws Exception {
        Method parse = PackageParser.class.getDeclaredMethod("parseApkLite", File.class, int.class);
        parse.setAccessible(true);
        Object lite = parse.invoke(null, apk, 0);
        packageName = (String) value(lite, "packageName");
        splitName = (String) value(lite, "splitName");
        splitRequired = Boolean.TRUE.equals(optionalValue(lite, "isSplitRequired"));
        usesSplitName = (String) optionalValue(lite, "usesSplitName");
        configForSplit = (String) optionalValue(lite, "configForSplit");
    }

    private static Object value(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Object optionalValue(Object owner, String name) throws Exception {
        try { return value(owner, name); }
        catch (NoSuchFieldException absentOnOldAndroid) { return null; }
    }
}
