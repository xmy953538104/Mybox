package io.github.xmy953538104.mybox.engine.installer;

import android.content.Intent;
import android.content.IntentSender;
import io.github.xmy953538104.mybox.engine.CallerIdentity;

/** The server validates a virtual callback capability before sealing or publishing a session. */
public interface InstallerStatusDispatcher {
    Delivery prepare(CallerIdentity owner, IntentSender receiver);

    interface Delivery {
        void send(Intent result) throws Exception;
    }
}
