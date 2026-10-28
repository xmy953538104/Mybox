package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Ordinary guest with no dependency on any container engine. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String mode = "idle";
        File request = new File(getFilesDir(), "fixture-mode");
        try {
            if (request.isFile()) {
                mode = new String(java.nio.file.Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8);
                if (!request.delete()) throw new java.io.IOException("Cannot consume fixture mode");
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        // Unrequested OS recreation must not start another probe or navigate over the next test.
        if ("idle".equals(mode)) {
            TextView idle = new TextView(this); idle.setText("Fixture idle"); setContentView(idle); return;
        }
        if (mode.startsWith("native-path:")) android.util.Log.i("MylitePathProbe", "mode-selected " + mode);
        JSONObject report = new JSONObject();
        try {
            report.put("package", getPackageName());
            report.put("pid", android.os.Process.myPid());
            report.put("dataDir", getApplicationInfo().dataDir);
            report.put("applicationCreated", new File(getFilesDir(), "application-created.txt").isFile());
            report.put("providerCreated", new File(getFilesDir(), "provider-created.txt").isFile());
            if ("components".equals(mode)) {
                android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
                report.put("batteryQueryOwn", power.isIgnoringBatteryOptimizations(getPackageName()));
                android.content.Context separate = createConfigurationContext(new android.content.res.Configuration(getResources().getConfiguration()));
                report.put("batteryQueryNewContext", separate.getSystemService(android.os.PowerManager.class)
                        .isIgnoringBatteryOptimizations(separate.getPackageName()));
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.hardware.biometrics.BiometricManager biometric = getSystemService(android.hardware.biometrics.BiometricManager.class);
                    report.put("biometricOwn", biometric.canAuthenticate());
                    report.put("biometricCredential", biometric.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL));
                    report.put("biometricNewContext", separate.getSystemService(android.hardware.biometrics.BiometricManager.class).canAuthenticate());
                }
                android.content.pm.PackageManager packages = getPackageManager();
                report.put("installedPackages", inventorySnapshot(packages));
                report.put("installedApplications", applicationNames(packages.getInstalledApplications(0)));
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    report.put("installedPackagesLongFlags", packageNames(packages.getInstalledPackages(
                            android.content.pm.PackageManager.PackageInfoFlags.of(0x00400000L))));
                    report.put("installedApplicationsLongFlags", applicationNames(packages.getInstalledApplications(
                            android.content.pm.PackageManager.ApplicationInfoFlags.of(0x00400000L))));
                }
                report.put("sharedLibraries", librarySnapshot(packages.getSharedLibraries(0)));
                report.put("sharedLibrariesStaticFlag", librarySnapshot(packages.getSharedLibraries(0x00400000)));
                if (android.os.Build.VERSION.SDK_INT >= 33) report.put("sharedLibrariesLongFlags",
                        librarySnapshot(packages.getSharedLibraries(android.content.pm.PackageManager.PackageInfoFlags.of(0x00400000L))));
            }
            File file = new File(getFilesDir(), "ordinary-file.txt");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(23); out.getFD().sync(); }
            report.put("contextFile", file.isFile());
            try (SQLiteDatabase database = openOrCreateDatabase("fixture.db", MODE_PRIVATE, null)) {
                database.execSQL("CREATE TABLE IF NOT EXISTS state(value INTEGER)");
                database.execSQL("INSERT INTO state VALUES(42)");
                report.put("sqlite", android.database.DatabaseUtils.longForQuery(database,
                        "SELECT count(*) FROM state WHERE value=42", null) > 0);
            }
            try {
                File nativeFile = new File("/data/data/" + getPackageName() + "/files/native-route.txt");
                try (FileOutputStream out = new FileOutputStream(nativeFile)) { out.write(41); out.getFD().sync(); }
                report.put("hardcodedPath", nativeFile.isFile());
            } catch (Exception failure) { report.put("hardcodedPath", false); }
        } catch (Throwable failure) {
            try {
                report.put("failure", failure.getClass().getName());
                java.io.StringWriter trace = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                report.put("failureTrace", trace.toString());
            } catch (Exception ignored) { }
        }
        try (FileOutputStream out = openFileOutput("engine-fixture.json.tmp", MODE_PRIVATE)) {
            out.write(report.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        if (!new File(getFilesDir(), "engine-fixture.json.tmp").renameTo(new File(getFilesDir(), "engine-fixture.json"))) throw new IllegalStateException("Cannot publish fixture report");
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView text = new TextView(this);
        text.setText(report.toString());
        body.addView(text);
        Button next = new Button(this);
        next.setText("Open second guest Activity");
        next.setOnClickListener(view -> startActivity(new Intent(this, SecondActivity.class)));
        body.addView(next);
        setContentView(body);
        if (mode.startsWith("service-alarm:")) {
            io.github.xmy953538104.mybox.fixture.alarm.ServiceAlarmProbe.run(this, mode.substring("service-alarm:".length()));
            return;
        }
        if (mode.startsWith("activity-health:")) {
            publishActivityHealth(mode.substring("activity-health:".length()));
            return;
        }
        if (mode.startsWith("native-path:")) {
            android.util.Log.i("MylitePathProbe", "activity-dispatch " + mode);
            PathOperationsProbe.run(this, mode.substring("native-path:".length()));
            return;
        }
        if (mode.startsWith("exit-history-seed:")) {
            if (android.os.Build.VERSION.SDK_INT < 30) throw new UnsupportedOperationException("OS exit history requires API 30");
            ExitHistoryProbe.run(this, mode.substring("exit-history-seed:".length()), true);
            return;
        }
        if (mode.startsWith("exit-history:")) {
            if (android.os.Build.VERSION.SDK_INT < 30) throw new UnsupportedOperationException("OS exit history requires API 30");
            ExitHistoryProbe.run(this, mode.substring("exit-history:".length()), false);
            return;
        }
        if (mode.startsWith("telephony-registry:")) {
            TelephonyRegistryProbe.run(this, mode.substring("telephony-registry:".length()));
            return;
        }
        if (mode.startsWith("package-cache:")) {
            PackageCacheProbe.run(this, mode.substring("package-cache:".length()));
            return;
        }
        if (mode.startsWith("account-error-gate:")) {
            io.github.xmy953538104.mybox.fixture.accounts.AccountErrorGateProbe.run(this, mode.substring("account-error-gate:".length()));
            return;
        }
        if (mode.startsWith("jobs-stop-finish:")) {
            io.github.xmy953538104.mybox.fixture.jobs.JobProbe.runStopFinishRace(this, mode.substring("jobs-stop-finish:".length()));
            return;
        }
        if (mode.startsWith("bound-services:")) {
            BoundServiceProbe.run(this, mode.substring("bound-services:".length()));
            return;
        }
        switch (mode) {
            case "notification-pending": io.github.xmy953538104.mybox.fixture.pending.NotificationPendingProbe.run(this); break;
            case "activity-pending": io.github.xmy953538104.mybox.fixture.pending.ActivityPendingProbe.run(this); break;
            case "locales-set": case "locales-reset":
                startActivity(new Intent(this, LocaleProbe.class).putExtra("reset", mode.equals("locales-reset"))); break;
            case "web-input": WebInputProbe.run(this); break;
            case "windows": WindowProbe.run(this); break;
            case "capacity": CapacityProbe.run(this); break;
            case "connectivity": ConnectivityProbe.run(this); break;
            case "storage": StorageProbe.run(this); break;
            case "jobs": io.github.xmy953538104.mybox.fixture.jobs.JobProbe.run(this); break;
            case "jobs-recover": io.github.xmy953538104.mybox.fixture.jobs.JobProbe.runRecovery(this); break;
            case "jobs-expedited": io.github.xmy953538104.mybox.fixture.jobs.JobProbe.runExpedited(this); break;
            case "jobs-important": io.github.xmy953538104.mybox.fixture.jobs.JobProbe.runImportant(this); break;
            case "jobs-persisted": io.github.xmy953538104.mybox.fixture.jobs.JobProbe.runPersisted(this); break;
            case "component-state": ComponentStateProbe.run(this, false); break;
            case "component-state-resume": ComponentStateProbe.run(this, true); break;
            case "content-observers":
                io.github.xmy953538104.mybox.fixture.content.ObserverProbe.run(this);
                break;
            case "clipboard":
                io.github.xmy953538104.mybox.fixture.clipboard.ClipboardProbe.run(this);
                break;
            case "alarms":
                io.github.xmy953538104.mybox.fixture.alarm.AlarmProbe.run(this, false);
                break;
            case "alarm-recover":
                io.github.xmy953538104.mybox.fixture.alarm.AlarmProbe.run(this, true);
                break;
            case "pending":
                io.github.xmy953538104.mybox.fixture.pending.PendingProbe.run(this, () -> { });
                break;
            case "accounts":
                io.github.xmy953538104.mybox.fixture.accounts.AccountProbe.run(this);
                break;
            case "installer":
                io.github.xmy953538104.mybox.fixture.installer.InstallerProbe.run(this);
                break;
            case "installer-application-open":
                io.github.xmy953538104.mybox.fixture.installer.InstallerProbe.openFromApplication(this);
                break;
            case "broadcast":
                io.github.xmy953538104.mybox.fixture.broadcast.BroadcastProbe.run(this);
                break;
            case "broadcast-permissions":
                io.github.xmy953538104.mybox.fixture.broadcast.permissions.PermissionProbe.run(this);
                break;
            case "components":
                ComponentProbe.run(this);
                new android.os.Handler(getMainLooper()).post(() -> startActivity(new Intent(this, SecondActivity.class).putExtra("probe", 42)));
                break;
            case "providers": break;
            case "settings": SettingsProbe.run(this); break;
            case "foreground":
                io.github.xmy953538104.mybox.fixture.foreground.ForegroundProbe.run(this);
                break;
            case "service-pending":
                io.github.xmy953538104.mybox.fixture.pending.ServicePendingProbe.run(this);
                break;
            case "activities":
                io.github.xmy953538104.mybox.fixture.activities.ActivityProbe.start(this);
                break;
            default: throw new IllegalStateException("Unknown fixture mode: " + mode);
        }
    }

    private void publishActivityHealth(String nonce) {
        if (!java.util.UUID.fromString(nonce).toString().equals(nonce)) throw new IllegalArgumentException("Invalid activity health nonce");
        if (android.os.Looper.myLooper() != getMainLooper()) throw new IllegalStateException("Activity health must run on the main thread");
        String name = "activity-health-" + nonce + ".json";
        try {
            JSONObject result = new JSONObject();
            result.put("nonce", nonce); result.put("pid", android.os.Process.myPid()); result.put("main_thread", true);
            try (FileOutputStream out = openFileOutput(name + ".tmp", MODE_PRIVATE)) {
                out.write(result.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            if (!new File(getFilesDir(), name + ".tmp").renameTo(new File(getFilesDir(), name))) throw new java.io.IOException("Cannot publish activity health");
        } catch (Exception failure) { throw new IllegalStateException("Cannot report activity health", failure); }
    }

    private static org.json.JSONArray librarySnapshot(java.util.List<android.content.pm.SharedLibraryInfo> libraries) {
        if (libraries == null) throw new IllegalStateException("Public library query returned null");
        java.util.List<String> values = new java.util.ArrayList<>();
        for (android.content.pm.SharedLibraryInfo library : libraries)
            values.add(library.getName() + ":" + library.getType() + ":" + library.getLongVersion());
        java.util.Collections.sort(values); return new org.json.JSONArray(values);
    }

    private org.json.JSONArray inventorySnapshot(android.content.pm.PackageManager manager) throws Exception {
        int flags = android.content.pm.PackageManager.GET_SIGNATURES | android.content.pm.PackageManager.GET_SERVICES;
        java.util.List<android.content.pm.PackageInfo> packages = manager.getInstalledPackages(flags);
        android.content.pm.PackageInfo self = null;
        for (android.content.pm.PackageInfo info : packages) {
            android.content.pm.PackageInfo direct = manager.getPackageInfo(info.packageName, flags);
            if (info.versionCode != direct.versionCode || !info.applicationInfo.sourceDir.equals(direct.applicationInfo.sourceDir)
                    || !java.util.Arrays.equals(info.signatures, direct.signatures)) throw new AssertionError("Inventory metadata differs from direct lookup");
            if (getPackageName().equals(info.packageName)) self = info;
        }
        if (self == null || self.services == null || self.signatures == null) throw new AssertionError("Own installed package metadata is absent");
        self.applicationInfo.sourceDir = "/modified/by/caller";
        self.services[0].name = "modified.by.caller";
        self.signatures[0] = new android.content.pm.Signature(new byte[]{1, 2, 3});
        java.util.List<android.content.pm.PackageInfo> refreshed = manager.getInstalledPackages(flags);
        if (!packageNames(packages).toString().equals(packageNames(refreshed).toString())) throw new AssertionError("Caller mutation changed inventory membership");
        for (android.content.pm.PackageInfo fresh : refreshed) {
            if (getPackageName().equals(fresh.packageName) && (fresh.applicationInfo.sourceDir.equals("/modified/by/caller")
                    || fresh.services[0].name.equals("modified.by.caller") || fresh.signatures[0].toByteArray().length == 3))
                throw new AssertionError("Caller mutation persisted in inventory");
        }
        for (android.content.pm.PackageInfo plain : manager.getInstalledPackages(0))
            if (plain.signatures != null || plain.services != null) throw new AssertionError("Unrequested inventory metadata leaked");
        if (!manager.getInstalledPackages(android.content.pm.PackageManager.MATCH_SYSTEM_ONLY).isEmpty()
                || !manager.getInstalledApplications(android.content.pm.PackageManager.MATCH_SYSTEM_ONLY).isEmpty())
            throw new AssertionError("User-imported guests appeared as physical system applications");
        try {
            manager.getPackageInfo(getPackageName(), android.content.pm.PackageManager.MATCH_SYSTEM_ONLY);
            throw new AssertionError("Direct system-only lookup returned a user-imported guest");
        } catch (android.content.pm.PackageManager.NameNotFoundException expected) { }
        return packageNames(packages);
    }

    private static org.json.JSONArray packageNames(java.util.List<android.content.pm.PackageInfo> packages) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (android.content.pm.PackageInfo info : packages) names.add(info.packageName);
        java.util.Collections.sort(names); return new org.json.JSONArray(names);
    }

    private static org.json.JSONArray applicationNames(java.util.List<android.content.pm.ApplicationInfo> applications) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (android.content.pm.ApplicationInfo info : applications) names.add(info.packageName);
        java.util.Collections.sort(names); return new org.json.JSONArray(names);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        io.github.xmy953538104.mybox.fixture.activities.ActivityProbe.result(this, request, result, data);
    }
}
