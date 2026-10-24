package io.github.xmy953538104.mybox.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.service.notification.StatusBarNotification;
import android.util.AtomicFile;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Owns the physical namespace; every request is authenticated from its real Binder PID. */
final class ServerNotifications extends Binder {
    static final String DESCRIPTOR = "io.github.xmy953538104.mybox.engine.Notifications.v1";
    private final Engine engine;
    private final NotificationManager manager;
    private final AtomicFile file;
    private final Properties ids = new Properties();
    private int nextId = 0x60000001;

    ServerNotifications(Engine engine) throws IOException {
        this.engine = engine;
        manager = engine.hostContext().getSystemService(NotificationManager.class);
        file = new AtomicFile(new File(engine.hostContext().getFilesDir(), "mylite-notification-ids.properties"));
        if (file.getBaseFile().exists()) {
            try (java.io.InputStream input = file.openRead()) { ids.load(input); }
            try { nextId = Integer.parseInt(ids.getProperty("next", "1610612737")); }
            catch (NumberFormatException bad) { throw new IOException("Invalid notification namespace", bad); }
            if (nextId < 0x60000001 || nextId > 0x6fffffff) throw new IOException("Invalid notification ID counter");
        }
        attachInterface(null, DESCRIPTOR);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        if (code != FIRST_CALL_TRANSACTION) return false;
        data.enforceInterface(DESCRIPTOR);
        try {
            CallerIdentity caller = EngineServerProvider.resolveCaller();
            Bundle input = data.readBundle(getClass().getClassLoader());
            if (input == null || !caller.packageName.equals(input.getString("package"))) {
                throw new SecurityException("Notification owner differs from the registered process");
            }
            long identity = Binder.clearCallingIdentity();
            Bundle result;
            try { synchronized (this) { result = route(input, caller); } }
            finally { Binder.restoreCallingIdentity(identity); }
            reply.writeNoException(); reply.writeBundle(result);
        } catch (Exception failure) {
            reply.writeException(failure instanceof RuntimeException ? failure
                    : new IllegalStateException("Notification operation failed", failure));
        }
        return true;
    }

