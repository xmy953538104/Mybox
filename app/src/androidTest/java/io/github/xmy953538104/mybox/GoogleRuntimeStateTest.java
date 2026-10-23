package io.github.xmy953538104.mybox;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import java.util.ArrayList;
import java.util.List;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.frameworks.BPackageManager;

/** Read-only diagnosis: no guest launch, component changes, or account access. */
public final class GoogleRuntimeStateTest extends InstrumentationTestCase {
    public void testMicrogSettingsResolution() {
        ((MyboxApplication) getInstrumentation().getTargetContext().getApplicationContext()).requireEngine();
        BPackageManager pm = BlackBoxCore.getBPackageManager();
        String pkg = "com.google.android.gms";
        ComponentName settings = new ComponentName(pkg, "org.microg.gms.ui.MainSettingsActivity");
        Intent preferences = new Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(pkg);
        PackageInfo installed = pm.getPackageInfo(pkg, 0, 0);
        ActivityInfo explicit = pm.getActivityInfo(settings, PackageManager.GET_DISABLED_COMPONENTS, 0);
        List<ResolveInfo> packageResults = pm.queryIntentActivities(preferences, 0, null, 0);
        List<ResolveInfo> globalResults = pm.queryIntentActivities(
                new Intent(Intent.ACTION_APPLICATION_PREFERENCES), 0, null, 0);
        ResolveInfo resolved = pm.resolveActivity(preferences, 0, null, 0);
        String snapshot = "installed=" + (installed == null ? "missing" : installed.versionCode)
                + " explicit=" + (explicit == null ? "missing" : explicit.name + "/enabled=" + explicit.enabled)
                + " state=" + (explicit == null ? "unknown" : pm.getComponentEnabledSetting(settings, 0))
                + " package=" + describe(packageResults, pkg)
                + " indexed=" + describe(globalResults, pkg)
                + " resolved=" + (resolved == null ? "null" : resolved.activityInfo.name);
        Bundle status = new Bundle();
        status.putString("stream", "microG resolver: " + snapshot + "\n");
        getInstrumentation().sendStatus(0, status);
        assertNotNull(snapshot, installed);
        assertNotNull(snapshot, explicit);
        assertTrue(snapshot, explicit.enabled);
        assertEquals(snapshot, 1, describe(packageResults, pkg).size());
        assertEquals(snapshot, describe(packageResults, pkg), describe(globalResults, pkg));
        assertNotNull(snapshot, resolved);
        assertEquals(snapshot, settings.getClassName(), resolved.activityInfo.name);
    }

    private static List<String> describe(List<ResolveInfo> results, String pkg) {
        List<String> descriptions = new ArrayList<>();
        if (results != null) for (ResolveInfo result : results) {
            ActivityInfo activity = result.activityInfo;
            if (activity != null && pkg.equals(activity.packageName)) {
                descriptions.add(activity.name + "/enabled=" + activity.enabled + "/priority=" + result.priority);
            }
        }
        return descriptions;
    }
}
