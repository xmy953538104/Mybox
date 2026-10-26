package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.IOException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Pinned Google bootstrap/recovery; microG remains authoritative for its enabled/network state. */
final class ServerGoogleServices {
    private static final String GMS = "com.google.android.gms", GENERATION = "generation";
    private static final long RECOVERY_WINDOW_MS = 300_000;
    private final Engine engine;
    private final ServerBroadcasts broadcasts;
    private final SharedPreferences policy;
    private final int boot;
    private final ScheduledThreadPoolExecutor recovery = new ScheduledThreadPoolExecutor(1,
            runnable -> new Thread(runnable, "MyliteGoogleRecovery"));
    private boolean queued;
    private long noticeVersion;
    private volatile String notice = "";

    ServerGoogleServices(Engine engine, ServerBroadcasts broadcasts, int boot) {
        this.engine = engine; this.broadcasts = broadcasts; this.boot = boot;
        policy = engine.hostContext().getSharedPreferences("mylite-google-services", Context.MODE_PRIVATE);
        recovery.setRemoveOnCancelPolicy(true);
    }

    void prepare() throws Exception {
        InstalledPackage installed = engine.packages().get(GMS);
        TrustedMicrog.require(engine.hostContext(), installed);
        String generation = installed.baseApk.getAbsolutePath();
        synchronized (this) {
            if (!policy.edit().putString(GENERATION, generation).putInt("boot", boot)
                    .putLong("window", SystemClock.elapsedRealtime()).putInt("attempts", 0).commit())
                throw new IOException("Google service policy could not be saved");
            notice = ""; noticeVersion++;
        }
        try { wake(generation); }
        catch (Exception failure) { failed(generation, failure); throw failure; }
    }

    String status() { return notice; }

    void restore() {
        try {
            String generation = policy.getString(GENERATION, null);
            if (generation != null) enqueue(generation, null);
        } catch (RuntimeException failure) { failed(null, failure); }
    }

    void died(ProcessRegistry.Identity identity, ProviderConnection connection) {
        if (!GMS.equals(identity.packageName) || !(GMS + ":persistent").equals(identity.processName)) return;
        try { enqueue(identity.packageGeneration, connection); }
        catch (RuntimeException failure) { failed(identity.packageGeneration, failure); }
    }

    private void enqueue(String generation, ProviderConnection connection) {
        final int attempt;
        synchronized (this) {
            if (queued || !generation.equals(policy.getString(GENERATION, null))) return;
            long now = SystemClock.elapsedRealtime();
            attempt = nextAttempt(policy.getInt("boot", -1), boot, policy.getLong("window", now),
                    now, policy.getInt("attempts", 0));
            if (attempt == 0) {
                notice = "Google 服务多次退出，已暂停自动重试。请点击“准备 Google 服务与商店”重试。";
                noticeVersion++;
                android.util.Log.w("MyliteGoogle", "Automatic Google recovery budget exhausted");
                return;
            }
            SharedPreferences.Editor update = policy.edit().putInt("boot", boot).putInt("attempts", attempt);
            if (attempt == 1) update.putLong("window", now);
            if (!update.commit()) { failed(generation, new IOException("Recovery policy save failed")); return; }
            queued = true;
        }
        // Only one queued recovery plus one running attempt can exist, including repeated deaths.
        recovery.schedule(() -> {
            synchronized (ServerGoogleServices.this) { queued = false; }
            try {
                if (connection != null && !connection.awaitReleased(5_000))
                    throw new IOException("Previous Google provider has not finished releasing");
                if (wake(generation)) android.util.Log.i("MyliteGoogle", "Normal Google recovery dispatched");
            } catch (Exception failure) { failed(generation, failure); }
        }, attempt == 1 ? 0 : 250L << (attempt - 2), TimeUnit.MILLISECONDS);
    }

    private boolean wake(String generation) throws Exception {
        InstalledPackage installed = engine.packages().get(GMS);
        if (installed == null || !generation.equals(installed.baseApk.getAbsolutePath())
                || !generation.equals(policy.getString(GENERATION, null))) return false;
        TrustedMicrog.require(engine.hostContext(), installed);
        long version;
        synchronized (this) { version = noticeVersion; }
        broadcasts.reconnectGoogle(new OsPendingStore.Creator(installed));
        synchronized (this) {
            if (version == noticeVersion && generation.equals(policy.getString(GENERATION, null))) notice = "";
        }
        return true;
    }

    private synchronized void failed(String generation, Exception failure) {
        try { if (generation != null && !generation.equals(policy.getString(GENERATION, null))) return; }
        catch (RuntimeException unreadable) { /* Preserve the visible failure without resetting policy. */ }
        notice = "Google 服务初始化未完成，请点击“准备 Google 服务与商店”重试。";
        noticeVersion++;
        android.util.Log.w("MyliteGoogle", "Google bootstrap/recovery failed: " + failure.getClass().getSimpleName());
    }

    static int nextAttempt(int previousBoot, int boot, long window, long now, int attempts) {
        if (attempts < 0 || now < 0) throw new IllegalStateException("Invalid Google recovery policy");
        if (previousBoot != boot || now < window || now - window >= RECOVERY_WINDOW_MS) attempts = 0;
        return attempts >= 3 ? 0 : attempts + 1;
    }
}
