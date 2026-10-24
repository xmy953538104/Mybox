package io.github.xmy953538104.mybox.engine.accounts;

import io.github.xmy953538104.mybox.engine.CallerIdentity;

/** Must resolve the current synchronous Binder caller using the server's process registry. */
public interface CallerResolver {
    CallerIdentity resolve(String claimedOpPackage);
}
