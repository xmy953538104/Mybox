package io.github.xmy953538104.mybox.engine.packages;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Parcel;
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
import java.util.LinkedHashMap;
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
    private final Map<String, SigningCache> signingCache = new LinkedHashMap<String, SigningCache>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, SigningCache> eldest) { return size() > 8; }
    };
    // Inventory scans need more than eight views; bound immutable bytes while installed state stays fresh.
    private final android.util.LruCache<String, byte[]> archiveMetadata = new android.util.LruCache<String, byte[]>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, byte[] value) { return value.length; }
    };

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
        return install(sources, expectedPackageName, null);
    }

    public InstalledPackage install(List<File> sources, String expectedPackageName,
                                    io.github.xmy953538104.mybox.engine.CallerIdentity installer) throws IOException {
        return install(sources, expectedPackageName, installer, null);
    }

    /** Checks the copied/verified publisher before publication, not a mutable source-file preflight. */
    public InstalledPackage installWithPublisher(List<File> sources, String expectedPackageName,
                                                java.util.function.Predicate<SigningIdentity> publisher) throws IOException {
        if (publisher == null) throw new IllegalArgumentException("Publisher policy is missing");
        return install(sources, expectedPackageName, null, publisher);
    }

    private InstalledPackage install(List<File> sources, String expectedPackageName,
                                     io.github.xmy953538104.mybox.engine.CallerIdentity installer,
                                     java.util.function.Predicate<SigningIdentity> publisher) throws IOException {
        if (expectedPackageName != null) validateName(expectedPackageName);
        if (sources == null || sources.isEmpty() || sources.size() > 256) {
            throw new PackageValidationException("An installation requires 1 to 256 APKs");
        }
        synchronized (PROCESS_INSTALL_LOCK) {
            mkdir(root);
            try (RandomAccessFile lockFile = new RandomAccessFile(new File(root, "install.lock"), "rw");
                 FileLock ignored = lockFile.getChannel().lock()) {
                if (installer != null) {
                    InstalledPackage owner = get(installer.packageName);
                    if (owner == null || owner.appId != installer.appId || !owner.certificateSha256.equals(installer.certificateSha256)) {
                        throw new SecurityException("Installer identity changed before package publication");
                    }
                }
                return installLocked(sources, expectedPackageName, installer == null ? null : installer.packageName, publisher);
            }
        }
    }

    private InstalledPackage installLocked(List<File> sources, String expectedPackageName, String installerPackage,
                                           java.util.function.Predicate<SigningIdentity> publisher) throws IOException {
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
            if (publisher != null && !publisher.test(base.signing))
                throw new SecurityException("APK publisher is not approved for this import");
            if (context != null && name.equals(context.getPackageName())) {
                throw new PackageValidationException("Cannot install the host as a guest");
            }
            InstalledPackage previous = get(name);
            if (previous != null) {
                if (base.manifest.versionCode < previous.versionCode) throw new PackageValidationException("Version downgrade rejected");
                if (!base.signing.canUpdate(verifiedSigning(previous))) {
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
                    previous == null ? now : previous.firstInstallTime, now, base.manifest.launchActivities, installerPackage);
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
                    || !base.signing.sameSplitSigner(apk.signing)) {
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
            String installer = values.getProperty("installer.package");
            if (installer != null) validateName(installer);
            return new InstalledPackage(packageName, Long.parseLong(values.getProperty("version")),
                    Integer.parseInt(values.getProperty("appId")), base, files, names,
                    new File(root, "user/0/" + packageName), new File(root, "user_de/0/" + packageName),
                    new File(version, "lib/" + (abi.isEmpty() ? "none" : abi)), abi.isEmpty() ? null : abi,
                    signers, Long.parseLong(values.getProperty("firstInstallTime")),
                    Long.parseLong(values.getProperty("lastUpdateTime")), launchers, installer);
        } catch (NumberFormatException failure) {
            throw new IOException("Corrupt package record", failure);
        }
    }

    /** Source-archive proof only; committed role checks must use verifiedSigning below. */
    public SigningIdentity verifyArchive(File file) throws IOException { return VerifiedApk.read(file, platformSdk).signing; }

    /** Records locate files, but actual sealed APK verification establishes publisher authority. */
    public SigningIdentity verifiedSigning(InstalledPackage expected) throws IOException {
        requireCurrentRecord(expected);
        String key = expected.baseApk.getAbsolutePath();
        SigningCache cached;
        synchronized (signingCache) {
            cached = signingCache.get(key);
            if (cached == null) { cached = new SigningCache(); signingCache.put(key, cached); }
        }
        // Deduplicate cold reads per generation without holding the cache/registry lock during I/O.
        synchronized (cached) { return verifiedSigning(expected, cached); }
    }

    private SigningIdentity verifiedSigning(InstalledPackage expected, SigningCache cached) throws IOException {
        requireCurrentRecord(expected);
        List<File> files = new ArrayList<>(); files.add(expected.baseApk); files.addAll(expected.splitApks);
        List<SealedApkIdentity> before = witnesses(files);
        long clock = clockOffset();
        if (cached.matches(expected, before, clock)) {
            requireCurrentRecord(expected);
            return cached.signing;
        }
        // No registry/cache lock is held while reading an entire APK or its signature proof.
        List<VerifiedApk> apks = new ArrayList<>();
        for (File file : files) apks.add(VerifiedApk.readSealed(file, platformSdk, context != null));
        Map<String, VerifiedApk> set = validateSet(apks);
        VerifiedApk base = set.get("");
        List<String> actualSplits = new ArrayList<>(set.keySet()); actualSplits.remove("");
        // shortcut: SDK-selected current signer changes need explicit record/account-ACL migration after an OS upgrade.
        if (base.manifest.splitName != null || !expected.packageName.equals(base.manifest.packageName)
                || expected.versionCode != base.manifest.versionCode || !expected.certificateSha256.equals(base.signers)
                || !expected.splitNames.equals(actualSplits) || !apks.get(0).file.equals(base.file))
            throw new SecurityException("Package record differs from the verified APK set");
        for (int i = 1; i < apks.size(); i++) if (!expected.splitNames.get(i - 1).equals(apks.get(i).manifest.splitName))
            throw new SecurityException("Committed split filename differs from its verified manifest");
        List<SealedApkIdentity> after = witnesses(files);
        for (int i = 0; i < files.size(); i++) {
            if (!before.get(i).stamp.equals(apks.get(i).sealed.stamp)
                    || !before.get(i).stamp.equals(after.get(i).stamp))
                throw new SecurityException("APK set changed during verification");
        }
        requireCurrentRecord(expected);
        cached.update(base.signing, expected.splitNames, after, clock);
        return base.signing;
    }

    private List<SealedApkIdentity> witnesses(List<File> files) throws IOException {
        List<SealedApkIdentity> result = new ArrayList<>();
        for (File file : files) result.add(SealedApkIdentity.read(file, context != null));
        return result;
    }

    private void requireCurrentRecord(InstalledPackage expected) throws IOException {
        if (expected == null) throw new SecurityException("Package is not installed");
        InstalledPackage current = get(expected.packageName);
        if (current == null || !current.baseApk.equals(expected.baseApk) || current.appId != expected.appId
                || current.versionCode != expected.versionCode || !current.splitNames.equals(expected.splitNames)
                || !current.splitApks.equals(expected.splitApks) || !current.certificateSha256.equals(expected.certificateSha256))
            throw new SecurityException("Installed package generation changed");
    }

    private long clockOffset() {
        return System.currentTimeMillis() - (context == null ? System.nanoTime() / 1_000_000L : android.os.SystemClock.elapsedRealtime());
    }

    private static final class SigningCache {
        SigningIdentity signing;
        List<String> splits;
        List<SealedApkIdentity> witnesses;
        long clock;
        void update(SigningIdentity signing, List<String> splits, List<SealedApkIdentity> witnesses, long clock) {
            this.signing = signing; this.splits = new ArrayList<>(splits); this.witnesses = witnesses; this.clock = clock;
        }
        boolean matches(InstalledPackage installed, List<SealedApkIdentity> current, long now) {
            if (signing == null || Math.abs(now - clock) >= 100 || !signing.packageName.equals(installed.packageName)
                    || signing.versionCode != installed.versionCode || !signing.currentSigners.equals(installed.certificateSha256)
                    || !splits.equals(installed.splitNames) || current.size() != witnesses.size()) return false;
            for (int i = 0; i < current.size(); i++) if (!witnesses.get(i).cacheable || !current.get(i).cacheable
                    || !witnesses.get(i).stamp.equals(current.get(i).stamp)) return false;
            return true;
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
        PackageInfo info = archiveMetadata(installed, flags | PackageManager.MATCH_DISABLED_COMPONENTS);
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
        Properties states = componentStates(packageName);
        info.activities = applyComponentStates(info.activities, states, flags);
        info.receivers = applyComponentStates(info.receivers, states, flags);
        info.services = applyComponentStates(info.services, states, flags);
        info.providers = applyComponentStates(info.providers, states, flags);
        return info;
    }

    public Map<String, Boolean> sharedLibraries(String packageName) throws IOException {
        InstalledPackage installed = get(packageName);
        if (installed == null) throw new IOException("Shared library owner is not installed");
        Map<String, Boolean> libraries = new LinkedHashMap<>(ApkManifest.read(installed.baseApk).libraries);
        for (File split : installed.splitApks) for (Map.Entry<String, Boolean> entry : ApkManifest.read(split).libraries.entrySet()) {
            libraries.put(entry.getKey(), entry.getValue() || Boolean.TRUE.equals(libraries.get(entry.getKey())));
        }
        if (libraries.size() > 64) throw new IOException("Too many shared library declarations");
        return libraries;
    }

    /** Raw override, not the effective manifest enabled boolean. Only declared components exist. */
    public int componentEnabledSetting(String packageName, String className) throws IOException {
        requireDeclaredComponent(packageName, className);
        return Integer.parseInt(componentStates(packageName).getProperty(className, "0"));
    }

    /** Server-authenticated self updates; all declarations and values are checked before publication. */
    public void setComponentEnabledSettings(String packageName, String expectedGeneration, Map<String, Integer> changes) throws IOException {
        validateName(packageName);
        if (changes == null || changes.isEmpty() || changes.size() > 256) throw new IllegalArgumentException("Invalid component state batch");
        synchronized (PROCESS_INSTALL_LOCK) {
            mkdir(root);
            try (RandomAccessFile lock = new RandomAccessFile(new File(root, "install.lock"), "rw"); FileLock ignored = lock.getChannel().lock()) {
                InstalledPackage installed = get(packageName);
                if (installed == null || !installed.baseApk.getAbsolutePath().equals(expectedGeneration)) throw new SecurityException("Component state package generation changed");
                for (Map.Entry<String, Integer> change : changes.entrySet()) {
                    if (change.getValue() == null || change.getValue() < 0 || change.getValue() > 2) throw new IllegalArgumentException("Unsupported component state");
                    if (change.getKey() == null || change.getKey().isEmpty() || change.getKey().length() > 1024) throw new IllegalArgumentException("Invalid component state key");
                    requireDeclaredComponent(packageName, change.getKey());
                    PackageInfo mutable = getPackageInfo(packageName, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                            | PackageManager.GET_RECEIVERS | PackageManager.MATCH_DISABLED_COMPONENTS);
                    boolean supported = false;
                    for (ComponentInfo[] group : new ComponentInfo[][]{mutable.activities, mutable.services, mutable.receivers}) {
                        if (group != null) for (ComponentInfo component : group) if (change.getKey().equals(component.name)) supported = true;
                    }
                    // shortcut: Provider transitions need published Binder and reference lifecycle validation.
                    if (!supported) throw new UnsupportedOperationException("Mutable Provider state is not implemented");
                }
                Properties values = componentStates(packageName);
                for (Map.Entry<String, Integer> change : changes.entrySet()) {
                    if (change.getValue() == 0) values.remove(change.getKey());
                    else values.setProperty(change.getKey(), change.getValue().toString());
                }
                if (values.size() > 4097) throw new IOException("Too many persisted component states");
                File directory = new File(root, "component-state"); mkdir(directory);
                File temporary = new File(directory, packageName + "." + UUID.randomUUID() + ".tmp");
                try {
                    try (FileOutputStream output = new FileOutputStream(temporary)) {
                        values.store(output, "Mylite user-zero component state"); output.getFD().sync();
                    }
                    if (temporary.length() > 1048576) throw new IOException("Component state record exceeds its size limit");
                    atomicMove(temporary, new File(directory, packageName + ".properties"));
                } catch (IOException failure) {
                    try { Files.deleteIfExists(temporary.toPath()); }
                    catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        }
    }

    private void requireDeclaredComponent(String packageName, String className) throws IOException {
        if (className == null || className.isEmpty()) throw new IllegalArgumentException("Missing component class");
        PackageInfo info = getPackageInfo(packageName, PackageManager.GET_ACTIVITIES | PackageManager.GET_RECEIVERS
                | PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS | PackageManager.MATCH_DISABLED_COMPONENTS);
        if (info != null) for (ComponentInfo[] group : new ComponentInfo[][]{info.activities, info.receivers, info.services, info.providers}) {
            if (group != null) for (ComponentInfo component : group) if (className.equals(component.name)) return;
        }
        throw new IllegalArgumentException("Virtual component is absent");
    }

    private Properties componentStates(String packageName) throws IOException {
        validateName(packageName); Properties states = new Properties(); states.setProperty("format", "1");
        File file = new File(root, "component-state/" + packageName + ".properties");
        if (!file.exists()) return states;
        if (!file.isFile() || file.length() > 1048576) throw new IOException("Invalid component state file");
        try (FileInputStream input = new FileInputStream(file)) { states.load(input); }
        if (!"1".equals(states.getProperty("format")) || states.size() > 4097) throw new IOException("Unsupported component state record");
        for (String key : states.stringPropertyNames()) {
            if (key.equals("format")) continue;
            if (key.isEmpty() || key.length() > 1024 || !(states.getProperty(key).equals("1") || states.getProperty(key).equals("2"))) {
                throw new IOException("Corrupt component state override");
            }
        }
        return states;
    }

    private static <T extends ComponentInfo> T[] applyComponentStates(T[] components, Properties states, int flags) {
        if (components == null) return null;
        int retained = 0;
        for (T component : components) {
            String state = states.getProperty(component.name);
            if (state != null) component.enabled = state.equals("1");
            if (component.enabled || (flags & PackageManager.MATCH_DISABLED_COMPONENTS) != 0) components[retained++] = component;
        }
        return retained == components.length ? components : java.util.Arrays.copyOf(components, retained);
    }

    private PackageInfo archiveMetadata(InstalledPackage installed, int flags) throws IOException {
        String key = installed.baseApk.getPath() + '\0' + flags;
        synchronized (archiveMetadata) {
            Parcel parcel = Parcel.obtain();
            try {
                byte[] template = archiveMetadata.get(key);
                if (template == null) {
                    PackageInfo parsed = context.getPackageManager().getPackageArchiveInfo(installed.baseApk.getPath(), flags);
                    if (parsed == null || parsed.applicationInfo == null
                            || !installed.packageName.equals(parsed.packageName)) {
                        throw new IOException("Cannot parse committed package metadata");
                    }
                    parsed.writeToParcel(parcel, 0);
                    template = parcel.marshall();
                    if (template.length <= archiveMetadata.maxSize()) archiveMetadata.put(key, template);
                } else parcel.unmarshall(template, 0, template.length);
                // Copy constructors retain mutable components, Bundles and signing arrays.
                parcel.setDataPosition(0);
                return PackageInfo.CREATOR.createFromParcel(parcel);
            } finally { parcel.recycle(); }
        }
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
        if (installed.installerPackageName != null) values.setProperty("installer.package", installed.installerPackageName);
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