    @android.annotation.SuppressLint("MissingPermission") // Android applies the host's actual user notification permission.
    private Bundle route(Bundle input, CallerIdentity caller) throws Exception {
        String owner = owner(caller);
        String operation = input.getString("operation");
        Bundle out = EngineProtocol.response();
        if ("createChannels".equals(operation)) {
            ArrayList<NotificationChannel> channels = input.getParcelableArrayList("channels");
            if (channels == null || channels.size() > 100) throw new IllegalArgumentException("Invalid channel batch");
            for (NotificationChannel channel : channels) manager.createNotificationChannel(channel(owner, channel, false));
        } else if ("getChannel".equals(operation)) {
            NotificationChannel channel = manager.getNotificationChannel(name(owner, input.getString("id")));
            out.putParcelable("channel", channel == null ? null : channel(owner, channel, true));
        } else if ("channels".equals(operation)) {
            ArrayList<NotificationChannel> channels = new ArrayList<>();
            for (NotificationChannel channel : manager.getNotificationChannels()) {
                if (belongs(owner, channel.getId())) channels.add(channel(owner, channel, true));
            }
            out.putParcelableArrayList("channels", channels);
        } else if ("deleteChannel".equals(operation)) manager.deleteNotificationChannel(name(owner, input.getString("id")));
        else if ("createGroups".equals(operation)) {
            ArrayList<NotificationChannelGroup> groups = input.getParcelableArrayList("groups");
            if (groups == null || groups.size() > 100) throw new IllegalArgumentException("Invalid channel group batch");
            for (NotificationChannelGroup group : groups) manager.createNotificationChannelGroup(group(owner, group, false));
        } else if ("groups".equals(operation) || "getGroup".equals(operation)) {
            ArrayList<NotificationChannelGroup> groups = new ArrayList<>();
            for (NotificationChannelGroup group : manager.getNotificationChannelGroups()) {
                if (belongs(owner, group.getId())) {
                    NotificationChannelGroup copy = group(owner, group, true);
                    if ("getGroup".equals(operation) && copy.getId().equals(input.getString("id"))) out.putParcelable("group", copy);
                    groups.add(copy);
                }
            }
            out.putParcelableArrayList("groups", groups);
        } else if ("deleteGroup".equals(operation)) manager.deleteNotificationChannelGroup(name(owner, input.getString("id")));
        else if ("enabled".equals(operation)) out.putBoolean("enabled", manager.areNotificationsEnabled());
        else if ("importance".equals(operation)) out.putInt("importance", manager.getImportance());
        else if ("prepare".equals(operation) || "notify".equals(operation)) {
            int original = input.getInt("id");
            boolean foreground = "prepare".equals(operation);
            if (foreground && original == 0) throw new IllegalArgumentException("Foreground notification ID cannot be zero");
            String tag = input.getString("tag");
            if (foreground && tag != null) throw new IllegalArgumentException("Foreground notifications do not have tags");
            int id = id(owner, tag, original, true);
            Notification notification = input.getParcelable("notification");
            requireNormalized(notification);
            notification = ForegroundNotifications.copy(engine.hostContext(), engine.hostContext(), notification);
            String channel = name(owner, notification.getChannelId());
            if (manager.getNotificationChannel(channel) == null) throw new IllegalArgumentException("Notification channel has not been created");
            RuntimeReflection.set(notification, "mChannelId", channel);
            if (notification.getGroup() != null) RuntimeReflection.set(notification, "mGroupKey", name(owner, notification.getGroup()));
            if (notification.publicVersion != null) RuntimeReflection.set(notification.publicVersion, "mChannelId", channel);
            out.putInt("id", id); out.putParcelable("notification", notification);
            if (!foreground) manager.notify(id, notification);
        } else if ("cancel".equals(operation)) {
            int id = id(owner, input.getString("tag"), input.getInt("id"), false);
            if (id != 0) manager.cancel(id);
        } else if ("cancelAll".equals(operation)) {
            for (String key : ids.stringPropertyNames()) if (key.startsWith(owner + ".")) manager.cancel(Integer.parseInt(ids.getProperty(key)));
        } else if ("active".equals(operation)) {
            Map<Integer, String> reverse = new HashMap<>();
            for (String key : ids.stringPropertyNames()) if (key.startsWith(owner + ".")) reverse.put(Integer.parseInt(ids.getProperty(key)), key);
            ArrayList<StatusBarNotification> active = new ArrayList<>();
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                String key = reverse.get(posted.getId());
                if (key == null || posted.getTag() != null) continue;
                String suffix = key.substring(owner.length() + 1);
                int split = suffix.lastIndexOf('.');
                String tag = suffix.substring(0, split);
                Notification notification = posted.getNotification().clone();
                RuntimeReflection.set(notification, "mChannelId", original(owner, notification.getChannelId()));
                if (notification.getGroup() != null) RuntimeReflection.set(notification, "mGroupKey", original(owner, notification.getGroup()));
                active.add(new StatusBarNotification(caller.packageName, caller.packageName,
                        Integer.parseInt(suffix.substring(split + 1)), tag.equals("N") ? null : decode(tag.substring(1)),
                        caller.appId, caller.pid, 0, notification, posted.getUser(), posted.getPostTime()));
            }
            out.putParcelableArrayList("active", active);
        } else throw new UnsupportedOperationException("Notification operation is not implemented: " + operation);
        return out;
    }

    private static void requireNormalized(Notification notification) {
        if (notification == null || notification.getSmallIcon() == null) throw new IllegalArgumentException("A notification with an icon is required");
        if (notification.contentView != null || notification.bigContentView != null || notification.headsUpContentView != null
                || notification.tickerView != null || notification.contentIntent != null || notification.deleteIntent != null
                || notification.fullScreenIntent != null || (notification.actions != null && notification.actions.length != 0)) {
            throw new UnsupportedOperationException("Custom notification views and interaction capabilities are not implemented");
        }
    }

    private NotificationChannel channel(String owner, NotificationChannel source, boolean reverse) throws Exception {
        if (source == null) throw new IllegalArgumentException("Null channel");
        Parcel parcel = Parcel.obtain();
        NotificationChannel copy;
        try { source.writeToParcel(parcel, 0); parcel.setDataPosition(0); copy = NotificationChannel.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
        if (!reverse && copy.getSound() != null && !("content".equals(copy.getSound().getScheme())
                && "settings".equals(copy.getSound().getAuthority()))) {
            throw new UnsupportedOperationException("Guest channel sound URI forwarding is not implemented");
        }
        RuntimeReflection.set(copy, "mId", reverse ? original(owner, copy.getId()) : name(owner, copy.getId()));
        if (copy.getGroup() != null) copy.setGroup(reverse ? original(owner, copy.getGroup()) : name(owner, copy.getGroup()));
        if (android.os.Build.VERSION.SDK_INT >= 30 && copy.getParentChannelId() != null) {
            copy.setConversationId(reverse ? original(owner, copy.getParentChannelId()) : name(owner, copy.getParentChannelId()), copy.getConversationId());
        }
        return copy;
    }

    private NotificationChannelGroup group(String owner, NotificationChannelGroup source, boolean reverse) throws Exception {
        if (source == null) throw new IllegalArgumentException("Null notification channel group");
        Parcel parcel = Parcel.obtain();
        NotificationChannelGroup copy;
        try { source.writeToParcel(parcel, 0); parcel.setDataPosition(0); copy = NotificationChannelGroup.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
        RuntimeReflection.set(copy, "mId", reverse ? original(owner, source.getId()) : name(owner, source.getId()));
        List<NotificationChannel> children = copy.getChannels();
        for (int i = 0; i < children.size(); i++) children.set(i, channel(owner, children.get(i), reverse));
        return copy;
    }

    private int id(String owner, String tag, int id, boolean create) throws IOException {
        if (tag != null && tag.length() > 512) throw new IllegalArgumentException("Notification tag is too long");
        String key = owner + "." + (tag == null ? "N" : "S" + encode(tag)) + "." + id;
        String current = ids.getProperty(key);
        if (current != null) return Integer.parseInt(current);
        if (!create) return 0;
        if (ids.size() >= 32768) throw new IllegalStateException("Persistent notification identity capacity is exhausted");
        if (nextId >= 0x6fffffff) throw new IllegalStateException("Notification namespace exhausted");
        int allocated = nextId++;
        ids.setProperty(key, Integer.toString(allocated)); ids.setProperty("next", Integer.toString(nextId));
        FileOutputStream output = null;
        try { output = file.startWrite(); ids.store(output, "Mylite notification IDs"); file.finishWrite(output); }
        catch (IOException failure) { if (output != null) file.failWrite(output); ids.remove(key); nextId = allocated; ids.setProperty("next", Integer.toString(nextId)); throw failure; }
        return allocated;
    }

    private static String owner(CallerIdentity caller) {
        ArrayList<String> certs = new ArrayList<>(caller.certificateSha256);
        java.util.Collections.sort(certs);
        if (certs.isEmpty()) throw new SecurityException("Notification owner has no verified certificate");
        if (caller.userId != 0) throw new SecurityException("Only virtual user 0 is supported");
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(String.join(",", certs).getBytes(StandardCharsets.UTF_8));
            return "mylite0_" + encode(caller.packageName) + "_" + Base64.encodeToString(digest, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String name(String owner, String value) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 256) throw new IllegalArgumentException("Invalid notification channel/group ID");
        return owner + ":" + encode(value);
    }
    private static boolean belongs(String owner, String value) { return value != null && value.startsWith(owner + ":"); }
    private static String original(String owner, String value) {
        if (!belongs(owner, value)) throw new SecurityException("Notification channel belongs to another package");
        return decode(value.substring(owner.length() + 1));
    }
    private static String encode(String value) { return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE); }
    private static String decode(String value) { return new String(Base64.decode(value, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE), StandardCharsets.UTF_8); }
}
