package io.github.xmy953538104.mybox.engine.packages;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Own implementation of verified package generations; no VLite or BlackBox binary dependency. */
public final class PackageStore {
    private static final Object PROCESS_INSTALL_LOCK = new Object();
    private final Context context;
    private final File root;
    private final int platformSdk;
    private final String[] supportedAbis;

    public PackageStore(Context context) {
        // Preserve existing package generations/data across the Mylite product rename.
        this(stableContext(context), new File(context.getFilesDir(), "vlite"), Build.VERSION.SDK_INT,
                android.os.Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS);
    }

    private static Context stableContext(Context context) {
        // attachBaseContext runs before LoadedApk publishes the host Application.
        Context application = context.getApplicationContext();
        return application == null ? context : application;
    }

    PackageStore(Context context, File root, int platformSdk, String[] supportedAbis) {
        this.context = context;
        this.root = root;
        this.platformSdk = platformSdk;
        this.supportedAbis = supportedAbis.clone();
    }

    public InstalledPackage install(List<File> sources) throws IOException {
        return install(sources, null);
    }

    public InstalledPackage install(List<File> sources, String expectedPackageName) throws IOException {
        if (expectedPackageName != null) validateName(expectedPackageName);
        if (sources == null || sources.isEmpty() || sources.size() > 256) {
            throw new PackageValidationException("An installation requires 1 to 256 APKs");
        }
        synchronized (PROCESS_INSTALL_LOCK) {
            mkdir(root);
            try (RandomAccessFile lockFile = new RandomAccessFile(new File(root, "install.lock"), "rw");
                 FileLock ignored = lockFile.getChannel().lock()) {
                return installLocked(sources, expectedPackageName);
            }
        }
    }

    private InstalledPackage installLocked(List<File> sources, String expectedPackageName) throws IOException {
        String generation = UUID.randomUUID().toString();
        File staging = new File(root, "staging/" + generation);
        mkdir(staging);
        File unpublished = staging;
        try {
            List<VerifiedApk> apks = new ArrayList<>();
            for (int index = 0; index < sources.size(); index++) {
                File source = sources.get(index);
                if (source == null || !source.isFile()) throw new PackageValidationException("APK input is not a regular file");
                File copy = new File(staging, "input-" + index + ".apk");
                copyAndSync(source, copy);
                apks.add(VerifiedApk.read(copy, platformSdk));
            }
            Map<String, VerifiedApk> bySplit = validateSet(apks);
            VerifiedApk base = bySplit.get("");
            String name = base.manifest.packageName;
            if (expectedPackageName != null && !expectedPackageName.equals(name)) {
                throw new PackageValidationException("Session package name differs from the verified base APK");
            }
            if (context != null && name.equals(context.getPackageName())) {
                throw new PackageValidationException("Cannot install the host as a guest");
            }
            InstalledPackage previous = get(name);
            if (previous != null) {
                if (base.manifest.versionCode < previous.versionCode) throw new PackageValidationException("Version downgrade rejected");
                if (!previous.certificateSha256.equals(base.signers)) {
                    throw new PackageValidationException("Update signer differs from the installed package");
                }
            }
            if (context != null) {
                PackageInfo parsed = context.getPackageManager().getPackageArchiveInfo(base.file.getPath(),
                        PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS
                                | PackageManager.GET_RECEIVERS | PackageManager.GET_META_DATA);
                if (parsed == null || !name.equals(parsed.packageName)) {
                    throw new PackageValidationException("Android rejected the base package manifest");
                }
            }
            String abi = NativeLibraries.extract(apks, new File(staging, "lib"), supportedAbis);
            List<String> splitNames = new ArrayList<>();
            for (Map.Entry<String, VerifiedApk> entry : bySplit.entrySet()) {
                String split = entry.getKey();
                File destination = new File(staging, split.isEmpty() ? "base.apk" : "split_" + split + ".apk");
                atomicMove(entry.getValue().file, destination);
                if (!destination.setReadOnly()) throw new IOException("Cannot seal APK for loading");
                if (!split.isEmpty()) splitNames.add(split);
            }
            File version = new File(root, "packages/" + name + "/versions/" + generation);
            mkdir(version.getParentFile());
            atomicMove(staging, version);
            unpublished = version;
            File data = new File(root, "user/0/" + name);
            File deviceData = new File(root, "user_de/0/" + name);
            mkdir(data);
            mkdir(deviceData);
            List<File> splitFiles = new ArrayList<>();
            for (String split : splitNames) splitFiles.add(new File(version, "split_" + split + ".apk"));
            long now = System.currentTimeMillis();
            int appId = previous == null ? nextAppId() : previous.appId;
            InstalledPackage installed = new InstalledPackage(name, base.manifest.versionCode, appId,
                    new File(version, "base.apk"), splitFiles, splitNames, data, deviceData,
                    new File(version, "lib/" + (abi == null ? "none" : abi)), abi, base.signers,
                    previous == null ? now : previous.firstInstallTime, now, base.manifest.launchActivities);
            publish(installed, generation);
            unpublished = null;
            return installed;
        } finally {
            // Only this unpublished generation is removed. Existing code and user data are retained.
            if (unpublished != null) deleteTree(unpublished);
        }
    }

