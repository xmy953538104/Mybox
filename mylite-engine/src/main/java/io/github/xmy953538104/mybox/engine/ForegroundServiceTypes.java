package io.github.xmy953538104.mybox.engine;

import android.content.pm.ServiceInfo;
import android.os.Build;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Physical types describe the actual host operation, never a guest's claimed OS exemption. */
final class ForegroundServiceTypes {
    static int resolve(Engine engine, ServiceInfo info, int requested) throws Exception {
        int declared = Build.VERSION.SDK_INT >= 29 ? info.getForegroundServiceType() : 0;
        int selected = requested == -1 ? declared : requested;
        if (requested == -1 && info.packageName.equals("com.google.android.gms")
                && (info.name.equals("org.microg.gms.checkin.CheckinService") || info.name.equals("org.microg.gms.gcm.McsService"))) {
            TrustedMicrog.require(engine.hostContext(), engine.packages().get(info.packageName));
            selected = info.name.equals("org.microg.gms.gcm.McsService") && Build.VERSION.SDK_INT >= 34
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        } else if (requested == -1 && info.packageName.equals("com.android.vending")
                && (info.name.equals("com.google.android.finsky.downloadservice.DownloadService")
                    || info.name.equals("com.google.android.finsky.setup.VpaService"))) {
            InstalledPackage installed = engine.packages().get(info.packageName);
            if (!InstallerPolicy.isGoogleStore(engine.packages(), installed)) {
                throw new SecurityException("Setup/download Service does not have Google's verified signing identity");
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
