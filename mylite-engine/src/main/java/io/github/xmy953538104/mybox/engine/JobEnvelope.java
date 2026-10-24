package io.github.xmy953538104.mybox.engine;

import android.app.job.JobInfo;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.util.Base64;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Signed nonpersistent OS metadata; no parallel pending-job database. */
final class JobEnvelope {
    static final String PAYLOAD = "mylite.job.v1", MAC = "mylite.job.mac";
    static final int MAX_BYTES = 65536;
    final String packageName, namespace, capability;
    final int appId, physicalId;
    final ArrayList<String> certificates;
    final JobInfo original;

    JobEnvelope(CallerIdentity owner, String namespace, int physicalId, JobInfo original) {
        this(owner.packageName, owner.appId, new ArrayList<>(owner.certificateSha256), namespace,
                physicalId, UUID.randomUUID().toString(), original);
    }
    private JobEnvelope(String name, int appId, ArrayList<String> certificates, String namespace,
                        int physicalId, String capability, JobInfo original) {
        packageName = name; this.appId = appId; this.certificates = certificates;
        this.namespace = namespace; this.physicalId = physicalId; this.capability = capability; this.original = original;
    }
    boolean belongs(CallerIdentity caller, String namespace) {
        return caller.appId == appId && packageName.equals(caller.packageName)
                && certificates.equals(caller.certificateSha256) && Objects.equals(this.namespace, namespace);
    }
    ServiceInfo service(Engine engine) throws Exception {
        InstalledPackage current = engine.packages().get(packageName);
        if (current == null || current.appId != appId || !current.certificateSha256.equals(certificates)) {
            throw new SecurityException("Job creator installation/signers changed");
        }
        PackageInfo info = engine.packages().getPackageInfo(packageName, PackageManager.GET_SERVICES);
        if (info != null && info.services != null) for (ServiceInfo service : info.services) {
            if (service.name.equals(original.getService().getClassName()) && service.enabled && service.applicationInfo.enabled
                    && "android.permission.BIND_JOB_SERVICE".equals(service.permission)
                    && (service.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0) return service;
        }
        throw new SecurityException("Job requires a current enabled nonisolated BIND_JOB_SERVICE component");
    }
    JobInfo physical(Engine engine, OsPendingStore store) throws Exception {
        Bundle payload = new Bundle(); payload.putInt("version", 1); payload.putInt("sdk", Build.VERSION.SDK_INT);
        payload.putInt("boot", store.boot); payload.putString("package", packageName); payload.putInt("app_id", appId);
        payload.putStringArrayList("certificates", certificates); payload.putString("namespace", namespace);
        payload.putInt("physical_id", physicalId); payload.putString("capability", capability); payload.putParcelable("job", original);
        byte[] bytes = marshal(payload);
        PersistableBundle extras = new PersistableBundle();
        extras.putString(PAYLOAD, Base64.encodeToString(bytes, Base64.NO_WRAP));
        extras.putString(MAC, Base64.encodeToString(store.signJob(bytes), Base64.NO_WRAP));
        JobInfo physical = copy(original);
        RuntimeReflection.set(physical, "jobId", physicalId);
        RuntimeReflection.set(physical, "service", new ComponentName(engine.hostContext(), OsJobService.class));
        RuntimeReflection.set(physical, "extras", extras);
        return physical;
    }
    static JobEnvelope read(PersistableBundle extras, int physicalId, OsPendingStore store) throws Exception {
        if (extras == null || extras.size() != 2) throw new SecurityException("Missing OS job envelope");
        String encoded = extras.getString(PAYLOAD), signed = extras.getString(MAC);
        if (encoded == null || signed == null || encoded.length() > (MAX_BYTES * 4 / 3 + 8) || signed.length() > 48) {
            throw new SecurityException("Invalid OS job envelope size");
        }
        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP), tag = Base64.decode(signed, Base64.NO_WRAP);
        if (bytes.length > MAX_BYTES || tag.length != 32 || !MessageDigest.isEqual(store.signJob(bytes), tag)) {
            throw new SecurityException("Invalid OS job authentication");
        }
        Parcel parcel = Parcel.obtain(); Bundle value;
        try {
            parcel.unmarshall(bytes, 0, bytes.length); parcel.setDataPosition(0);
            value = parcel.readBundle(JobEnvelope.class.getClassLoader());
            if (parcel.dataAvail() != 0 || value == null) throw new SecurityException("Invalid OS job payload");
        } finally { parcel.recycle(); }
        if (value.getInt("version") != 1 || value.getInt("sdk") != Build.VERSION.SDK_INT
                || value.getInt("boot", -1) != store.boot || value.getInt("physical_id", -1) != physicalId) {
            throw new SecurityException("OS job version, boot or identity differs");
        }
        JobInfo job = value.getParcelable("job"); String owner = value.getString("package");
        ArrayList<String> certs = value.getStringArrayList("certificates"); String cap = value.getString("capability");
        if (job == null || owner == null || !owner.equals(job.getService().getPackageName()) || certs == null || certs.isEmpty()
                || cap == null || !cap.matches("[a-f0-9-]{36}")) throw new SecurityException("Incomplete OS job creator");
        validate(job); namespace(value.getString("namespace"));
        return new JobEnvelope(owner, value.getInt("app_id"), certs, value.getString("namespace"), physicalId, cap, job);
    }
    static void namespace(String namespace) {
        if (namespace != null && (namespace.isEmpty() || namespace.length() > 128 || !namespace.equals(namespace.trim()))) {
            throw new IllegalArgumentException("Invalid job namespace");
        }
    }
    static void validate(JobInfo job) throws Exception {
        if (job == null || job.getService() == null) throw new IllegalArgumentException("Missing job or service");
        if (job.isPersisted()) throw new UnsupportedOperationException("Reboot-persisted jobs are not implemented");
        if (!job.getTransientExtras().isEmpty()) throw new UnsupportedOperationException("Transient job extras are not implemented");
        if (job.getClipData() != null || job.getClipGrantFlags() != 0) throw new UnsupportedOperationException("Job URI grants are not implemented");
        if (job.getTriggerContentUris() != null && job.getTriggerContentUris().length != 0) throw new UnsupportedOperationException("Content-trigger jobs are not implemented");
        int flags = ((Number) RuntimeReflection.get(job, "flags")).intValue();
        int allowed = Build.VERSION.SDK_INT >= 31 ? 1 << 4 : 0; // Public expedited jobs retain Android's actual host-UID quota.
        if ((flags & ~allowed) != 0) throw new UnsupportedOperationException("Job flags are not implemented: 0x" + Integer.toHexString(flags));
    }
    static void validate(JobWorkItem work) {
        if (work == null) throw new IllegalArgumentException("Missing job work item");
        Intent intent = work.getIntent();
        if (intent != null && (intent.getClipData() != null || (intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) != 0)) {
            throw new UnsupportedOperationException("Job work URI grants are not implemented");
        }
        Parcel parcel = Parcel.obtain();
        try {
            work.writeToParcel(parcel, 0);
            if (parcel.hasFileDescriptors() || parcel.dataSize() > MAX_BYTES) throw new IllegalArgumentException("Invalid job work payload size or descriptor");
            parcel.marshall(); // Binder-bearing extras are unsupported before any OS enqueue mutation.
        } finally { parcel.recycle(); }
    }
    static JobInfo copy(JobInfo original) {
        Parcel parcel = Parcel.obtain();
        try { original.writeToParcel(parcel, 0); parcel.setDataPosition(0); return JobInfo.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }
    private static byte[] marshal(Bundle payload) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(payload);
            if (parcel.hasFileDescriptors() || parcel.dataSize() > MAX_BYTES) throw new IllegalArgumentException("Oversized job payload or file descriptor");
            return parcel.marshall();
        } finally { parcel.recycle(); }
    }
}
