package io.github.xmy953538104.mybox.engine.packages;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A committed, immutable package generation. User data lives outside that generation. */
public final class InstalledPackage {
    public final String packageName;
    public final String installerPackageName;
    public final long versionCode;
    public final int appId;
    public final File baseApk;
    public final List<File> splitApks;
    public final List<String> splitNames;
    public final File dataDir;
    public final File deviceProtectedDataDir;
    public final File nativeLibraryDir;
    public final String primaryAbi;
    public final List<String> certificateSha256;
    public final long firstInstallTime;
    public final long lastUpdateTime;
    public final String launchActivity;
    public final List<String> launchActivities;

    InstalledPackage(String packageName, long versionCode, int appId, File baseApk,
                     List<File> splitApks, List<String> splitNames, File dataDir,
                     File deviceProtectedDataDir, File nativeLibraryDir, String primaryAbi,
                     List<String> certificates, long firstInstallTime, long lastUpdateTime,
                     List<String> launchActivities, String installerPackageName) {
        this.packageName = packageName;
        this.installerPackageName = installerPackageName;
        this.versionCode = versionCode;
        this.appId = appId;
        this.baseApk = baseApk;
        this.splitApks = Collections.unmodifiableList(new ArrayList<>(splitApks));
        this.splitNames = Collections.unmodifiableList(new ArrayList<>(splitNames));
        this.dataDir = dataDir;
        this.deviceProtectedDataDir = deviceProtectedDataDir;
        this.nativeLibraryDir = nativeLibraryDir;
        this.primaryAbi = primaryAbi;
        this.certificateSha256 = Collections.unmodifiableList(new ArrayList<>(certificates));
        this.firstInstallTime = firstInstallTime;
        this.lastUpdateTime = lastUpdateTime;
        this.launchActivities = Collections.unmodifiableList(new ArrayList<>(launchActivities));
        this.launchActivity = launchActivities.isEmpty() ? null : launchActivities.get(0);
    }
}
