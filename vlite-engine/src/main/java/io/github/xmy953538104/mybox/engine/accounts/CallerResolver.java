package io.github.xmy953538104.mybox.engine.accounts;

import io.github.xmy953538104.mybox.engine.CallerIdentity;

/** Must resolve the current synchronous Binder caller using the server's process registry. */
public interface CallerResolver {
    /** Internal relay checks; implementations must use the same live server process registry. */
    default io.github.xmy953538104.mybox.engine.CallerIdentity resolvePeer(int realUid, int realPid) {
        throw new SecurityException("Peer identity resolution is unavailable");
    }
    CallerIdentity resolve(String claimedOpPackage);
}
