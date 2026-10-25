package io.github.xmy953538104.mybox.fixture.jobs;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class JobProbe {
    public static void runStopFinishRace(Context context, String run) {
        if (!run.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid job race run");
        JSONObject event = new JSONObject();
        try {
            PersistableBundle extras = raceExtras(run, "race-a");
            int accepted = context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(7007,
                    new ComponentName(context, Worker.class)).setExtras(extras).setMinimumLatency(100).setOverrideDeadline(1000).build());
            if (accepted != JobScheduler.RESULT_SUCCESS) throw new AssertionError("OS rejected race A");
            event.put("accepted", accepted);
        } catch (Throwable failure) { putFailure(event, failure); }
        publish(context, raceFile(run, "scheduled"), event);
    }
    private static PersistableBundle raceExtras(String run, String mode) {
        PersistableBundle extras = new PersistableBundle(); extras.putString("mode", mode); extras.putString("run", run); return extras;
    }
    private static String raceFile(String run, String phase) { return "job-race-" + run + "-" + phase + ".json"; }
    private static JSONObject raceEvent(String run) {
        JSONObject event = new JSONObject();
        try { event.put("run", run); event.put("pid", android.os.Process.myPid()); event.put("elapsed", android.os.SystemClock.elapsedRealtime()); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        return event;
    }
    private static void putFailure(JSONObject event, Throwable failure) {
        try { event.put("failure", failure.toString()); } catch (Exception ignored) { }
    }
    private static void awaitControl(Context context, String run, String phase, long budget) throws Exception {
        File signal = new File(context.getFilesDir(), raceFile(run, phase));
        long end = android.os.SystemClock.elapsedRealtime() + budget;
        while (!signal.isFile() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(10);
        if (!signal.isFile()) throw new java.util.concurrent.TimeoutException("Missing job race control: " + phase);
    }
    /** Only this disposable fixture blocks onBind; the production eight-second deadline is unchanged. */
    public static final class BarrierWorker extends android.app.Service {
        private android.app.job.JobServiceEngine engine;
        @Override public void onCreate() {
            super.onCreate();
            engine = new android.app.job.JobServiceEngine(this) {
            @Override public boolean onStartJob(JobParameters parameters) {
                String run = parameters.getExtras().getString("run"); JSONObject event = raceEvent(run);
                new android.os.Handler(getMainLooper()).post(() -> {
                    jobFinished(parameters, false);
                    new android.os.Handler(getMainLooper()).post(() -> publish(BarrierWorker.this, raceFile(run, "b-finished"), event));
                });
                return true;
            }
            @Override public boolean onStopJob(JobParameters parameters) { return false; }
            };
        }
        @Override public android.os.IBinder onBind(Intent intent) {
            // The controller writes the unique run before submitting B, in the same fixture data directory.
            String run;
            try { run = new String(java.nio.file.Files.readAllBytes(new File(getFilesDir(), "job-race-current").toPath()), StandardCharsets.UTF_8); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            if (!run.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid barrier run");
            JSONObject event = raceEvent(run); publish(this, raceFile(run, "b-bound"), event);
            try { awaitControl(this, run, "release-b", 4000); }
            catch (Exception failure) { putFailure(event, failure); publish(this, raceFile(run, "b-bind-failed"), event); }
            return engine.getBinder();
        }
    }
    public static void runPersisted(Context context) {
        JSONObject result = new JSONObject();
        try {
            PersistableBundle extras = new PersistableBundle(); extras.putString("mode", "persisted");
            extras.putString("nonce", java.util.UUID.randomUUID().toString());
            JobInfo job = new JobInfo.Builder(7010, new ComponentName(context, Worker.class)).setExtras(extras)
                    .setPersisted(true).setMinimumLatency(15000).setOverrideDeadline(30000).build();
            if (context.getSystemService(JobScheduler.class).schedule(job) != JobScheduler.RESULT_SUCCESS) throw new AssertionError("OS rejected persistent job");
            result.put("nonce", extras.getString("nonce"));
            result.put("boot", android.provider.Settings.Global.getInt(context.getContentResolver(), "boot_count", -1));
        } catch (Throwable failure) { try { result.put("failure", failure.toString()); } catch (Exception ignored) { } }
        publish(context, "job-persisted-scheduled.json", result);
    }
    public static void runExpedited(Context context) {
        JSONObject result = new JSONObject();
        try {
            PersistableBundle extras = new PersistableBundle(); extras.putString("mode", "expedited");
            JobInfo job = new JobInfo.Builder(7006, new ComponentName(context, Worker.class)).setExtras(extras).setExpedited(true).build();
            result.put("accepted", context.getSystemService(JobScheduler.class).schedule(job));
        } catch (Throwable failure) { try { result.put("failure", failure.toString()); } catch (Exception ignored) { } }
        publish(context, "job-expedited-scheduled.json", result);
    }
    public static void runRecovery(Context context) {
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                PersistableBundle extras = new PersistableBundle(); extras.putString("mode", "recover");
                extras.putString("nonce", java.util.UUID.randomUUID().toString());
                JobInfo job = new JobInfo.Builder(7005, new ComponentName(context, Worker.class)).setExtras(extras)
                        .setMinimumLatency(500).setOverrideDeadline(1500).setBackoffCriteria(10000, JobInfo.BACKOFF_POLICY_LINEAR).build();
                int accepted = context.getSystemService(JobScheduler.class).schedule(job);
                if (accepted != JobScheduler.RESULT_SUCCESS) throw new AssertionError("Actual OS rejected recovery job");
                result.put("nonce", extras.getString("nonce"));
            } catch (Throwable failure) { try { result.put("failure", failure.toString()); } catch (Exception ignored) { } }
            publish(context, "job-recovery-scheduled.json", result);
        }, "actual-recovery-schedule").start();
    }
    public static void run(Context context) {
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                JobScheduler scheduler = context.getSystemService(JobScheduler.class); scheduler.cancelAll();
                android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
                result.put("singleUser", ((Number) android.os.UserManager.class.getDeclaredMethod("getUserCount").invoke(users)).intValue() == 1
                        && users.getUserProfiles().size() == 1 && users.getUserProfiles().get(0).equals(android.os.Process.myUserHandle()));
                Class<?> http = Class.forName("org.apache.http.ProtocolVersion", true, context.getClassLoader());
                Object version = http.getConstructor(String.class, int.class, int.class).newInstance("HTTP", 1, 1);
                result.put("systemLibrary", "HTTP/1.1".equals(version.toString()));
                Object policies = context.getSystemService(android.app.admin.DevicePolicyManager.class);
                java.lang.reflect.Method owner = android.app.admin.DevicePolicyManager.class.getDeclaredMethod("getProfileOwnerNameAsUser", int.class);
                java.lang.reflect.Method deviceOwner = android.app.admin.DevicePolicyManager.class.getDeclaredMethod("getDeviceOwnerNameOnAnyUser");
                java.lang.reflect.Method deviceOwnerComponent = android.app.admin.DevicePolicyManager.class.getDeclaredMethod("getDeviceOwnerComponentOnAnyUser");
                result.put("virtualOwnerAbsent", owner.invoke(policies, 0) == null && deviceOwner.invoke(policies) == null
                        && deviceOwnerComponent.invoke(policies) == null);
                result.put("realRestrictionsRead", context.getSystemService(android.content.RestrictionsManager.class).getApplicationRestrictions() != null
                        && users.getApplicationRestrictions(context.getPackageName()) != null);
                try { users.getApplicationRestrictions("foreign.owner"); throw new AssertionError("Foreign restrictions query accepted"); }
                catch (SecurityException expected) { result.put("foreignRestrictionsRejected", true); }
                Intent disabled = new Intent().setComponent(new ComponentName(context, DisabledHelper.class));
                java.util.List<android.content.pm.ResolveInfo> disabledMatches = context.getPackageManager().queryIntentServices(disabled, 0x10000200);
                result.put("disabledDirectBootQuery", disabledMatches.size() == 1 && !disabledMatches.get(0).serviceInfo.enabled
                        && !disabledMatches.get(0).serviceInfo.directBootAware && context.getPackageManager().queryIntentServices(disabled, 0).isEmpty()
                        && users.isUserUnlocked());
                ComponentName service = new ComponentName(context, Worker.class);
                PersistableBundle extras = new PersistableBundle(); extras.putString("marker", "actual-os-work");
                JobInfo job = new JobInfo.Builder(7001, service).setExtras(extras).setMinimumLatency(2000).setOverrideDeadline(5000).build();
                if (scheduler.enqueue(job, new JobWorkItem(new Intent("mylite.work").putExtra("sequence", 1))) != JobScheduler.RESULT_SUCCESS
                        || scheduler.enqueue(job, new JobWorkItem(new Intent("mylite.work").putExtra("sequence", 2))) != JobScheduler.RESULT_SUCCESS) {
                    throw new IllegalStateException("Actual OS rejected work enqueue");
                }
                JobInfo pending = scheduler.getPendingJob(7001);
                result.put("pendingIdentity", pending != null && pending.getId() == 7001 && service.equals(pending.getService())
                        && "actual-os-work".equals(pending.getExtras().getString("marker")));
                result.put("pendingList", scheduler.getAllPendingJobs().size() == 1);
                try {
                    scheduler.schedule(new JobInfo.Builder(7002, new ComponentName("foreign.owner", "foreign.owner.Job")).setMinimumLatency(60000).build());
                    throw new AssertionError("Foreign job service accepted");
                } catch (SecurityException expected) { result.put("foreignRejected", true); }
                if (scheduler.schedule(new JobInfo.Builder(7003, service).setPersisted(true).setMinimumLatency(60000).build()) != JobScheduler.RESULT_SUCCESS) throw new AssertionError("OS rejected persistent job");
                result.put("persistedPending", scheduler.getPendingJob(7003) != null && scheduler.getPendingJob(7003).isPersisted());
                scheduler.cancel(7003); result.put("persistedCancel", scheduler.getPendingJob(7003) == null);
                try {
                    scheduler.enqueue(job, new JobWorkItem(new Intent("mylite.work").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
                    throw new AssertionError("Unsupported work URI grant accepted");
                } catch (UnsupportedOperationException expected) { result.put("uriRejected", true); }
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    JobScheduler a = scheduler.forNamespace("first"), b = scheduler.forNamespace("second");
                    JobInfo waiting = new JobInfo.Builder(7009, service).setMinimumLatency(60000).build();
                    if (a.schedule(waiting) != 1 || b.schedule(waiting) != 1) throw new AssertionError("OS rejected namespaced jobs");
                    result.put("namespaceIdentity", a.getPendingJob(7009) != null && b.getPendingJob(7009) != null
                            && scheduler.getPendingJob(7009) == null && scheduler.getPendingJobsInAllNamespaces().size() == 3);
                    a.cancelAll();
                    result.put("namespaceCancel", a.getPendingJob(7009) == null && b.getPendingJob(7009) != null);
                    b.cancelAll();
                }
            } catch (Throwable failure) {
                try { result.put("failure", failure.toString()); } catch (Exception ignored) { }
            }
            publish(context, "job-scheduled.json", result);
        }, "actual-job-schedule").start();
    }
    public static final class Worker extends JobService {
        @Override public boolean onStartJob(JobParameters parameters) {
            if ("race-a".equals(parameters.getExtras().getString("mode"))) {
                String run = parameters.getExtras().getString("run");
                new android.os.Handler(getMainLooper()).post(() -> {
                    publish(this, raceFile(run, "a-started"), raceEvent(run));
                    new Thread(() -> {
                        JSONObject event = raceEvent(run);
                        try {
                            java.nio.file.Files.write(new File(getFilesDir(), "job-race-current").toPath(), run.getBytes(StandardCharsets.UTF_8));
                            int accepted = getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(7008,
                                    new ComponentName(this, BarrierWorker.class)).setExtras(raceExtras(run, "race-b"))
                                    .setMinimumLatency(100).setOverrideDeadline(1000).build());
                            if (accepted != JobScheduler.RESULT_SUCCESS) throw new AssertionError("OS rejected race B");
                            awaitControl(this, run, "finish-a", 20000);
                            new android.os.Handler(getMainLooper()).post(() -> {
                                jobFinished(parameters, false);
                                new android.os.Handler(getMainLooper()).post(() -> publish(this, raceFile(run, "a-finished"), raceEvent(run)));
                            });
                        } catch (Throwable failure) { putFailure(event, failure); publish(this, raceFile(run, "a-failed"), event); }
                    }, "actual-stop-finish-race").start();
                });
                return true;
            }
            if ("persisted".equals(parameters.getExtras().getString("mode"))) {
                JSONObject event = new JSONObject();
                try {
                    event.put("jobId", parameters.getJobId()); event.put("nonce", parameters.getExtras().getString("nonce"));
                    event.put("boot", android.provider.Settings.Global.getInt(getContentResolver(), "boot_count", -1));
                    event.put("pid", android.os.Process.myPid());
                } catch (Exception failure) { throw new IllegalStateException(failure); }
                new android.os.Handler(getMainLooper()).post(() -> {
                    jobFinished(parameters, false);
                    new android.os.Handler(getMainLooper()).post(() -> publish(this, "job-persisted-executed.json", event));
                });
                return true;
            }
            if ("expedited".equals(parameters.getExtras().getString("mode"))) {
                JSONObject event = new JSONObject();
                try {
                    event.put("jobId", parameters.getJobId()); event.put("pid", android.os.Process.myPid());
                    event.put("expedited", parameters.isExpeditedJob());
                } catch (Exception failure) { throw new IllegalStateException(failure); }
                new android.os.Handler(getMainLooper()).post(() -> {
                    jobFinished(parameters, false); jobFinished(parameters, false);
                    new android.os.Handler(getMainLooper()).post(() -> publish(this, "job-expedited-executed.json", event));
                });
                return true;
            }
            if ("recover".equals(parameters.getExtras().getString("mode"))) {
                JSONObject event = new JSONObject();
                try {
                    event.put("pid", android.os.Process.myPid()); event.put("jobId", parameters.getJobId());
                    event.put("nonce", parameters.getExtras().getString("nonce"));
                } catch (Exception failure) { throw new IllegalStateException(failure); }
                if (new File(getFilesDir(), "job-recovery-first.json").isFile()) {
                    new android.os.Handler(getMainLooper()).post(() -> {
                        jobFinished(parameters, false); publish(this, "job-recovery-second.json", event);
                    });
                } else publish(this, "job-recovery-first.json", event);
                return true;
            }
            new Thread(() -> {
                JSONObject result = new JSONObject();
                try {
                    result.put("jobId", parameters.getJobId()); result.put("pid", android.os.Process.myPid());
                    result.put("extras", "actual-os-work".equals(parameters.getExtras().getString("marker")));
                    JobWorkItem first = parameters.dequeueWork(), second = parameters.dequeueWork();
                    if (first == null || second == null) throw new AssertionError("OS did not return two queued work items");
                    result.put("workValues", first.getIntent().getIntExtra("sequence", -1) == 1 && second.getIntent().getIntExtra("sequence", -1) == 2);
                    java.lang.reflect.Method workId = JobWorkItem.class.getDeclaredMethod("getWorkId"); workId.setAccessible(true);
                    result.put("workIds", !workId.invoke(first).equals(workId.invoke(second)));
                    parameters.completeWork(first); parameters.completeWork(second);
                    result.put("completed", true); jobFinished(parameters, false);
                } catch (Throwable failure) {
                    try { result.put("failure", failure.toString()); } catch (Exception ignored) { }
                }
                publish(this, "job-executed.json", result);
            }, "actual-job-work").start();
            return true;
        }
        @Override public boolean onStopJob(JobParameters parameters) {
            if ("race-a".equals(parameters.getExtras().getString("mode"))) {
                String run = parameters.getExtras().getString("run"); publish(this, raceFile(run, "a-stopped"), raceEvent(run)); return false;
            }
            JSONObject event = new JSONObject();
            try { event.put("jobId", parameters.getJobId()); event.put("stopReason", parameters.getStopReason()); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            publish(this, "job-stopped.json", event); return true;
        }
    }
    public static final class DisabledHelper extends android.app.Service {
        @Override public android.os.IBinder onBind(Intent intent) { return null; }
    }
    private static void publish(Context context, String name, JSONObject result) {
        try {
            File temporary = new File(context.getFilesDir(), name + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(result.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            if (!temporary.renameTo(new File(context.getFilesDir(), name))) throw new java.io.IOException("Cannot publish job report");
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
