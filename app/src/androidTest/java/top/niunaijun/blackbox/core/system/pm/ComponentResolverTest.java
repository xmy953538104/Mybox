package top.niunaijun.blackbox.core.system.pm;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Parcel;
import android.test.AndroidTestCase;
import java.util.List;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallOption;

public final class ComponentResolverTest extends AndroidTestCase {
    private static final String ACTION = "fixture.resolver.SETTINGS";

    public void testMultipleFiltersDoNotMakeDuplicateActivitiesOrReceivers() {
        BPackage pkg = fixture();
        BPackage.Activity first = activity(pkg, "First");
        BPackage.Activity second = activity(pkg, "Second");
        pkg.activities.add(first);
        pkg.activities.add(second);
        pkg.receivers.add(first);
        ComponentResolver resolver = new ComponentResolver();
        resolver.addAllComponents(pkg);
        Intent intent = new Intent(ACTION).setPackage(pkg.packageName);
        assertEquals(2, resolver.queryActivities(intent, null, 0, 0).size());
        assertEquals(2, resolver.queryActivities(intent, null, 0, pkg.activities, 0).size());
        assertEquals(1, resolver.queryReceivers(intent, null, 0, 0).size());
        pkg.mExtras.setComponentState(first.info.name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
        List<ResolveInfo> enabled = resolver.queryActivities(intent, null, 0, 0);
        assertEquals(1, enabled.size());
        assertEquals(second.info.name, enabled.get(0).activityInfo.name);
        assertEquals(2, resolver.queryActivities(intent, null, PackageManager.GET_DISABLED_COMPONENTS, 0).size());
        assertTrue("Query must not mutate the manifest enabled state", first.info.enabled);
        pkg.mExtras.setComponentState(first.info.name, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0);
        assertEquals(2, resolver.queryActivities(intent, null, 0, 0).size());
    }

    public void testMultipleFiltersDoNotMakeDuplicateServices() {
        BPackage pkg = fixture();
        ServiceInfo info = new ServiceInfo();
        info.packageName = pkg.packageName;
        info.name = pkg.packageName + ".Service";
        info.applicationInfo = pkg.applicationInfo;
        Parcel parcel = Parcel.obtain();
        BPackage.Service service;
        try {
            parcel.writeString(info.name);
            parcel.writeBundle(null);
            parcel.writeParcelable(info, 0);
            parcel.writeInt(0);
            parcel.setDataPosition(0);
            service = new BPackage.Service(parcel);
        } finally { parcel.recycle(); }
        service.owner = pkg;
        for (int i = 0; i < 2; i++) {
            PackageParser.ServiceIntentInfo raw = new PackageParser.ServiceIntentInfo(null);
            raw.addAction(ACTION);
            BPackage.ServiceIntentInfo filter = new BPackage.ServiceIntentInfo(raw);
            filter.service = service;
            service.intents.add(filter);
        }
        pkg.services.add(service);
        ComponentResolver resolver = new ComponentResolver();
        resolver.addAllComponents(pkg);
        assertEquals(1, resolver.queryServices(new Intent(ACTION), null, 0, 0).size());
        assertEquals(1, resolver.queryServices(new Intent(ACTION), null, 0, pkg.services, 0).size());
        resolver.removeAllComponents(pkg);
        assertTrue(resolver.queryServices(new Intent(ACTION), null, 0, 0).isEmpty());
    }

    private static BPackage fixture() {
        BPackage pkg = new BPackage(new PackageParser.Package("fixture.resolver"));
        pkg.applicationInfo.processName = pkg.packageName;
        pkg.baseCodePath = BlackBoxCore.getContext().getApplicationInfo().sourceDir;
        pkg.installOption = InstallOption.installByStorage();
        pkg.mExtras = new BPackageSettings();
        pkg.mExtras.pkg = pkg;
        pkg.mExtras.setInstalled(true, 0);
        return pkg;
    }

    private static BPackage.Activity activity(BPackage pkg, String name) {
        ActivityInfo info = new ActivityInfo();
        info.packageName = pkg.packageName;
        info.name = pkg.packageName + "." + name;
        info.applicationInfo = pkg.applicationInfo;
        Parcel parcel = Parcel.obtain();
        BPackage.Activity activity;
        try {
            parcel.writeString(info.name);
            parcel.writeBundle(null);
            parcel.writeParcelable(info, 0);
            parcel.writeInt(0);
            parcel.setDataPosition(0);
            activity = new BPackage.Activity(parcel);
        } finally { parcel.recycle(); }
        activity.owner = pkg;
        for (int i = 0; i < 2; i++) {
            PackageParser.ActivityIntentInfo raw = new PackageParser.ActivityIntentInfo(null);
            raw.addAction(ACTION);
            BPackage.ActivityIntentInfo filter = new BPackage.ActivityIntentInfo(raw);
            filter.activity = activity;
            activity.intents.add(filter);
        }
        return activity;
    }
}
