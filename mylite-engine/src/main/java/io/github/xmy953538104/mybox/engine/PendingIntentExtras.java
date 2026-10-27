package io.github.xmy953538104.mybox.engine;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;

/** Keep creator-private Parcelable classes out of the server's durable primitive codec. */
final class PendingIntentExtras {
    static final String BYTES = "io.github.xmy953538104.mybox.pending.extras.v1";
    static final String PLATFORM = BYTES + ".platform";
    static final String SCHEMA = BYTES + ".schema";

    static Intent pack(Intent source, int flags) {
        Intent copy = new Intent(source);
        if ((flags & PendingIntent.FLAG_IMMUTABLE) == 0 || source.getExtras() == null) return copy;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(source.getExtras());
            if (parcel.hasFileDescriptors() || parcel.dataSize() > OsPendingStore.MAX_PAYLOAD) return copy;
            byte[] bytes;
            try { bytes = parcel.marshall(); }
            catch (RuntimeException binderObjects) { return copy; }
            Bundle frame = new Bundle(); frame.putByteArray(BYTES, bytes);
            frame.putString(PLATFORM, Build.FINGERPRINT); frame.putInt(SCHEMA, 1);
            return copy.replaceExtras(frame);
        } finally { parcel.recycle(); }
    }

    static void requireCompatible(Intent intent) {
        if (intent == null || !intent.hasExtra(BYTES)) return;
        byte[] bytes = intent.getByteArrayExtra(BYTES);
        if (intent.getIntExtra(SCHEMA, -1) != 1 || !Build.FINGERPRINT.equals(intent.getStringExtra(PLATFORM))
                || bytes == null || bytes.length < 4 || bytes.length > OsPendingStore.MAX_PAYLOAD) {
            throw new SecurityException("PendingIntent payload schema or Android build changed");
        }
    }

    static void restore(Intent intent, ClassLoader loader) {
        if (intent == null) return;
        intent.setExtrasClassLoader(loader);
        if (!intent.hasExtra(BYTES)) return;
        requireCompatible(intent);
        Bundle frame = intent.getExtras(); byte[] bytes = frame.getByteArray(BYTES);
        frame.remove(BYTES); frame.remove(PLATFORM); frame.remove(SCHEMA);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(bytes, 0, bytes.length); parcel.setDataPosition(0);
            Bundle restored = parcel.readBundle(loader);
            if (parcel.dataAvail() != 0 || restored == null) throw new IllegalArgumentException("Invalid PendingIntent extras frame");
            if (!frame.isEmpty()) {
                if (loader == null) throw new IllegalArgumentException("Guest loader is required for PendingIntent fill-in extras");
                frame.putAll(restored); restored = frame;
            }
            intent.replaceExtras(restored); intent.setExtrasClassLoader(loader);
        } finally { parcel.recycle(); }
    }

    // shortcut: private durable extras are immutable, object-free and Android-build scoped; extend before mutable/Binder payloads.
    private PendingIntentExtras() { }
}
