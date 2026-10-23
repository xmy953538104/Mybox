package io.github.xmy953538104.mybox.sessionfeature;
public final class FeatureMarker {
    public static String value() { return "feature-dex-loaded"; }
    public static String resourceValue(android.content.Context context) {
        return context.getString(R.string.session_feature_marker);
    }
}
