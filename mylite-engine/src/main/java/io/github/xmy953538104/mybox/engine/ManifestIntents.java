package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.PatternMatcher;
import com.android.apksig.internal.apk.AndroidBinXmlParser;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Rebuilds implicit intent matching from the installed APK, without a hardcoded service list. */
final class ManifestIntents {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final class Entry {
        final String component;
        final IntentFilter filter;
        Entry(String component, IntentFilter filter) { this.component = component; this.filter = filter; }
    }

    static List<ResolveInfo> query(Engine engine, Intent intent, String resolvedType, int kind, int flags) throws Exception {
        String tag = kind == PackageManager.GET_ACTIVITIES ? "activity" : kind == PackageManager.GET_SERVICES ? "service"
                : kind == PackageManager.GET_RECEIVERS ? "receiver" : null;
        if (tag == null || intent.getComponent() != null || intent.getSelector() != null) throw new IllegalArgumentException("Implicit component query without a selector required");
        Map<String, ResolveInfo> matches = new LinkedHashMap<>();
        for (InstalledPackage installed : engine.packages().list()) {
            if (intent.getPackage() != null && !intent.getPackage().equals(installed.packageName)) continue;
            PackageInfo pkg = engine.packages().getPackageInfo(installed.packageName, kind | flags);
            if (pkg == null) continue;
            ComponentInfo[] components = kind == PackageManager.GET_ACTIVITIES ? pkg.activities
                    : kind == PackageManager.GET_SERVICES ? pkg.services : pkg.receivers;
            if (components == null) continue;
            for (Entry entry : read(installed.baseApk, installed.packageName, tag)) {
                if (kind == PackageManager.GET_ACTIVITIES && (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0
                        && !entry.filter.hasCategory(Intent.CATEGORY_DEFAULT)) continue;
                int score = entry.filter.match(intent.getAction(), resolvedType, intent.getScheme(), intent.getData(), intent.getCategories(), "Mybox");
                if (score < 0) continue;
                for (ComponentInfo component : components) {
                    if (!entry.component.equals(component.name) || !GuestPackageQueries.matchesComponent(engine, component, flags)) continue;
                    String key = installed.packageName + "/" + component.name;
                    ResolveInfo previous = matches.get(key);
                    if (previous != null && (previous.priority > entry.filter.getPriority()
                            || previous.priority == entry.filter.getPriority() && previous.match >= score)) continue;
                    ResolveInfo result = new ResolveInfo();
                    result.filter = entry.filter;
                    result.match = score;
                    result.priority = entry.filter.getPriority();
                    result.isDefault = entry.filter.hasCategory(Intent.CATEGORY_DEFAULT);
                    if (kind == PackageManager.GET_SERVICES) result.serviceInfo = (android.content.pm.ServiceInfo) component;
                    else result.activityInfo = (android.content.pm.ActivityInfo) component;
                    matches.put(key, result);
                }
            }
        }
        List<ResolveInfo> results = new ArrayList<>(matches.values());
        results.sort((a, b) -> a.priority != b.priority ? Integer.compare(b.priority, a.priority) : Integer.compare(b.match, a.match));
        return results;
    }

    private static List<Entry> read(File apk, String packageName, String kind) throws Exception {
        byte[] manifest;
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) throw new IOException("Installed APK manifest is missing");
            try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (out.size() + count > 4 * 1024 * 1024) throw new IOException("Manifest exceeds its bound");
                    out.write(buffer, 0, count);
                }
                manifest = out.toByteArray();
            }
        }
        AndroidBinXmlParser xml = new AndroidBinXmlParser(ByteBuffer.wrap(manifest));
        List<Entry> entries = new ArrayList<>();
        String component = null;
        int componentDepth = -1, filterDepth = -1;
        IntentFilter filter = null;
        for (int event = xml.next(); event != AndroidBinXmlParser.EVENT_END_DOCUMENT; event = xml.next()) {
            String name = xml.getName();
            if (event == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                if (kind.equals(name) || kind.equals("activity") && name.equals("activity-alias")) {
                    String raw = string(xml, "name");
                    if (raw == null) throw new IOException("Component name is missing");
                    component = raw.startsWith(".") ? packageName + raw : raw.contains(".") ? raw : packageName + "." + raw;
                    componentDepth = xml.getDepth();
                } else if (component != null && name.equals("intent-filter")) {
                    filter = new IntentFilter();
                    filterDepth = xml.getDepth();
                    int priority = attribute(xml, "priority");
                    if (priority >= 0) filter.setPriority(xml.getAttributeIntValue(priority));
                } else if (filter != null) {
                    if (name.equals("action")) filter.addAction(required(xml, "name"));
                    else if (name.equals("category")) filter.addCategory(required(xml, "name"));
                    else if (name.equals("data")) data(xml, filter);
                    else if (name.equals("uri-relative-filter-group")) throw new IOException("Relative URI groups are not supported");
                }
            } else if (event == AndroidBinXmlParser.EVENT_END_ELEMENT) {
                if (xml.getDepth() == filterDepth && name.equals("intent-filter")) {
                    entries.add(new Entry(component, filter));
                    filter = null;
                    filterDepth = -1;
                }
                if (xml.getDepth() == componentDepth) { component = null; componentDepth = -1; }
            }
        }
        return entries;
    }

    private static void data(AndroidBinXmlParser xml, IntentFilter filter) throws Exception {
        if (attribute(xml, "mimeGroup") >= 0) throw new IOException("Dynamic MIME groups are not supported");
        String value = string(xml, "mimeType");
        if (value != null) filter.addDataType(value);
        value = string(xml, "scheme");
        if (value != null) filter.addDataScheme(value);
        value = string(xml, "host");
        if (value != null) filter.addDataAuthority(value, string(xml, "port"));
        String[] attributes = {"path", "pathPrefix", "pathPattern", "pathAdvancedPattern", "pathSuffix"};
        for (int pattern = 0; pattern < attributes.length; pattern++) {
            value = string(xml, attributes[pattern]);
            if (value != null) {
                if (pattern > PatternMatcher.PATTERN_SIMPLE_GLOB && android.os.Build.VERSION.SDK_INT < 31) throw new IOException("New URI path patterns require API 31");
                filter.addDataPath(value, pattern);
            }
        }
        String[] parts = {"ssp", "sspPrefix", "sspPattern", "sspAdvancedPattern", "sspSuffix"};
        for (int pattern = 0; pattern < parts.length; pattern++) {
            value = string(xml, parts[pattern]);
            if (value != null) {
                if (pattern > PatternMatcher.PATTERN_SIMPLE_GLOB && android.os.Build.VERSION.SDK_INT < 31) throw new IOException("New URI part patterns require API 31");
                filter.addDataSchemeSpecificPart(value, pattern);
            }
        }
    }

    private static int attribute(AndroidBinXmlParser xml, String name) throws Exception {
        for (int i = 0; i < xml.getAttributeCount(); i++) if (name.equals(xml.getAttributeName(i)) && ANDROID.equals(xml.getAttributeNamespace(i))) return i;
        return -1;
    }
    private static String string(AndroidBinXmlParser xml, String name) throws Exception {
        int index = attribute(xml, name);
        if (index < 0) return null;
        String value = xml.getAttributeStringValue(index);
        if (value == null) throw new IOException("Resource-backed intent attributes are not supported");
        return value;
    }
    private static String required(AndroidBinXmlParser xml, String name) throws Exception {
        String value = string(xml, name);
        if (value == null || value.isEmpty()) throw new IOException("Intent attribute is missing: " + name);
        return value;
    }
}
