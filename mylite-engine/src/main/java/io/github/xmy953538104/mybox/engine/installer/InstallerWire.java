package io.github.xmy953538104.mybox.engine.installer;

import android.content.pm.PackageInstaller;
import android.os.Bundle;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Our own versioned transport; the runtime platform supplies its public parcelable layouts. */
final class InstallerWire {
    static final int VERSION = 1;
    private InstallerWire() { }

    static Bundle snapshot(SessionSnapshot value, boolean active) {
        if (value == null) return null;
        Bundle result = new Bundle(); result.putInt("id", value.id);
        result.putString("installer", value.installerPackage); result.putString("package", value.packageName);
        result.putString("label", value.label); result.putString("state", value.state);
        result.putByteArray("icon", value.icon);
        result.putLong("size", value.sizeBytes); result.putLong("created", value.createdAt);
        result.putFloat("progress", value.progress); result.putInt("status", value.status);
        result.putBoolean("sealed", value.sealed()); result.putBoolean("committed", value.committed());
        result.putBoolean("active", active); return result;
    }

    static PackageInstaller.SessionInfo info(Bundle value) throws ReflectiveOperationException {
        if (value == null) return null;
        Class<?> type = PackageInstaller.SessionInfo.class;
        PackageInstaller.SessionInfo result = (PackageInstaller.SessionInfo) type.getDeclaredConstructor().newInstance();
        set(result, "sessionId", value.getInt("id")); set(result, "installerPackageName", value.getString("installer"));
        set(result, "progress", value.getFloat("progress")); set(result, "sealed", value.getBoolean("sealed"));
        set(result, "active", value.getBoolean("active")); set(result, "mode", PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        set(result, "sizeBytes", value.getLong("size")); set(result, "appPackageName", value.getString("package"));
        set(result, "appLabel", value.getString("label"));
        byte[] icon = checkedIcon(value.getByteArray("icon"));
        set(result, "appIcon", icon == null ? null : android.graphics.BitmapFactory.decodeByteArray(icon, 0, icon.length));
        optional(result, "userId", 0); optional(result, "createdMillis", value.getLong("created"));
        optional(result, "updatedMillis", value.getLong("created")); optional(result, "isCommitted", value.getBoolean("committed"));
        optional(result, "isMultiPackage", false); optional(result, "isStaged", false);
        optional(result, "parentSessionId", -1); optional(result, "childSessionIds", new int[0]);
        return result;
    }

    static Object list(Bundle values) throws ReflectiveOperationException {
        ArrayList<Bundle> items = values.getParcelableArrayList("sessions");
        List<PackageInstaller.SessionInfo> sessions = new ArrayList<>();
        if (items != null) for (Bundle value : items) sessions.add(info(value));
        Class<?> type = Class.forName("android.content.pm.ParceledListSlice");
        return type.getDeclaredConstructor(List.class).newInstance(sessions);
    }

    static void params(Bundle target, PackageInstaller.SessionParams params) throws ReflectiveOperationException {
        if (params == null) throw new IllegalArgumentException("Installation parameters are required");
        if (((Integer) get(params, "mode")) != PackageInstaller.SessionParams.MODE_FULL_INSTALL
                || Boolean.TRUE.equals(optional(params, "isMultiPackage")) || Boolean.TRUE.equals(optional(params, "isStaged"))
                || optional(params, "dataLoaderParams") != null) {
            throw new UnsupportedOperationException("Only complete non-staged APK sessions are supported");
        }
        int flags = (Integer) get(params, "installFlags");
        // These are framework defaults / complete-app update hints. They do not grant host permissions.
        int allowed = constant("INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS") | constant("INSTALL_REPLACE_EXISTING")
                | constant("INSTALL_FULL_APP") | constant("INSTALL_DONT_KILL_APP")
                | constant("INSTALL_REQUEST_UPDATE_OWNERSHIP");
        // shortcut: ownership requests are declined; implement persisted enforcement before honoring them.
        if ((flags & ~allowed) != 0) throw new UnsupportedOperationException("Requested installer flags are not supported");
        if (optional(params, "grantedRuntimePermissions") != null) {
            throw new UnsupportedOperationException("Installation cannot grant runtime permissions");
        }
        Object permissionStates = optional(params, "mPermissionStates");
        if (permissionStates instanceof java.util.Map && !((java.util.Map<?, ?>) permissionStates).isEmpty()) {
            throw new UnsupportedOperationException("Installation permission-state changes are not supported");
        }
        Object requiredVersion = optional(params, "requiredInstalledVersionCode");
        if (requiredVersion != null && ((Number) requiredVersion).longValue() != ((Number) Class.forName("android.content.pm.PackageManager")
                .getDeclaredField("VERSION_CODE_HIGHEST").get(null)).longValue()) {
            throw new UnsupportedOperationException("Conditional installed-version sessions are not supported");
        }
        Object userAction = optional(params, "requireUserAction");
        if (userAction != null && userAction.equals(PackageInstaller.SessionParams.class.getDeclaredField("USER_ACTION_REQUIRED").get(null))) {
            throw new UnsupportedOperationException("Interactive installation approval is not implemented");
        }
        if (optional(params, "abiOverride") != null || optional(params, "volumeUuid") != null
                || ((Integer) get(params, "installLocation")) == android.content.pm.PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL
                || Boolean.TRUE.equals(optional(params, "applicationEnabledSettingPersistent"))) {
            throw new UnsupportedOperationException("Requested installation placement or enabled-state policy is not supported");
        }
        target.putString("package", (String) get(params, "appPackageName"));
        target.putLong("size", (Long) get(params, "sizeBytes"));
        Object label = get(params, "appLabel"); target.putString("label", label == null ? null : label.toString());
        target.putByteArray("icon", icon((android.graphics.Bitmap) get(params, "appIcon")));
    }

    static byte[] icon(android.graphics.Bitmap source) {
        if (source == null) return null;
        if (source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) throw new IllegalArgumentException("Invalid installation icon");
        float scale = Math.min(1f, 96f / Math.max(source.getWidth(), source.getHeight()));
        android.graphics.Bitmap bitmap = scale == 1f ? source : android.graphics.Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * scale)), Math.max(1, Math.round(source.getHeight() * scale)), true);
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalArgumentException("Installation icon encoding failed");
            return checkedIcon(output.toByteArray());
        } finally { if (bitmap != source) bitmap.recycle(); }
    }

    static byte[] checkedIcon(byte[] icon) {
        if (icon == null) return null;
        if (icon.length == 0 || icon.length > InstallRequest.MAX_ICON_BYTES) throw new IllegalArgumentException("Installation icon exceeds limit");
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(icon, 0, icon.length, bounds);
        if (!"image/png".equals(bounds.outMimeType) || bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 96 || bounds.outHeight > 96)
            throw new IllegalArgumentException("Invalid installation icon format or bounds");
        android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(icon, 0, icon.length);
        if (decoded == null) throw new IllegalArgumentException("Installation icon cannot be decoded");
        decoded.recycle(); return icon;
    }

    static RuntimeException io(IOException cause) {
        try {
            return (RuntimeException) Class.forName("android.os.ParcelableException").getConstructor(Throwable.class)
                    .newInstance(new IOException("Virtual installer file operation failed", cause));
        } catch (ReflectiveOperationException unavailable) {
            return new IllegalStateException("Platform IOException transport is unavailable", unavailable);
        }
    }

    static String required(Bundle input, String name) {
        String value = input.getString(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing installer field " + name);
        return value;
    }
    private static int constant(String name) throws ReflectiveOperationException {
        try { return Class.forName("android.content.pm.PackageManager").getDeclaredField(name).getInt(null); }
        catch (NoSuchFieldException absentOnOlderPlatform) { return 0; }
    }
    private static Object get(Object object, String name) throws ReflectiveOperationException { return field(object.getClass(), name).get(object); }
    private static Object optional(Object object, String name) throws ReflectiveOperationException {
        try { return get(object, name); } catch (NoSuchFieldException absentOnOlderPlatform) { return null; }
    }
    private static void set(Object object, String name, Object value) throws ReflectiveOperationException { field(object.getClass(), name).set(object, value); }
    private static void optional(Object object, String name, Object value) throws ReflectiveOperationException {
        try { set(object, name, value); } catch (NoSuchFieldException absentOnOlderPlatform) { /* Version-dependent metadata only. */ }
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException { Field value = type.getDeclaredField(name); value.setAccessible(true); return value; }
}
