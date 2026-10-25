package io.github.xmy953538104.mybox.engine;

import android.app.Activity;
import android.os.Bundle;

/** Declared OS entry only; LaunchInterceptor substitutes the actual guest before construction. */
public class GuestActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        Engine engine = Engine.get();
        boolean expired = engine.runtime().discardPhysicalActivity(getIntent(), getClass().getName());
        // An expired physical stub cannot restore guest Fragment classes using the host class loader.
        super.onCreate(expired ? null : state);
        if (expired) {
            android.util.Log.w("MyliteActivities", "Discarding an expired physical Activity without executing guest code");
            engine.runtime().activities().finishDiscarded(this);
            android.widget.Toast.makeText(getApplicationContext(), "应用进程已退出，请从 Mybox 重新打开。", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        throw new EngineException("Guest launch was not intercepted; no guest Activity was started");
    }
    public static final class P0 extends GuestActivity { }
    public static final class P1 extends GuestActivity { }
    public static final class P2 extends GuestActivity { }
    public static final class P3 extends GuestActivity { }
    public static final class P4 extends GuestActivity { }
    public static final class P5 extends GuestActivity { }
    public static final class P6 extends GuestActivity { }
    public static final class P7 extends GuestActivity { }
}
