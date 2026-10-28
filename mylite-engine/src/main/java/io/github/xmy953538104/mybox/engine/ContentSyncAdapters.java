package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.IOException;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Finds real declared adapters; absence schedules no work and grants no account access. */
final class ContentSyncAdapters {
    private static final String ACTION = "android.content.SyncAdapter";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    static boolean matches(Engine engine, Set<String> authorities) throws Exception {
        int flags = PackageManager.GET_META_DATA | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        for (ResolveInfo resolved : ManifestIntents.query(engine, new Intent(ACTION), null, PackageManager.GET_SERVICES, flags)) {
            ServiceInfo service = resolved.serviceInfo;
            if (service.metaData == null || !service.metaData.containsKey(ACTION)) continue;
            InstalledPackage installed = engine.packages().get(service.packageName);
            if (installed == null || !installed.baseApk.getPath().equals(service.applicationInfo.sourceDir))
                throw new SecurityException("Sync adapter generation changed before metadata lookup");
            int xmlId = service.metaData.getInt(ACTION);
            if (xmlId == 0) throw new IOException("Sync adapter metadata is not an XML resource");
            boolean match;
            try {
                Resources resources = engine.hostContext().getPackageManager().getResourcesForApplication(service.applicationInfo);
                try (XmlResourceParser xml = resources.getXml(xmlId)) {
                    int event;
                    do { event = xml.next(); } while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT);
                    if (event != XmlPullParser.START_TAG || !"sync-adapter".equals(xml.getName()))
                        throw new IOException("Invalid sync adapter metadata root");
                    String authority = attribute(resources, xml, "contentAuthority");
                    attribute(resources, xml, "accountType");
                    match = authorities.contains(authority);
                }
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException | XmlPullParserException failure) {
                throw new IOException("Virtual sync adapter metadata cannot be read", failure);
            }
            InstalledPackage current = engine.packages().get(service.packageName);
            if (current == null || !installed.baseApk.equals(current.baseApk))
                throw new SecurityException("Sync adapter generation changed during metadata lookup");
            if (match) return true;
        }
        return false;
    }

    private static String attribute(Resources resources, XmlResourceParser xml, String name) throws IOException {
        int resource = xml.getAttributeResourceValue(ANDROID, name, 0);
        String value = resource == 0 ? xml.getAttributeValue(ANDROID, name) : resources.getString(resource);
        if (value == null || value.isEmpty()) throw new IOException("Required sync adapter metadata is empty");
        return value;
    }
}
