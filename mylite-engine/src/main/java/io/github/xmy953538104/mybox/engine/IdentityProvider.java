package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Service-side bridge for the source-built microG caller-identity patch. */
public final class IdentityProvider extends EngineProvider {
    @Override public Bundle call(String method, String argument, Bundle input) {
        EngineProtocol.requireCaller();
        IdentityRpcStats.Ticket timing = IdentityRpcStats.GLOBAL.enter(Binder.getCallingPid(), android.os.SystemClock.elapsedRealtime());
        try {
            return resolve(method, input);
        } finally {
            IdentityRpcStats.GLOBAL.exit(timing, android.os.SystemClock.elapsedRealtime());
        }
    }

    static void logDiagnostics() {
        String snapshot = IdentityRpcStats.GLOBAL.snapshot(android.os.SystemClock.elapsedRealtime());
        if (snapshot != null) android.util.Log.i("MyliteIdentity", "processPid=" + Process.myPid() + " " + snapshot);
    }

    private Bundle resolve(String method, Bundle input) {
        if (!"resolveCallingIdentity".equals(method) || input == null) throw new IllegalArgumentException("Invalid identity request");
        if (input.getInt("originalUid", -1) != Process.myUid()) throw new SecurityException("Caller is outside this host UID");
        int originalPid = input.getInt("originalPid", -1);
        if (originalPid <= 0) throw new SecurityException("Caller PID is unavailable");
        CallerIdentity querying = EngineServerProvider.resolveCaller();
        try {
            InstalledPackage service = Engine.get().packages().get(querying.packageName);
            TrustedMicrog.require(getContext(), service);
        } catch (java.io.IOException failure) { throw new SecurityException("Service trust could not be verified", failure); }
        Bundle result = new Bundle();
        // This provider lives in the account server process. Only its real PID has this role.
        if (originalPid == Process.myPid()) {
            result.putInt("uid", 1000);
            result.putInt("userId", 0);
            result.putString("packageName", "android");
            result.putStringArray("packages", new String[]{"android"});
        } else {
            CallerIdentity original = EngineServerProvider.resolveRegisteredCaller(Process.myUid(), originalPid);
            if (original.userId != querying.userId) throw new SecurityException("Caller belongs to a different virtual user");
            result.putInt("uid", original.appId);
            result.putInt("userId", original.userId);
            result.putString("packageName", original.packageName);
            result.putStringArray("packages", new String[]{original.packageName});
        }
        return result;
    }
}
