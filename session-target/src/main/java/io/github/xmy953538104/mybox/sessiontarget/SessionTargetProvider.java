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
        if ("writeMarker".equals(method)) {
            if (!getContext().getSharedPreferences("fixture", 0).edit().putString("marker", arg).commit()) {
                throw new IllegalStateException("Cannot save marker");
            }
        }
        state.putString("marker", getContext().getSharedPreferences("fixture", 0).getString("marker", null));
        try {
            state.putInt("version", getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionCode);
            Object feature = getContext().getClassLoader().loadClass("io.github.xmy953538104.mybox.sessionfeature.FeatureMarker")
                    .getDeclaredMethod("value").invoke(null);
            state.putBoolean("featureCode", "feature-dex-loaded".equals(feature));
            int resource = getContext().getResources().getIdentifier("session_feature_marker", "string", getContext().getPackageName());
            state.putBoolean("featureResources", resource != 0 && "feature-resources-loaded".equals(getContext().getString(resource)));
        } catch (Exception e) { state.putString("error", e.toString()); }
        return state;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
