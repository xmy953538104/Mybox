package io.github.xmy953538104.mybox.engine;

import android.os.Bundle;

/** Each manifest subclass starts a separate OS process and seals it to one guest identity. */
public class GuestBootstrapProvider extends EngineProvider {
    @Override public Bundle call(String method, String argument, Bundle input) {
        EngineProtocol.requireCaller();
        EngineProtocol.requireProcess(getContext(), getContext().getPackageName() + ":engine_server");
        if (!"prepare".equals(method)) throw new IllegalArgumentException("Unknown guest operation");
        try {
            return Engine.get().runtime().prepare(input);
        } catch (Exception error) {
            return EngineProtocol.failure(error);
        }
    }
    public static final class P0 extends GuestBootstrapProvider { }
    public static final class P1 extends GuestBootstrapProvider { }
    public static final class P2 extends GuestBootstrapProvider { }
    public static final class P3 extends GuestBootstrapProvider { }
}
