package io.github.xmy953538104.mybox.sessiontarget;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Test-only provider: persists a marker and proves a feature split's code and resources were loaded. */
public final class SessionTargetProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle state = new Bundle();
        if ("componentStateProbe".equals(method)) {
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            android.content.ComponentName receiver = new android.content.ComponentName(getContext(), ToggleReceiver.class);
            try {
                boolean disabledInManifest = !pm.getReceiverInfo(receiver, android.content.pm.PackageManager.GET_DISABLED_COMPONENTS).enabled;
                pm.setComponentEnabledSetting(receiver, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP);
                boolean enabled = pm.getComponentEnabledSetting(receiver) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        && pm.getReceiverInfo(receiver, 0).enabled;
                state.putBoolean("passed", disabledInManifest && enabled);
            } catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
            finally { pm.setComponentEnabledSetting(receiver, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    android.content.pm.PackageManager.DONT_KILL_APP); }
            return state;
        }
        if ("tryOpenSession".equals(method)) {
            try (android.content.pm.PackageInstaller.Session session = getContext().getPackageManager()
                    .getPackageInstaller().openSession(Integer.parseInt(arg))) {
                state.putBoolean("denied", false);
            } catch (SecurityException expected) {
                state.putBoolean("denied", true);
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
            return state;
        }
        if ("writeMarker".equals(method)) {
            if (!getContext().getSharedPreferences("fixture", 0).edit().putString("marker", arg).commit()) {
                throw new IllegalStateException("Cannot save marker");
            }
        }
        state.putString("marker", getContext().getSharedPreferences("fixture", 0).getString("marker", null));
        try {
            state.putInt("version", getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionCode);
            Class<?> featureClass = getContext().getClassLoader().loadClass("io.github.xmy953538104.mybox.sessionfeature.FeatureMarker");
            Object feature = featureClass.getDeclaredMethod("value").invoke(null);
            state.putBoolean("featureCode", "feature-dex-loaded".equals(feature));
            Object resource = featureClass.getDeclaredMethod("resourceValue", android.content.Context.class).invoke(null, getContext());
            state.putBoolean("featureResources", "feature-resources-loaded".equals(resource));
        } catch (Exception e) { state.putString("error", e.toString()); }
        return state;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
