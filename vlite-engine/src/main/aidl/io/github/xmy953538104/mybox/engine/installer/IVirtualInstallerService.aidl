package io.github.xmy953538104.mybox.engine.installer;

import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import io.github.xmy953538104.mybox.engine.installer.IVirtualInstallerCallback;

interface IVirtualInstallerService {
    Bundle execute(String operation, in Bundle request, IVirtualInstallerCallback callback);
    ParcelFileDescriptor openFile(String opPackage, int sessionId, String name, long offset,
            long length, boolean writing, IBinder lifetime);
}
