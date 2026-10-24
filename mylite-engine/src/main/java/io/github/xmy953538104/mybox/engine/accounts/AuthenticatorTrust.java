package io.github.xmy953538104.mybox.engine.accounts;

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.IOException;

/** Uses the engine's same-run APK and real certificate pins, never the compatibility signature. */
public interface AuthenticatorTrust {
    boolean isApprovedGoogleAuthenticator(InstalledPackage installed) throws IOException;
}
