package io.github.xmy953538104.mybox.engine;

import android.app.Service;
import android.app.job.JobParameters;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A real OS JobService Binder. Only this proxy process dies if its actual executor vanishes. */
public final class OsJobService extends Service {
    static final String GUARD = EngineProtocol.SERVER + ".JobGuard.v1";
    static final int WATCH = IBinder.FIRST_CALL_TRANSACTION, RELEASE = WATCH + 1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, String> operations = new HashMap<>();
    private final ThreadPoolExecutor dispatch = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
    private final Binder service = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString("android.app.job.IJobService"); return true; }
            if (Binder.getCallingUid() != Process.SYSTEM_UID) throw new SecurityException("Only Android may invoke the physical JobService");
            String operation = operations.get(code);
            if (operation == null) return false;
            data.enforceInterface("android.app.job.IJobService");
            JobParameters parameters = data.readInt() == 0 ? null : JobParameters.CREATOR.createFromParcel(data);
            android.app.job.JobWorkItem work = null;
            if (operation.equals("getTransferredDownloadBytes") || operation.equals("getTransferredUploadBytes")) {
                work = data.readInt() == 0 ? null : android.app.job.JobWorkItem.CREATOR.createFromParcel(data);
            }
            if (parameters == null || data.dataAvail() != 0) throw new IllegalArgumentException("Invalid OS job executor arguments");
            Guard guard = "startJob".equals(operation) ? new Guard() : null;
            android.app.job.JobWorkItem actualWork = work;
            try { dispatch.execute(() -> deliver(operation, parameters, actualWork, guard)); }
            catch (java.util.concurrent.RejectedExecutionException full) { retire("OS job dispatch queue exhausted", full); }
            return true;
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        try {
            Class<?> stub = Class.forName("android.app.job.IJobService$Stub");
            for (String operation : new String[]{"startJob", "stopJob", "onNetworkChanged", "getTransferredDownloadBytes", "getTransferredUploadBytes"}) {
                try { Field field = RuntimeReflection.field(stub, "TRANSACTION_" + operation); operations.put(field.getInt(null), operation); }
                catch (NoSuchFieldException absent) { if (operation.equals("startJob") || operation.equals("stopJob")) throw absent; }
            }
            service.attachInterface(null, "android.app.job.IJobService");
        } catch (Exception failure) { throw new EngineException("Physical JobService protocol is unavailable", failure); }
    }
    @Override public IBinder onBind(Intent intent) { return service; }
    @Override public void onDestroy() { dispatch.shutdownNow(); super.onDestroy(); }
    private void deliver(String operation, JobParameters parameters, android.app.job.JobWorkItem work, Guard guard) {
        Runnable timeout = () -> retire("Guest job dispatch exceeded Android's start/stop budget", null);
        main.postDelayed(timeout, 8000);
        try {
            IBinder server = Engine.get().server();
            if (guard != null) {
                guard.server = server;
                server.linkToDeath(guard.serverDeath, 0);
                if (!server.isBinderAlive()) throw new android.os.DeadObjectException();
            }
            Bundle input = EngineProtocol.response(); input.putString("operation", operation); input.putParcelable("parameters", parameters);
            if (work != null) input.putParcelable("work", work);
            if (guard != null) input.putBinder("guard", guard);
            EngineProtocol.request(server, EngineProtocol.SERVER, EngineProtocol.JOB_SERVICE, input);
        } catch (Exception failure) { retire("Actual guest job dispatch failed", failure); }
        finally { main.removeCallbacks(timeout); }
    }
    private void retire(String reason, Exception failure) {
        android.util.Log.e("MyliteJobs", reason, failure);
        Process.killProcess(Process.myPid());
    }
    private final class Guard extends Binder {
        IBinder owner, server;
        boolean closed;
        final IBinder.DeathRecipient executorDeath = () -> {
            synchronized (this) { if (closed) return; }
            retire("Actual guest JobService executor died", null);
        };
        final IBinder.DeathRecipient serverDeath = () -> retire("Job's bound engine server died", null);
        Guard() { attachInterface(null, GUARD); }
        @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(GUARD); return true; }
            EngineProtocol.requireProcess(Engine.get().hostContext(), getPackageName() + ":engine_server");
            data.enforceInterface(GUARD); Bundle input = data.readBundle(getClass().getClassLoader());
            try {
                if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) throw new SecurityException("Invalid job guard protocol");
                if (code == WATCH) {
                    if (closed || owner != null) throw new SecurityException("Job executor guard already assigned or closed");
                    IBinder candidate = input.getBinder("owner");
                    if (candidate == null || !EngineProtocol.GUEST.equals(candidate.getInterfaceDescriptor())) throw new SecurityException("Invalid job executor owner");
                    candidate.linkToDeath(executorDeath, 0); owner = candidate;
                    if (!candidate.isBinderAlive()) throw new android.os.DeadObjectException();
                } else if (code == RELEASE) {
                    closed = true;
                    if (owner != null) owner.unlinkToDeath(executorDeath, 0);
                    if (server != null) server.unlinkToDeath(serverDeath, 0);
                } else return false;
                reply.writeNoException(); reply.writeBundle(EngineProtocol.response());
            } catch (Exception failure) { reply.writeNoException(); reply.writeBundle(EngineProtocol.failure(failure)); }
            return true;
        }
    }
}
