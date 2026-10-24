package io.github.xmy953538104.mybox.engine;

import android.app.Activity;
import android.os.Bundle;
import android.content.ComponentName;
import android.content.Intent;

/** Declared OS entry only; LaunchInterceptor substitutes the actual guest before construction. */
public class GuestActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Engine engine = Engine.get();
        if (ownedEnvelope(engine, getIntent(), getClass().getName()) && engine.runtime().discardUnboundActivity()) {
            // Recents can recreate an old physical stub before the new guest has bound.
            android.util.Log.w("MyliteActivities", "Discarding an expired physical Activity in an unbound guest process");
            if (isTaskRoot()) finishAndRemoveTask(); else finish();
            android.widget.Toast.makeText(getApplicationContext(), "应用进程已退出，请从 Mybox 重新打开。", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        throw new EngineException("Guest launch was not intercepted; no guest Activity was started");
    }
    private static boolean ownedEnvelope(Engine engine, Intent outer, String declaredClass) {
        try {
            if (!ActivityEnvelope.isStub(outer) || !engine.hostContext().getPackageName().equals(outer.getComponent().getPackageName())
                    || !declaredClass.equals(outer.getComponent().getClassName()) || outer.getSelector() != null || outer.getClipData() != null) return false;
            Bundle envelope = outer.getBundleExtra(EngineProtocol.LAUNCH);
            if (envelope == null || envelope.getInt("protocol") != EngineProtocol.VERSION) return false;
            int slot = envelope.getInt("slot", -1);
            if (!declaredClass.equals(GuestActivity.class.getName() + "$P" + slot)) return false;
            ComponentName component = new ComponentName(envelope.getString("package"), envelope.getString("activity"));
            return ActivityEnvelope.create(engine.hostContext(), component, outer.getFlags(), envelope).filterEquals(outer);
        } catch (Exception invalid) { return false; }
    }
    public static final class P0 extends GuestActivity { }
    public static final class P1 extends GuestActivity { }
    public static final class P2 extends GuestActivity { }
    public static final class P3 extends GuestActivity { }
}
