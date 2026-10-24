package io.github.xmy953538104.mybox.engine;

import java.util.List;

final class ContentObserverPolicy {
    static boolean matches(List<String> registered, List<String> changed, boolean descendants, int flags) {
        int shared = Math.min(registered.size(), changed.size());
        for (int index = 0; index < shared; index++) if (!registered.get(index).equals(changed.get(index))) return false;
        if (registered.size() < changed.size()) return descendants;
        return !descendants || (flags & 2) == 0;
    }
    private ContentObserverPolicy() { }
}
