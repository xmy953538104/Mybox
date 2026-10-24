package io.github.xmy953538104.mybox.engine.installer;

import io.github.xmy953538104.mybox.engine.CallerIdentity;
import java.io.IOException;

/** Only approved real installer identities may create or commit virtual installation sessions. */
public interface InstallerAuthorization {
    void requireApproved(CallerIdentity caller) throws IOException;
}
