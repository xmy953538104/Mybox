// IBPackageManagerService.aidl
package top.niunaijun.blackbox.core.system.pm;

// Declare any non-default types here with import statements
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.ProviderInfo;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.util.List;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstalledPackage;


interface IBPackageManagerService {
    ResolveInfo resolveService(in Intent intent, int flags, String resolvedType, int userId);
    ResolveInfo resolveActivity(in Intent intent, int flags, String resolvedType, int userId);
    ProviderInfo resolveContentProvider(String authority, int flag, int userId);
    ResolveInfo resolveIntent(in Intent intent, String resolvedType, int flags, int userId);

    ApplicationInfo getApplicationInfo(String packageName, int flags, int userId);
    PackageInfo getPackageInfo(String packageName, int flags, int userId);
    ServiceInfo getServiceInfo(in ComponentName component, int flags, int userId);
    ActivityInfo getReceiverInfo(in ComponentName componentName, int flags, int userId);
    ActivityInfo getActivityInfo(in ComponentName component, int flags, int userId);
    ProviderInfo getProviderInfo(in ComponentName component, int flags, int userId);
    List<ApplicationInfo> getInstalledApplications(int flags, int userId);
    List<PackageInfo> getInstalledPackages(int flags, int userId);

    List<ResolveInfo> queryIntentActivities(in Intent intent, int flags, String resolvedType, int userId);
    List<ResolveInfo> queryBroadcastReceivers(in Intent intent, int flags, String resolvedType, int userId);
    List<ResolveInfo> queryIntentServices(in Intent intent, int flags, int userId);
    List<ProviderInfo> queryContentProviders(String processName, int uid, int flags, int userId);

    InstallResult installPackageAsUser(String file, in InstallOption option, int userId);
    InstallResult installPackageSetAsUser(in String[] files, in InstallOption option, int userId, String installerPackageName);
    Bundle installerCall(String operation, in Bundle arguments);
    ParcelFileDescriptor openSessionWrite(int sessionId, String name, long offset, long length, boolean fileBridge);
    ParcelFileDescriptor openSessionRead(int sessionId, String name);
    String getInstallerPackageName(String packageName, int userId);
    boolean isTrustedMicroG(String packageName, int userId);
    int getComponentEnabledSetting(in ComponentName component, int userId);
    void setComponentEnabledSetting(in ComponentName component, int state, int flags, int userId);
    void uninstallPackageAsUser(String packageName, int userId);
    void uninstallPackage(String packageName);
    void clearPackage(String packageName, int userId);
    void stopPackage(String packageName, int userId);
    void deleteUser(int userId);

    boolean isInstalled(String packageName, int userId);
    List<InstalledPackage> getInstalledPackagesAsUser(int userId);
    String[] getPackagesForUid(int uid, int userId);
}
