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
    static final String OWNER = "guest_owner";

    static ComponentName realComponent(android.content.pm.ActivityInfo info) {
        return new ComponentName(info.packageName, info.targetActivity == null ? info.name : info.targetActivity);
    }

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

    static boolean owned(Context host, Intent outer, String declaredClass) {
        try {
            if (!isStub(outer) || !host.getPackageName().equals(outer.getComponent().getPackageName())
                    || !declaredClass.equals(outer.getComponent().getClassName()) || outer.getSelector() != null || outer.getClipData() != null) return false;
            Bundle envelope = outer.getBundleExtra(EngineProtocol.LAUNCH);
            if (envelope == null || envelope.getInt("protocol") != EngineProtocol.VERSION) return false;
            int slot = envelope.getInt("slot", -1);
            if (!declaredClass.equals(GuestActivity.class.getName() + "$P" + slot)) return false;
            ComponentName component = new ComponentName(envelope.getString("package"), envelope.getString("activity"));
            return create(host, component, outer.getFlags(), envelope).filterEquals(outer);
        } catch (Exception invalid) { return false; }
    }

    static boolean recordDied(Bundle envelope) {
        return binderDied(envelope.getBinder(RECORD)) || binderDied(envelope.getBinder(OWNER));
    }

    private static boolean binderDied(IBinder record) {
        if (record == null) return false;
        android.os.Parcel input = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
        try { record.transact(IBinder.INTERFACE_TRANSACTION, input, reply, 0); return false; }
        catch (android.os.DeadObjectException dead) { return true; }
        catch (android.os.RemoteException unavailable) { throw new EngineException("Cannot verify Activity record death", unavailable); }
        finally { input.recycle(); reply.recycle(); }
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
