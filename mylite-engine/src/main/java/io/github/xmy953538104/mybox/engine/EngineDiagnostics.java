package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/** Own structured events only; diagnostics must never change an operation's result. */
public final class EngineDiagnostics {
    private static volatile DiagnosticJournal journal;
    private EngineDiagnostics() { }

    static synchronized void initialize(Context host) {
        if (journal != null) return;
        try {
            String process = Build.VERSION.SDK_INT >= 28 ? android.app.Application.getProcessName() : "unknown";
            String role = host.getPackageName().equals(process) ? "host"
                    : process != null && process.startsWith(host.getPackageName() + ":")
                    ? process.substring(host.getPackageName().length() + 1) : "unknown";
            journal = new DiagnosticJournal(new File(host.getNoBackupFilesDir(), "diagnostics"), android.os.Process.myPid(), role);
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try { failure("engine", "uncaught", 0, error); }
                finally {
                    if (previous != null) previous.uncaughtException(thread, error);
                    else thread.getThreadGroup().uncaughtException(thread, error);
                }
            });
            event("engine", "process_start", android.os.Process.myPid(), Build.VERSION.SDK_INT);
        } catch (IOException | RuntimeException failure) { android.util.Log.w("MyliteDiagnostics", "Diagnostic storage unavailable"); }
    }

    public static boolean enabled() { DiagnosticJournal value = journal; return value != null && value.enabled(); }
    public static void setEnabled(boolean enabled) throws IOException {
        require().setEnabled(enabled);
        if (enabled) event("engine", "debug_enabled", 0, 1);
    }
    public static void clear() throws IOException { require().clear(); }
    static void bound(String name, long generation) { DiagnosticJournal value = journal; if (value != null) value.bound(name, generation); }
    public static long begin(String area, String operation, long value) {
        DiagnosticJournal journal = EngineDiagnostics.journal;
        if (journal == null || !journal.enabled()) return 0;
        long id = journal.next(); journal.record(area, operation + ".begin", id, value, null); return id;
    }
    public static void event(String area, String operation, long id, long value) {
        DiagnosticJournal journal = EngineDiagnostics.journal;
        if (journal != null) journal.record(area, operation, id, value, null);
    }
    public static void failure(String area, String operation, long id, Throwable error) {
        DiagnosticJournal journal = EngineDiagnostics.journal;
        if (journal != null) journal.record(area, operation + ".failed", id, 0, error);
    }
    public static void export(Context host, OutputStream output) throws IOException {
        DiagnosticNetworkLog.capture(host.getApplicationInfo().uid);
        try {
            ConnectivityManager network = host.getSystemService(ConnectivityManager.class);
            NetworkCapabilities capabilities = network.getNetworkCapabilities(network.getActiveNetwork());
            int flags = capabilities == null ? 0 : 1
                    | (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? 2 : 0)
                    | (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 4 : 0)
                    | (network.getDefaultProxy() != null ? 8 : 0);
            event("network", "host_capabilities", 0, flags);
            android.app.job.JobScheduler jobs = host.getSystemService(android.app.job.JobScheduler.class);
            for (android.app.job.JobInfo job : jobs.getAllPendingJobs()) event("job", "physical_pending", job.getId(), job.isPersisted() ? 1 : 0);
            if (Build.VERSION.SDK_INT >= 30) {
                android.app.ActivityManager processes = host.getSystemService(android.app.ActivityManager.class);
                for (android.app.ApplicationExitInfo exit : processes.getHistoricalProcessExitReasons(host.getPackageName(), 0, 32)) {
                    event("process_exit", "reason", exit.getPid(), exit.getReason());
                    event("process_exit", "status", exit.getPid(), exit.getStatus());
                    event("process_exit", "wall_ms", exit.getPid(), exit.getTimestamp());
                }
            }
        } catch (RuntimeException failure) { failure("engine", "environment_snapshot", 0, failure); }
        try {
            long version = host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionCode;
            android.os.Bundle metadata = host.getPackageManager().getApplicationInfo(host.getPackageName(), android.content.pm.PackageManager.GET_META_DATA).metaData;
            require().export(output, Build.VERSION.SDK_INT, version, metadata == null ? "unknown" : metadata.getString("mybox.source-revision", "unknown"));
        } catch (android.content.pm.PackageManager.NameNotFoundException impossible) { throw new IOException("Host metadata unavailable", impossible); }
    }
    private static DiagnosticJournal require() throws IOException {
        DiagnosticJournal value = journal;
        if (value == null) throw new IOException("Diagnostic storage unavailable");
        return value;
    }
}
