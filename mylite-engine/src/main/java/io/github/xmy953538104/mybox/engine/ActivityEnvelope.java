package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** The physical Intent identifies a guest component; its payload is an opaque server record. */
final class ActivityEnvelope {
    static final String RECORD = "activity_record";

    static Intent create(Context host, ComponentName component, int flags, Bundle identity) {
        int slot = identity.getInt("slot", -1);
        if (slot < 0 || slot >= EngineProtocol.SLOT_COUNT || identity.getBinder(RECORD) == null) {
            throw new EngineException("Activity reservation is incomplete");
        }
        String committed = identity.getString("package_generation");
        String revision = UUID.nameUUIDFromBytes(committed.getBytes(StandardCharsets.UTF_8)).toString();
        Intent outer = new Intent().setComponent(new ComponentName(host,
                GuestActivity.class.getName() + "$P" + slot));
        outer.setDataAndType(new Uri.Builder().scheme("mylite").authority("activity")
                .appendPath("0").appendPath(revision).appendPath(Long.toString(identity.getLong("generation")))
                .appendPath(component.flattenToString()).build(),
                component.flattenToString());
        outer.setFlags(flags);
        outer.putExtra(EngineProtocol.LAUNCH, new Bundle(identity));
        return outer;
    }

    static boolean isStub(Intent intent) {
        if (intent == null || intent.getComponent() == null) return false;
        String name = intent.getComponent().getClassName();
        for (int slot = 0; slot < EngineProtocol.SLOT_COUNT; slot++) {
            if (name.equals(GuestActivity.class.getName() + "$P" + slot)) return true;
        }
        return false;
    }

    static Bundle operation(Bundle envelope, String operation, IBinder token) {
        if (envelope == null || envelope.getInt("protocol") != EngineProtocol.VERSION
                || envelope.getBinder(RECORD) == null) throw new SecurityException("Missing Activity record");
        Bundle request = new Bundle(envelope);
        request.putString("operation", operation);
        if (token != null) request.putBinder("token", token);
        return request;
    }

    private ActivityEnvelope() { }
}
