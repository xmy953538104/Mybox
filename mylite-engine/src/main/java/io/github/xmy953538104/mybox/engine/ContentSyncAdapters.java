package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import android.content.ComponentName;
import android.content.SyncAdapterType;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.IOException;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Finds real declared adapters; absence schedules no work and grants no account access. */
final class ContentSyncAdapters {
    private static final String ACTION = "android.content.SyncAdapter";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    static final class Adapter {
        final InstalledPackage installed;
        final ComponentName component;
        final SyncAdapterType type;
        Adapter(InstalledPackage installed, ServiceInfo service, SyncAdapterType type) {
            this.installed = installed; component = new ComponentName(service.packageName, service.name); this.type = type;
        }
    }

    static boolean matches(Engine engine, Set<String> authorities) throws Exception {
        for (Adapter adapter : list(engine)) if (authorities.contains(adapter.type.authority)) return true;
        return false;
    }

    static List<Adapter> list(Engine engine) throws Exception {
        ArrayList<Adapter> result = new ArrayList<>();
        HashSet<String> keys = new HashSet<>();
        int flags = PackageManager.GET_META_DATA | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        for (ResolveInfo resolved : ManifestIntents.query(engine, new Intent(ACTION), null, PackageManager.GET_SERVICES, flags)) {
            ServiceInfo service = resolved.serviceInfo;
            if (service.metaData == null || !service.metaData.containsKey(ACTION)) continue;
            InstalledPackage installed = engine.packages().get(service.packageName);
            if (installed == null || !installed.baseApk.getPath().equals(service.applicationInfo.sourceDir))
                throw new SecurityException("Sync adapter generation changed before metadata lookup");
            int xmlId = service.metaData.getInt(ACTION);
            if (xmlId == 0) throw new IOException("Sync adapter metadata is not an XML resource");
            SyncAdapterType type;
            try {
                Resources resources = engine.hostContext().getPackageManager().getResourcesForApplication(service.applicationInfo);
                try (XmlResourceParser xml = resources.getXml(xmlId)) {
                    int event;
                    do { event = xml.next(); } while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT);
                    if (event != XmlPullParser.START_TAG || !"sync-adapter".equals(xml.getName()))
                        throw new IOException("Invalid sync adapter metadata root");
                    String authority = attribute(resources, xml, "contentAuthority");
                    String accountType = attribute(resources, xml, "accountType");
                    boolean visible = bool(resources, xml, "userVisible", true), upload = bool(resources, xml, "supportsUploading", true);
                    boolean always = bool(resources, xml, "isAlwaysSyncable", false), parallel = bool(resources, xml, "allowParallelSyncs", false);
                    String settings = optional(resources, xml, "settingsActivity");
                    try {
                        type = SyncAdapterType.class.getConstructor(String.class, String.class, boolean.class, boolean.class,
                                boolean.class, boolean.class, String.class, String.class).newInstance(authority, accountType,
                                visible, upload, always, parallel, settings, service.packageName);
                    } catch (NoSuchMethodException oldPlatform) {
                        type = SyncAdapterType.class.getConstructor(String.class, String.class, boolean.class, boolean.class,
                                boolean.class, boolean.class, String.class).newInstance(authority, accountType, visible, upload, always, parallel, settings);
                    }
                }
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException | XmlPullParserException | IOException failure) {
                EngineDiagnostics.failure("sync", "adapter_metadata", installed.appId, failure);
                continue;
            }
            InstalledPackage current = engine.packages().get(service.packageName);
            if (current == null || !installed.baseApk.equals(current.baseApk))
                throw new SecurityException("Sync adapter generation changed during metadata lookup");
            // An ambiguous authority/type must not bind whichever package happens to enumerate first.
            if (!keys.add(type.authority + "\n" + type.accountType)) throw new SecurityException("Conflicting virtual sync adapters");
            if (result.size() >= 128) throw new IOException("Virtual sync adapter quota exceeded");
            result.add(new Adapter(installed, service, type));
        }
        return result;
    }

    private static String attribute(Resources resources, XmlResourceParser xml, String name) throws IOException {
        String value = optional(resources, xml, name);
        if (value == null || value.isEmpty()) throw new IOException("Required sync adapter metadata is empty");
        return value;
    }
    private static String optional(Resources resources, XmlResourceParser xml, String name) {
        int resource = xml.getAttributeResourceValue(ANDROID, name, 0);
        return resource == 0 ? xml.getAttributeValue(ANDROID, name) : resources.getString(resource);
    }
    private static boolean bool(Resources resources, XmlResourceParser xml, String name, boolean fallback) {
        int resource = xml.getAttributeResourceValue(ANDROID, name, 0);
        return resource == 0 ? xml.getAttributeBooleanValue(ANDROID, name, fallback) : resources.getBoolean(resource);
    }
}
