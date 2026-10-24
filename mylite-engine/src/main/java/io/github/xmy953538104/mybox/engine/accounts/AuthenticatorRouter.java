package io.github.xmy953538104.mybox.engine.accounts;

import android.content.ComponentName;
import android.os.IBinder;

/** An internal server binding to an installed virtual authenticator, never a system fallback. */
public interface AuthenticatorRouter {
    Binding bind(ComponentName component, int userId, Connection connection) throws Exception;
    interface Binding { void close(); }
    interface Connection {
        void onConnected(IBinder authenticator);
        void onDisconnected();
    }
}
