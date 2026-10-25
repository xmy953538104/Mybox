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
    static final String DEBUG_STOP_REQUEST = "job-stop-receipt-request.json", DEBUG_STOP_RECEIPT = "job-stop-queued.json";
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
            try {
                dispatch.execute(() -> deliver(operation, parameters, actualWork, guard));
                if ("stopJob".equals(operation)) recordRequestedStopReceipt(parameters.getJobId());
            }
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
    /** Opt-in debug observation after a genuine system transaction entered this executor's queue. */
    private synchronized void recordRequestedStopReceipt(int physicalId) {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        java.io.File request = new java.io.File(getFilesDir(), DEBUG_STOP_REQUEST);
        if (!request.isFile() || request.length() > 256) return;
        try {
            byte[] bytes = new byte[257]; int count = 0;
            try (java.io.FileInputStream input = new java.io.FileInputStream(request)) {
                for (int size; count < bytes.length && (size = input.read(bytes, count, bytes.length - count)) != -1;) count += size;
            }
            if (count == 0 || count > 256) return;
            org.json.JSONObject wanted = new org.json.JSONObject(new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8));
            String token = wanted.optString("token");
            if (!token.matches("[a-f0-9-]{36}") || wanted.optInt("physicalId", -1) != physicalId
                    || wanted.optInt("proxyPid", -1) != Process.myPid()) return;
            org.json.JSONObject receipt = new org.json.JSONObject(); receipt.put("token", token);
            receipt.put("physicalId", physicalId); receipt.put("proxyPid", Process.myPid());
            receipt.put("elapsed", android.os.SystemClock.elapsedRealtime());
            java.io.File temporary = new java.io.File(getFilesDir(), DEBUG_STOP_RECEIPT + ".tmp");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {
                output.write(receipt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (!temporary.renameTo(new java.io.File(getFilesDir(), DEBUG_STOP_RECEIPT))) throw new java.io.IOException("Cannot publish job stop receipt");
        } catch (Exception failure) { android.util.Log.w("MyliteJobs", "Requested debug stop receipt unavailable", failure); }
    }
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
            Bundle result = EngineProtocol.request(server, EngineProtocol.SERVER, EngineProtocol.JOB_SERVICE, input);
            if (!result.getBoolean("delivered")) {
                if (!ServerJobs.isContinuation(operation) || !ServerJobs.NO_ACTIVE_EXECUTION.equals(result.getString("rejection"))) {
                    throw new EngineException("Unexpected physical job non-delivery result");
                }
                // This is not an OS acknowledgement: Android retains its own callback/timeout decision.
                android.util.Log.w("MyliteJobs", "Job message not delivered: " + operation + " rejection=" + ServerJobs.NO_ACTIVE_EXECUTION);
            }
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
