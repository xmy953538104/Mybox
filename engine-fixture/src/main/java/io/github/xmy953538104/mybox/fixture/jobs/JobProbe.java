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
                result.put("virtualOwnerAbsent", owner.invoke(policies, 0) == null && deviceOwner.invoke(policies) == null);
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
                try {
                    scheduler.schedule(new JobInfo.Builder(7003, service).setPersisted(true).setMinimumLatency(60000).build());
                    throw new AssertionError("Unsupported persisted job accepted");
                } catch (UnsupportedOperationException expected) { result.put("persistedRejected", true); }
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
