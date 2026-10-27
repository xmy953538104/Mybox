package io.github.xmy953538104.mybox.fixture.pending;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Deliberately absent from the host/server class path, like Play's protobuf metadata. */
public final class NotificationMetadata implements Parcelable {
    public final String value;
    public NotificationMetadata(String value) { this.value = value; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeString(value); }
    public static final Creator<NotificationMetadata> CREATOR = new Creator<NotificationMetadata>() {
        @Override public NotificationMetadata createFromParcel(Parcel in) { return new NotificationMetadata(in.readString()); }
        @Override public NotificationMetadata[] newArray(int size) { return new NotificationMetadata[size]; }
    };
    public static Bundle extras(String run) {
        Bundle out = new Bundle(); out.putParcelable("guest-metadata", new NotificationMetadata(run)); return out;
    }
    public static Intent intent(Intent target, String run) {
        Bundle nested = new Bundle(); nested.putBundle("nested", extras(run));
        return target.putExtra("guest-intent-metadata", nested);
    }
    public static void requireIntent(Intent intent, String run) {
        Bundle nested = intent.getBundleExtra("guest-intent-metadata");
        if (nested == null) throw new AssertionError("PendingIntent lost guest extras");
        nested.setClassLoader(NotificationMetadata.class.getClassLoader());
        Bundle metadata = nested.getBundle("nested");
        if (metadata == null) throw new AssertionError("PendingIntent lost nested metadata");
        metadata.setClassLoader(NotificationMetadata.class.getClassLoader());
        NotificationMetadata value = metadata.getParcelable("guest-metadata");
        if (value == null || !run.equals(value.value)) throw new AssertionError("PendingIntent guest metadata changed");
    }
    public static void require(Notification notification, String run) {
        if (notification.actions == null || notification.actions.length != 1) throw new AssertionError("Missing metadata action");
        Bundle extras = notification.actions[0].getExtras(); extras.setClassLoader(NotificationMetadata.class.getClassLoader());
        NotificationMetadata value = extras.getParcelable("guest-metadata");
        if (value == null || !run.equals(value.value)) throw new AssertionError("Guest metadata did not survive Android notification delivery");
    }
    public static void requirePosted(NotificationManager manager, int id, String tag, String run) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        do {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                if (item.getId() == id && java.util.Objects.equals(tag, item.getTag())) {
                    require(item.getNotification(), run); return;
                }
            }
            Thread.sleep(25);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Posted notification was not visible to its guest");
    }
}