    static Map<String, VerifiedApk> validateSet(List<VerifiedApk> apks) throws IOException {
        Map<String, VerifiedApk> splits = new TreeMap<>();
        for (VerifiedApk apk : apks) {
            String split = apk.manifest.splitName == null ? "" : apk.manifest.splitName;
            if (splits.put(split, apk) != null) throw new PackageValidationException("Duplicate base or split APK");
        }
        VerifiedApk base = splits.get("");
        if (base == null) throw new PackageValidationException("A base APK is required");
        for (VerifiedApk apk : apks) {
            if (!base.manifest.packageName.equals(apk.manifest.packageName)
                    || base.manifest.versionCode != apk.manifest.versionCode
                    || !base.signers.equals(apk.signers)) {
                throw new PackageValidationException("APKs have different packages, versions, or signers");
            }
        }
        Set<String> complete = new HashSet<>();
        for (String split : splits.keySet()) visit(split, splits, new HashSet<>(), complete);
        return splits;
    }

    private static void visit(String split, Map<String, VerifiedApk> apks, Set<String> active, Set<String> complete) throws IOException {
        if (complete.contains(split)) return;
        if (!active.add(split)) throw new PackageValidationException("Cyclic split dependencies");
        ApkManifest manifest = apks.get(split).manifest;
        List<String> dependencies = new ArrayList<>(manifest.dependencies);
        if (manifest.configForSplit != null && !manifest.configForSplit.isEmpty()) dependencies.add(manifest.configForSplit);
        for (String dependency : dependencies) {
            if (!apks.containsKey(dependency)) throw new PackageValidationException("Required split is missing");
            visit(dependency, apks, active, complete);
        }
        active.remove(split);
        complete.add(split);
    }

