/* SPDX-License-Identifier: Apache-2.0 */
package io.github.xmy953538104.mybox.engine;

import android.app.AppOpsManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/** Synthetic IPC endpoint, declared only in the debug APK in its own process. */
public final class NativeIdentityProbeService extends Service {
    public static final String DESCRIPTOR = "mybox.synthetic.NativeIdentity";
    public static final int DESCRIBE = IBinder.FIRST_CALL_TRANSACTION;
    public static final int EXERCISE = DESCRIBE + 1;
    public static final int ONEWAY = DESCRIBE + 2;
    public static final int REPORT = DESCRIBE + 3;
    private NativeBinderIdentity.Result capability;
    private int selfUid, selfPid;

    // This fixture deliberately verifies the no-IPC identity on the service main thread.
    @android.annotation.SuppressLint("BinderGetCallingInMainThread")
    @Override public void onCreate() {
        super.onCreate();
        capability = NativeBinderIdentity.initializeTestProcess(this);
        selfUid = Binder.getCallingUid();
        selfPid = Binder.getCallingPid();
    }

    @Override public IBinder onBind(Intent intent) { return endpoint; }

    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code < DESCRIBE || code > ONEWAY) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            if (NativeBinderIdentity.rawCallingUid() != Process.myUid()) {
                throw new SecurityException("Synthetic endpoint requires the host UID");
            }
            Bundle report;
            if (code == DESCRIBE) {
                report = new Bundle();
                report.putBoolean("installed", capability.installed);
                report.putInt("errno", capability.errno);
                report.putInt("entryOffset", capability.entryOffset);
                report.putString("abi", capability.abi);
                report.putString("detail", capability.detail);
                report.putInt("selfUid", selfUid);
                report.putInt("selfPid", selfPid);
                report.putInt("processPid", Process.myPid());
            } else if (code == EXERCISE) {
                report = synchronous();
            } else {
                IBinder receiver = data.readStrongBinder();
                report = oneway();
                Parcel outgoing = Parcel.obtain();
                Parcel response = Parcel.obtain();
                try {
                    outgoing.writeInterfaceToken(DESCRIPTOR);
                    outgoing.writeBundle(report);
                    if (!receiver.transact(REPORT, outgoing, response, 0)) {
                        throw new RemoteException("Synthetic callback was rejected");
                    }
                    response.readException();
                } finally { response.recycle(); outgoing.recycle(); }
                return true;
            }
            reply.writeNoException();
            reply.writeBundle(report);
            return true;
        }
    };

    private Bundle synchronous() {
        Bundle report = new Bundle();
        int uid = NativeBinderIdentity.rawCallingUid();
        int pid = NativeBinderIdentity.rawCallingPid();
        report.putInt("rawUid", uid);
        report.putInt("rawPid", pid);
        try {
            require(capability.installed, "native capability unavailable");
            require(uid == Process.myUid() && pid > 0 && pid != Process.myPid(), "not a remote IPC");
            require(Binder.getCallingUid() == uid, "unscoped UID changed");
            try (NativeBinderIdentity.Scope scope = NativeBinderIdentity.enterVerifiedCall(uid, pid, 12345, 7)) {
                require(Binder.getCallingUid() == 12345, "virtual UID not visible");
                require(NativeBinderIdentity.rawCallingUid() == uid
                        && NativeBinderIdentity.rawCallingPid() == pid, "raw identity changed");
                report.putBoolean("mappedWithRawPreserved", true);

                try (NativeBinderIdentity.Scope raw = NativeBinderIdentity.enterRawScope()) {
                    require(Binder.getCallingUid() == uid, "raw scope did not suppress mapping");
                    getSystemService(AppOpsManager.class).checkPackage(Process.myUid(), getPackageName());
                    if (Build.VERSION.SDK_INT >= 31) {
                        require(getAttributionSource().getUid() == Process.myUid(), "system attribution changed");
                    }
                    report.putBoolean("systemBoundaryRaw", true);
                }
                require(Binder.getCallingUid() == 12345, "raw scope did not restore parent");
                try (NativeBinderIdentity.Scope nested = NativeBinderIdentity.enterVerifiedCall(uid, pid, 12346, 8)) {
                    require(Binder.getCallingUid() == 12346, "nested scope ignored");
                }
                require(Binder.getCallingUid() == 12345, "nested scope did not restore parent");
                report.putBoolean("nestedScopes", true);

                long token = Binder.clearCallingIdentity();
                try {
                    require(Binder.getCallingUid() == Process.myUid(), "clear identity retained mapping");
                    require(NativeBinderIdentity.rawCallingPid() == Process.myPid(), "clear identity PID wrong");
                } finally { Binder.restoreCallingIdentity(token); }
                require(Binder.getCallingUid() == 12345 && NativeBinderIdentity.rawCallingPid() == pid,
                        "genuine restore identity failed");
                report.putBoolean("clearRestore", true);

                if (Build.VERSION.SDK_INT >= 29) {
                    java.lang.reflect.Method set = RuntimeReflection.method(Binder.class, "setCallingWorkSourceUid", int.class);
                    java.lang.reflect.Method restore = RuntimeReflection.method(Binder.class, "restoreCallingWorkSource", long.class);
                    long source = (Long) set.invoke(null, 578875);
                    try { require(Binder.getCallingUid() == 12345, "untrusted WorkSource changed UID"); }
                    finally { restore.invoke(null, source); }
                }
                report.putBoolean("workSourceIgnored", true);
                int[] childUid = {-1};
                Thread child = new Thread(() -> childUid[0] = Binder.getCallingUid(), "identity-probe");
                child.start(); child.join(5000);
                require(!child.isAlive() && childUid[0] == uid, "scope escaped to another thread");
                report.putBoolean("threadIsolation", true);
            }
            require(Binder.getCallingUid() == uid, "scope leaked after close");
            try {
                try (NativeBinderIdentity.Scope ignored = NativeBinderIdentity.enterVerifiedCall(uid, pid, 12345, 7)) {
                    throw new ProbeException();
                }
            } catch (ProbeException expected) { }
            require(Binder.getCallingUid() == uid, "scope leaked after exception");
            report.putBoolean("exceptionCleanup", true);
            reject(uid + 1, pid, 12345, 7);
            reject(uid, Process.myPid(), 12345, 7);
            reject(uid, 0, 12345, 7);
            reject(uid, pid, 1000, 7);
            reject(uid, pid, 12345, 0);
            report.putBoolean("invalidScopesRejected", true);

            NativeBinderIdentity.Scope outer = NativeBinderIdentity.enterVerifiedCall(uid, pid, 12345, 7);
            NativeBinderIdentity.Scope inner = NativeBinderIdentity.enterVerifiedCall(uid, pid, 12346, 8);
            boolean rejected = false;
            try { outer.close(); } catch (IllegalStateException expected) { rejected = true; }
            require(rejected && Binder.getCallingUid() == uid, "misordered close did not fail closed");
            try { inner.close(); } catch (IllegalStateException expected) { }
            require(Binder.getCallingUid() == uid, "misordered scopes leaked");
            report.putBoolean("misorderedCloseFailsClosed", true);
        } catch (Exception | AssertionError failure) {
            report.putString("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        report.putInt("afterUid", Binder.getCallingUid());
        return report;
    }

    private Bundle oneway() {
        Bundle report = new Bundle();
        int uid = NativeBinderIdentity.rawCallingUid(), pid = NativeBinderIdentity.rawCallingPid();
        report.putInt("rawUid", uid);
        report.putInt("rawPid", pid);
        report.putInt("visibleUid", Binder.getCallingUid());
        try {
            require(capability.installed, "native capability unavailable");
            require(pid == 0, "oneway did not have the documented anonymous PID");
            reject(uid, pid, 12345, 7);
            report.putBoolean("anonymousScopeRejected", true);
        } catch (Exception | AssertionError failure) {
            report.putString("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return report;
    }

    private static void reject(int uid, int pid, int view, long generation) {
        try (NativeBinderIdentity.Scope ignored = NativeBinderIdentity.enterVerifiedCall(uid, pid, view, generation)) {
            throw new AssertionError("Unverified scope was accepted");
        } catch (SecurityException expected) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static final class ProbeException extends RuntimeException { }
}
