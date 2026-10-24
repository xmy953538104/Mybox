package io.github.xmy953538104.mybox.engine.accounts;

import java.util.Set;

/** Enqueue package-scoped virtual notifications without account names or credentials. */
public interface AccountNotifier {
    /** False until package-scoped virtual receiver delivery is installed. */
    default boolean supportsListeners() { return true; }
    void notifyAccountsChanged(int userId, Set<String> packages);
}
