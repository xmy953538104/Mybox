package io.github.xmy953538104.mybox.engine.installer;

import io.github.xmy953538104.mybox.engine.CallerIdentity;

/** Resolves the actual synchronous Binder caller through the engine's live process registry. */
public interface InstallerCallerResolver {
    CallerIdentity resolve(String claimedPackage);
}
