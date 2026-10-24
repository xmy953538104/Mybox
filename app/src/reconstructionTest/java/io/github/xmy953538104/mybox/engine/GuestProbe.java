package io.github.xmy953538104.mybox.engine;

import android.content.pm.ActivityInfo;
import android.os.Bundle;

/** Test-only inspection through the same authenticated process broker used by launch. */
public final class GuestProbe {
    public static Bundle describe(String packageName) throws Exception {
        Engine engine = Engine.get();
        ActivityInfo launch = engine.packages().findLaunchActivity(packageName);
        if (launch == null) throw new IllegalStateException("Guest launcher is absent");
        Bundle request = EngineProtocol.response();
        request.putString("package", packageName);
        request.putString("process", launch.processName == null ? packageName : launch.processName);
        Bundle process = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.ACQUIRE, request);
        Bundle actual = EngineProtocol.request(process.getBinder("client"), EngineProtocol.GUEST,
                EngineProtocol.DESCRIBE, EngineProtocol.response());
        if (actual.getInt("pid") != process.getInt("pid") || actual.getLong("generation") != process.getLong("generation")) {
            throw new SecurityException("Guest inspection does not match its registered process");
        }
        return actual;
    }
    private GuestProbe() { }
}
