package io.github.xmy953538104.mybox.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Assigned by the server from the calling Binder PID, never from caller-supplied package text. */
public final class CallerIdentity {
    public final String packageName;
    public final int userId = 0;
    public final int appId;
    public final int pid;
    public final List<String> certificateSha256;

    CallerIdentity(String packageName, int appId, int pid, List<String> certificates) {
        this.packageName = packageName;
        this.appId = appId;
        this.pid = pid;
        certificateSha256 = Collections.unmodifiableList(new ArrayList<>(certificates));
    }
}
