package io.github.xmy953538104.mybox.engine;

import android.content.pm.ServiceInfo;
import android.os.Build;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Physical types describe the actual host operation, never a guest's claimed OS exemption. */
final class ForegroundServiceTypes {
    private static final String GOOGLE_CERT = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83";
    static int resolve(Engine engine, ServiceInfo info, int requested) throws Exception {
        int declared = Build.VERSION.SDK_INT >= 29 ? info.getForegroundServiceType() : 0;
        int selected = requested == -1 ? declared : requested;
        if (requested == -1 && info.packageName.equals("com.google.android.gms")
                && (info.name.equals("org.microg.gms.checkin.CheckinService") || info.name.equals("org.microg.gms.gcm.McsService"))) {
            TrustedMicrog.require(engine.hostContext(), engine.packages().get(info.packageName));
            selected = info.name.equals("org.microg.gms.gcm.McsService") && Build.VERSION.SDK_INT >= 34
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        } else if (requested == -1 && info.packageName.equals("com.android.vending")
                && info.name.equals("com.google.android.finsky.downloadservice.DownloadService")) {
            InstalledPackage installed = engine.packages().get(info.packageName);
            if (installed == null || installed.certificateSha256.size() != 1 || !installed.certificateSha256.contains(GOOGLE_CERT)) {
                throw new SecurityException("Download Service does not have Google's verified signing identity");
            }
            selected = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        } else if (selected != 0 && selected != ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
            throw new UnsupportedOperationException("This guest foreground Service type is not implemented");
        }
        if (requested != -1 && (requested & ~declared) != 0) throw new IllegalArgumentException("Foreground type is absent from the guest manifest");
        return selected;
    }
    private ForegroundServiceTypes() { }
}
