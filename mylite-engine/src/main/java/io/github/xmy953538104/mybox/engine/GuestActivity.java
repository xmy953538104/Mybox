package io.github.xmy953538104.mybox.engine;

import android.app.Activity;
import android.os.Bundle;

/** Declared OS entry only; LaunchInterceptor substitutes the actual guest before construction. */
public class GuestActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        throw new EngineException("Guest launch was not intercepted; no guest Activity was started");
    }
    public static final class P0 extends GuestActivity { }
    public static final class P1 extends GuestActivity { }
    public static final class P2 extends GuestActivity { }
    public static final class P3 extends GuestActivity { }
}
