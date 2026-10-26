package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

final class StorageProbe {
    static void run(Activity activity) {
        JSONObject result = new JSONObject();
        try {
            StorageManager manager = activity.getSystemService(StorageManager.class);
            StorageVolume primary = manager.getPrimaryStorageVolume();
            StatFs data = new StatFs(activity.getFilesDir().getAbsolutePath());
            StatFs external = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            result.put("package", activity.getPackageName()).put("volumeCount", manager.getStorageVolumes().size())
                    .put("primary", primary.isPrimary()).put("volumeEmulated", primary.isEmulated())
                    .put("environmentEmulated", Environment.isExternalStorageEmulated())
                    .put("volumeState", primary.getState()).put("environmentState", Environment.getExternalStorageState())
                    .put("dataTotal", data.getTotalBytes()).put("dataAvailable", data.getAvailableBytes())
                    .put("externalTotal", external.getTotalBytes()).put("externalAvailable", external.getAvailableBytes());
            result.put("allocatable",manager.getAllocatableBytes(StorageManager.UUID_DEFAULT))
                    .put("cacheQuota",manager.getCacheQuotaBytes(StorageManager.UUID_DEFAULT))
                    .put("cacheSize",manager.getCacheSizeBytes(StorageManager.UUID_DEFAULT));
        } catch (Exception failure) {
            try { result.put("error", failure.getClass().getName()).put("message", failure.getMessage()); }
            catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
        }
        try {
            File pending = new File(activity.getFilesDir(), "storage-result.tmp");
            Files.write(pending.toPath(), result.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(pending.toPath(), new File(activity.getFilesDir(), "storage-result.json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { throw new IllegalStateException("Cannot publish actual storage observation", failure); }
    }
}
