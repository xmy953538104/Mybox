/* SPDX-License-Identifier: Apache-2.0 */
package io.github.xmy953538104.mybox.engine;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import dalvik.system.PathClassLoader;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Loads only the double-pinned service helper; never initializes a native UID hook or a guest. */
public final class MicrogIdentityProbeService extends Service {
    private static final String DESCRIPTOR = "mybox.synthetic.MicrogSelfIdentity";
    private Method selfMethod;
    private String loadFailure;

    @Override public void onCreate() {
        super.onCreate();
        try {
            InstalledPackage installed = Engine.get().packages().get("com.google.android.gms");
            TrustedMicrog.require(this, installed);
            PathClassLoader loader = new PathClassLoader(installed.baseApk.getAbsolutePath(), getClassLoader());
            Class<?> identity = Class.forName("org.microg.gms.common.MyboxIdentity", true, loader);
            if (identity.getClassLoader() != loader) throw new SecurityException("Helper did not come from the pinned APK");
            selfMethod = identity.getMethod("isCallingSelf", android.content.Context.class);
        } catch (Exception | LinkageError failure) {
            loadFailure = failure.getClass().getSimpleName();
        }
    }

    @Override public IBinder onBind(Intent intent) { return endpoint; }

    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            if ((flags & FLAG_ONEWAY) != 0 || reply == null || data.dataAvail() != 0) {
                throw new SecurityException("The identity probe requires an empty synchronous request");
            }
            EngineProtocol.requireProcess(MicrogIdentityProbeService.this, getPackageName());
            Bundle result = new Bundle();
            result.putBoolean("trustedHelperLoaded", selfMethod != null && loadFailure == null);
            if (loadFailure != null) result.putString("failure", loadFailure);
            else exercise(result);
            reply.writeNoException();
            reply.writeBundle(result);
            return true;
        }
    };

    private void exercise(Bundle result) {
        int callerUid = Binder.getCallingUid(), callerPid = Binder.getCallingPid();
        result.putBoolean("samePhysicalUid", callerUid == Process.myUid());
        result.putBoolean("differentPhysicalPid", callerPid > 0 && callerPid != Process.myPid());
        FutureTask<boolean[]> local = new FutureTask<>(() -> new boolean[]{
                Binder.getCallingUid() == Process.myUid() && Binder.getCallingPid() == Process.myPid(),
                invokeSelf()
        });
        Thread thread = new Thread(local, "microg-self-identity-probe");
        thread.setDaemon(true);
        thread.start();
        try {
            boolean[] observed = local.get(10, TimeUnit.SECONDS);
            result.putBoolean("localRawIdentity", observed[0]);
            result.putBoolean("localSelf", observed[1]);
            try {
                invokeSelf();
                result.putBoolean("remoteUnexpectedlyReturned", true);
            } catch (SecurityException expected) {
                // Fixed server-side error establishes that the helper reached the real registry.
                result.putBoolean("remoteRegistryRejected",
                        "Caller PID has no assigned guest identity".equals(expected.getMessage()));
            }
        } catch (Exception | LinkageError failure) {
            result.putString("failure", failure.getClass().getSimpleName());
        } finally {
            local.cancel(false);
            result.putBoolean("incomingIdentityRestored",
                    Binder.getCallingUid() == callerUid && Binder.getCallingPid() == callerPid);
        }
    }

    private boolean invokeSelf() throws Exception {
        try { return Boolean.TRUE.equals(selfMethod.invoke(null, this)); }
        catch (InvocationTargetException wrapper) {
            Throwable failure = wrapper.getCause();
            if (failure instanceof Exception) throw (Exception) failure;
            throw new IllegalStateException("Pinned helper failed");
        }
    }
}
