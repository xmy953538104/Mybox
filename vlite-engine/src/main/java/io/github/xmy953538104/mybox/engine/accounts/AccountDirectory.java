package io.github.xmy953538104.mybox.engine.accounts;

import android.accounts.AuthenticatorDescription;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Authenticator metadata is read only from committed, verified virtual APKs. */
final class AccountDirectory implements AccountRepository.Directory {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private final Context context;
    private final PackageStore packages;
    private final AuthenticatorTrust trust;

    AccountDirectory(Context context, PackageStore packages, AuthenticatorTrust trust) {
        this.context = context; this.packages = packages; this.trust = trust;
    }

    @Override public List<String> certificates(String packageName) throws IOException {
        InstalledPackage installed = packages.get(packageName);
        return installed == null ? null : installed.certificateSha256;
    }

    @Override public AuthenticatorRegistration authenticator(String type) throws IOException {
        return registrations().get(type);
    }

    AuthenticatorDescription[] descriptions() throws IOException {
        Map<String, AuthenticatorRegistration> values = registrations();
        AuthenticatorDescription[] result = new AuthenticatorDescription[values.size()];
        int index = 0;
        for (AuthenticatorRegistration item : values.values()) result[index++] = new AuthenticatorDescription(
                item.accountType, item.packageName, item.labelId, item.iconId, item.smallIconId,
                item.preferencesId, item.customTokens);
        return result;
    }

    boolean ownsComponent(String packageName, String component) throws IOException {
        for (AuthenticatorRegistration item : registrations().values()) {
            if (packageName.equals(item.packageName) && component.equals(item.className)) return true;
        }
        return false;
    }

    boolean ownsAnyType(String packageName, List<String> certificates) throws IOException {
        for (AuthenticatorRegistration item : registrations().values()) {
            if (packageName.equals(item.packageName) && certificates.equals(item.certificateSha256)) return true;
        }
        return false;
    }

    private Map<String, AuthenticatorRegistration> registrations() throws IOException {
        Map<String, AuthenticatorRegistration> result = new LinkedHashMap<>();
        for (InstalledPackage installed : packages.list()) {
            PackageInfo info = packages.getPackageInfo(installed.packageName,
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
            if (info == null || info.applicationInfo == null || !info.applicationInfo.enabled || info.services == null) continue;
            for (ServiceInfo service : info.services) {
                if (!service.enabled || service.metaData == null) continue;
                int xmlId = service.metaData.getInt("android.accounts.AccountAuthenticator");
                if (xmlId == 0) continue;
                if (!installed.packageName.equals(service.packageName)) throw new IOException("Authenticator component owner mismatch");
                AuthenticatorRegistration registration = parse(installed, service, xmlId);
                if (registration == null) continue;
                if (isGoogleType(registration.accountType)
                        && (!"com.google.android.gms".equals(installed.packageName)
                        || !trust.isApprovedGoogleAuthenticator(installed))) continue;
                if (result.put(registration.accountType, registration) != null) {
                    throw new SecurityException("Multiple virtual authenticators claim the same account type");
                }
            }
        }
        return result;
    }

    static boolean isGoogleType(String type) { return "com.google".equals(type) || type.startsWith("com.google."); }

    private AuthenticatorRegistration parse(InstalledPackage installed, ServiceInfo service, int xmlId) throws IOException {
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(service.applicationInfo);
            try (XmlResourceParser xml = resources.getXml(xmlId)) {
                int event;
                do { event = xml.next(); } while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT);
                if (event != XmlPullParser.START_TAG || !"account-authenticator".equals(xml.getName())) {
                    throw new IOException("Invalid account authenticator metadata root");
                }
                int typeId = xml.getAttributeResourceValue(ANDROID, "accountType", 0);
                String type = typeId == 0 ? xml.getAttributeValue(ANDROID, "accountType") : resources.getString(typeId);
                if (type == null || type.isEmpty()) throw new IOException("Authenticator account type is empty");
                int customId = xml.getAttributeResourceValue(ANDROID, "customTokens", 0);
                boolean custom = customId == 0 ? xml.getAttributeBooleanValue(ANDROID, "customTokens", false)
                        : resources.getBoolean(customId);
                return new AuthenticatorRegistration(type, installed.packageName, service.name,
                        installed.certificateSha256, custom,
                        xml.getAttributeResourceValue(ANDROID, "label", 0),
                        xml.getAttributeResourceValue(ANDROID, "icon", 0),
                        xml.getAttributeResourceValue(ANDROID, "smallIcon", 0),
                        xml.getAttributeResourceValue(ANDROID, "accountPreferences", 0));
            }
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException | XmlPullParserException failure) {
            throw new IOException("Virtual authenticator metadata cannot be read", failure);
        }
    }
}