    public InstalledPackage get(String packageName) throws IOException {
        validateName(packageName);
        File record = new File(root, "records/" + packageName + ".properties");
        if (!record.isFile()) return null;
        Properties values = new Properties();
        try (FileInputStream input = new FileInputStream(record)) { values.load(input); }
        try {
            if (!"1".equals(values.getProperty("format")) || !packageName.equals(values.getProperty("package"))) {
                throw new IOException("Unsupported or inconsistent package record");
            }
            String generation = values.getProperty("generation", "");
            if (!generation.matches("[a-f0-9-]{36}")) throw new IOException("Invalid package generation");
            File version = new File(root, "packages/" + packageName + "/versions/" + generation);
            File base = new File(version, "base.apk");
            if (!base.isFile()) throw new IOException("Committed base APK is missing");
            int splitCount = Integer.parseInt(values.getProperty("split.count"));
            if (splitCount < 0 || splitCount > 255) throw new IOException("Invalid split count");
            List<String> names = new ArrayList<>();
            List<File> files = new ArrayList<>();
            for (int index = 0; index < splitCount; index++) {
                String split = values.getProperty("split." + index, "");
                if (!split.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*") || names.contains(split)) throw new IOException("Invalid recorded split");
                File apk = new File(version, "split_" + split + ".apk");
                if (!apk.isFile()) throw new IOException("Committed split APK is missing");
                names.add(split);
                files.add(apk);
            }
            int signerCount = Integer.parseInt(values.getProperty("signer.count"));
            if (signerCount < 1 || signerCount > 32) throw new IOException("Invalid signer count");
            List<String> signers = new ArrayList<>();
            for (int index = 0; index < signerCount; index++) {
                String signer = values.getProperty("signer." + index, "");
                if (!signer.matches("[a-f0-9]{64}")) throw new IOException("Invalid recorded signer");
                signers.add(signer);
            }
            String abi = values.getProperty("abi", "");
            if (!abi.isEmpty() && !abi.matches("[a-zA-Z0-9_-]+")) throw new IOException("Invalid recorded ABI");
            int launcherCount = Integer.parseInt(values.getProperty("launcher.count"));
            if (launcherCount < 0 || launcherCount > 1024) throw new IOException("Invalid launcher count");
            List<String> launchers = new ArrayList<>();
            for (int index = 0; index < launcherCount; index++) {
                String launcher = values.getProperty("launcher." + index, "");
                if (launcher.isEmpty()) throw new IOException("Missing launcher name");
                launchers.add(launcher);
            }
            return new InstalledPackage(packageName, Long.parseLong(values.getProperty("version")),
                    Integer.parseInt(values.getProperty("appId")), base, files, names,
                    new File(root, "user/0/" + packageName), new File(root, "user_de/0/" + packageName),
                    new File(version, "lib/" + (abi.isEmpty() ? "none" : abi)), abi.isEmpty() ? null : abi,
                    signers, Long.parseLong(values.getProperty("firstInstallTime")),
                    Long.parseLong(values.getProperty("lastUpdateTime")), launchers);
        } catch (NumberFormatException failure) {
            throw new IOException("Corrupt package record", failure);
        }
    }

    public List<InstalledPackage> list() throws IOException {
        File directory = new File(root, "records");
        if (!directory.exists()) return Collections.emptyList();
        File[] records = directory.listFiles((parent, name) -> name.endsWith(".properties"));
        if (records == null) throw new IOException("Cannot enumerate package records");
        List<InstalledPackage> packages = new ArrayList<>();
        for (File record : records) {
            InstalledPackage item = get(record.getName().substring(0, record.getName().length() - 11));
            if (item != null) packages.add(item);
        }
        packages.sort(Comparator.comparing(item -> item.packageName));
        return Collections.unmodifiableList(packages);
    }

    public PackageInfo getPackageInfo(String packageName, int flags) throws IOException {
        if (context == null) throw new IllegalStateException("Android context is required for component metadata");
        InstalledPackage installed = get(packageName);
        if (installed == null) return null;
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(installed.baseApk.getPath(), flags);
        if (info == null || info.applicationInfo == null) throw new IOException("Cannot parse committed package metadata");
        ApplicationInfo application = info.applicationInfo;
        application.sourceDir = installed.baseApk.getPath();
        application.publicSourceDir = application.sourceDir;
        application.splitSourceDirs = installed.splitApks.isEmpty() ? null : new String[installed.splitApks.size()];
        if (application.splitSourceDirs != null) {
            for (int index = 0; index < installed.splitApks.size(); index++) application.splitSourceDirs[index] = installed.splitApks.get(index).getPath();
        }
        application.splitPublicSourceDirs = application.splitSourceDirs == null ? null : application.splitSourceDirs.clone();
        application.dataDir = installed.dataDir.getPath();
        application.deviceProtectedDataDir = installed.deviceProtectedDataDir.getPath();
        try {
            // Present since API 24, but absent from the public SDK. ContextImpl uses it for CE contexts.
            ApplicationInfo.class.getField("credentialProtectedDataDir").set(application, application.dataDir);
        } catch (ReflectiveOperationException | SecurityException denied) {
            throw new IOException("Runtime denied credential-protected package metadata", denied);
        }
        application.nativeLibraryDir = installed.nativeLibraryDir.getPath();
        application.uid = context.getApplicationInfo().uid;
        if (application.processName == null) application.processName = packageName;
        info.splitNames = installed.splitNames.isEmpty() ? null : installed.splitNames.toArray(new String[0]);
        application.splitNames = info.splitNames;
        info.firstInstallTime = installed.firstInstallTime;
        info.lastUpdateTime = installed.lastUpdateTime;
        attach(info.activities, application);
        attach(info.services, application);
        attach(info.providers, application);
        attach(info.receivers, application);
        return info;
    }

    public ActivityInfo findLaunchActivity(String packageName) throws IOException {
        InstalledPackage installed = get(packageName);
        if (installed == null || installed.launchActivities.isEmpty()) return null;
        PackageInfo info = getPackageInfo(packageName, PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
        if (info.activities != null && info.applicationInfo.enabled) {
            for (String candidate : installed.launchActivities) {
                for (ActivityInfo activity : info.activities) {
                    if (activity.enabled && candidate.equals(activity.name)) return activity;
                }
            }
        }
        return null;
    }

    private static void attach(ComponentInfo[] components, ApplicationInfo application) {
        if (components == null) return;
        for (ComponentInfo component : components) component.applicationInfo = application;
    }

    private int nextAppId() throws IOException {
        int id = 10000;
        for (InstalledPackage installed : list()) id = Math.max(id, installed.appId + 1);
        if (id >= 100000) throw new IOException("Virtual app ID range exhausted");
        return id;
    }

    private void publish(InstalledPackage installed, String generation) throws IOException {
        Properties values = new Properties();
        values.setProperty("format", "1");
        values.setProperty("package", installed.packageName);
        values.setProperty("generation", generation);
        values.setProperty("version", Long.toString(installed.versionCode));
        values.setProperty("appId", Integer.toString(installed.appId));
        values.setProperty("firstInstallTime", Long.toString(installed.firstInstallTime));
        values.setProperty("lastUpdateTime", Long.toString(installed.lastUpdateTime));
        values.setProperty("abi", installed.primaryAbi == null ? "" : installed.primaryAbi);
        values.setProperty("launcher.count", Integer.toString(installed.launchActivities.size()));
        for (int index = 0; index < installed.launchActivities.size(); index++) values.setProperty("launcher." + index, installed.launchActivities.get(index));
        values.setProperty("split.count", Integer.toString(installed.splitNames.size()));
        for (int index = 0; index < installed.splitNames.size(); index++) values.setProperty("split." + index, installed.splitNames.get(index));
        values.setProperty("signer.count", Integer.toString(installed.certificateSha256.size()));
        for (int index = 0; index < installed.certificateSha256.size(); index++) values.setProperty("signer." + index, installed.certificateSha256.get(index));
        File records = new File(root, "records");
        mkdir(records);
        File temporary = new File(records, installed.packageName + "." + generation + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                values.store(output, "Mybox Mylite package record");
                output.getFD().sync();
            }
            atomicMove(temporary, new File(records, installed.packageName + ".properties"));
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary.toPath()); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void validateName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            throw new IOException("Invalid package name");
        }
    }

    private static void mkdir(File directory) throws IOException {
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Cannot create package directory");
    }

    private static void copyAndSync(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static void atomicMove(File source, File destination) throws IOException {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteTree(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Unexpected link in package staging");
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists()) file.setWritable(true);
        Files.deleteIfExists(file.toPath());
    }
}
