package io.github.xmy953538104.mybox.engine.accounts;

import android.content.Intent;
import io.github.xmy953538104.mybox.engine.CallerIdentity;
import java.io.IOException;

/** Server-local dependency; guests cannot issue their own UI grants. */
public interface AccountUiAuthorizer {
    Scope begin(CallerIdentity consumer, AuthenticatorRegistration authenticator,
                String operation, boolean expectActivity) throws IOException;
    interface Scope {
        Intent publish(CallerIdentity owner, Intent validated) throws IOException;
        void close(boolean retainReturnedIntent);
    }
}
