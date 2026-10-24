package io.github.xmy953538104.mybox.engine.packages;

import com.android.apksig.internal.apk.AndroidBinXmlParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Binary XML is decoded by the pinned Apache-2.0 apksig parser. */
final class ApkManifest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    String packageName;
    String splitName;
    long versionCode;
    String configForSplit;
    final List<String> dependencies = new ArrayList<>();
    final java.util.Map<String, Boolean> libraries = new java.util.LinkedHashMap<>();
    final List<String> launchActivities = new ArrayList<>();
    boolean hasComponents;
    boolean isolatedSplits;
    int minSdk = 1;

    static ApkManifest read(File apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) throw new PackageValidationException("APK has no AndroidManifest.xml");
            byte[] bytes;
            try (InputStream input = zip.getInputStream(entry)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 4 * 1024 * 1024) throw new PackageValidationException("Manifest is too large");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            return parse(bytes);
        }
    }

    private static ApkManifest parse(byte[] bytes) throws IOException {
        try {
            AndroidBinXmlParser xml = new AndroidBinXmlParser(ByteBuffer.wrap(bytes));
            ApkManifest manifest = new ApkManifest();
            String activity = null;
            int activityDepth = -1;
            int filterDepth = -1;
            boolean main = false, launcher = false;
            long version = 0, major = 0;
            boolean root = false;
            for (int event = xml.next(); event != AndroidBinXmlParser.EVENT_END_DOCUMENT; event = xml.next()) {
                String name = xml.getName();
                if (event == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                    if (xml.getDepth() == 1 && "manifest".equals(name)) {
                        root = true;
                        manifest.packageName = string(xml, "", "package");
                        manifest.splitName = string(xml, "", "split");
                        manifest.configForSplit = string(xml, "", "configForSplit");
                        version = integer(xml, ANDROID, "versionCode", 0) & 0xffffffffL;
                        major = integer(xml, ANDROID, "versionCodeMajor", 0) & 0xffffffffL;
                        manifest.isolatedSplits = bool(xml, ANDROID, "isolatedSplits", false);
                    } else if ("uses-sdk".equals(name)) {
                        manifest.minSdk = integer(xml, ANDROID, "minSdkVersion", 1);
                    } else if ("uses-split".equals(name)) {
                        String dependency = string(xml, ANDROID, "name");
                        if (dependency == null) throw new PackageValidationException("uses-split has no name");
                        manifest.dependencies.add(dependency);
                    } else if ("uses-library".equals(name) && xml.getDepth() == 3) {
                        String library = string(xml, ANDROID, "name");
                        if (library == null || library.isEmpty() || library.length() > 255 || manifest.libraries.size() >= 64) {
                            throw new PackageValidationException("Invalid or excessive shared library declarations");
                        }
                        boolean required = bool(xml, ANDROID, "required", true);
                        manifest.libraries.put(library, required || Boolean.TRUE.equals(manifest.libraries.get(library)));
                    } else if ("activity".equals(name) || "activity-alias".equals(name)) {
                        manifest.hasComponents = true;
                        activity = string(xml, ANDROID, "name");
                        activityDepth = xml.getDepth();
                    } else if ("service".equals(name) || "provider".equals(name) || "receiver".equals(name)) {
                        manifest.hasComponents = true;
                    } else if (activity != null && "intent-filter".equals(name)) {
                        filterDepth = xml.getDepth();
                        main = launcher = false;
                    } else if (filterDepth > 0 && "action".equals(name)) {
                        main |= "android.intent.action.MAIN".equals(string(xml, ANDROID, "name"));
                    } else if (filterDepth > 0 && "category".equals(name)) {
                        launcher |= "android.intent.category.LAUNCHER".equals(string(xml, ANDROID, "name"));
                    }
                } else if (event == AndroidBinXmlParser.EVENT_END_ELEMENT) {
                    if (xml.getDepth() == filterDepth && "intent-filter".equals(name)) {
                        String qualified = qualify(manifest.packageName, activity);
                        if (main && launcher && qualified != null && !manifest.launchActivities.contains(qualified)) {
                            manifest.launchActivities.add(qualified);
                        }
                        filterDepth = -1;
                    }
                    if (xml.getDepth() == activityDepth) { activity = null; activityDepth = -1; }
                }
            }
            if (!root || manifest.packageName == null || !manifest.packageName.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
                throw new PackageValidationException("Invalid manifest package name");
            }
            if (major > Integer.MAX_VALUE) throw new PackageValidationException("Invalid versionCodeMajor");
            manifest.versionCode = (major << 32) | version;
            if (manifest.splitName != null && !manifest.splitName.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) {
                throw new PackageValidationException("Invalid split name");
            }
            return manifest;
        } catch (AndroidBinXmlParser.XmlParserException | IllegalArgumentException failure) {
            throw new PackageValidationException("Malformed binary AndroidManifest.xml", failure);
        }
    }

    private static int attribute(AndroidBinXmlParser xml, String namespace, String name) throws AndroidBinXmlParser.XmlParserException {
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            String actual = xml.getAttributeNamespace(i);
            if (name.equals(xml.getAttributeName(i)) && namespace.equals(actual == null ? "" : actual)) return i;
        }
        return -1;
    }

    private static String string(AndroidBinXmlParser xml, String namespace, String name) throws AndroidBinXmlParser.XmlParserException {
        int index = attribute(xml, namespace, name);
        if (index < 0) return null;
        String value = xml.getAttributeStringValue(index);
        return value == null || value.isEmpty() ? null : value;
    }

    private static int integer(AndroidBinXmlParser xml, String namespace, String name, int fallback) throws AndroidBinXmlParser.XmlParserException {
        int index = attribute(xml, namespace, name);
        return index < 0 ? fallback : xml.getAttributeIntValue(index);
    }

    private static boolean bool(AndroidBinXmlParser xml, String namespace, String name, boolean fallback) throws AndroidBinXmlParser.XmlParserException {
        int index = attribute(xml, namespace, name);
        return index < 0 ? fallback : xml.getAttributeBooleanValue(index);
    }

    private static String qualify(String packageName, String name) {
        if (name == null) return null;
        if (name.startsWith(".")) return packageName + name;
        return name.contains(".") ? name : packageName + "." + name;
    }
}
