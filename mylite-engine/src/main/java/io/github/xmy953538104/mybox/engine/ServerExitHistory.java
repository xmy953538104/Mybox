package io.github.xmy953538104.mybox.engine;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads actual OS history under the real host identity, then restricts it to authenticated guest ownership. */
@android.annotation.TargetApi(30)
final class ServerExitHistory extends Binder {
    static final String DESCRIPTOR = "io.github.xmy953538104.mybox.engine.ExitHistory.v1";
    private final Engine engine;
    private final ActivityManager manager;
    private final ExitHistoryJournal journal;
    private final String boot;
    private volatile IOException storageFailure;

    ServerExitHistory(Engine engine) throws IOException {
        this.engine = engine;
        manager = engine.hostContext().getSystemService(ActivityManager.class);
        int bootCount = android.provider.Settings.Global.getInt(engine.hostContext().getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        if (bootCount < 0) throw new IOException("Boot identity is unavailable for process exit history");
        boot = java.util.UUID.nameUUIDFromBytes(("boot-count:" + bootCount).getBytes(StandardCharsets.US_ASCII)).toString();
        AtomicFile file = new AtomicFile(new File(engine.hostContext().getNoBackupFilesDir(), "mylite-exit-history.bin"));
        File backup = new File(file.getBaseFile().getPath() + ".bak");
        if (file.getBaseFile().length() > 1024 * 1024 || backup.length() > 1024 * 1024) throw new IOException("Oversized process exit history journal");
        byte[] existing;
        try { existing = file.readFully(); }
        catch (java.io.FileNotFoundException missing) {
            if (file.getBaseFile().exists() || backup.exists()) throw missing;
            existing = null;
        }
        journal = new ExitHistoryJournal(existing, bytes -> {
            FileOutputStream output = null;
            try {
                output = file.startWrite(); output.write(bytes); output.getFD().sync(); file.finishWrite(output); output = null;
                if (!java.util.Arrays.equals(bytes, file.readFully())) throw new IOException("Exit history atomic commit did not persist");
            }
            catch (IOException failure) { if (output != null) file.failWrite(output); throw failure; }
        });
        attachInterface(null, DESCRIPTOR);
    }

    void seal(ProcessRegistry.Identity identity) throws Exception {
        requireStorage(); InstalledPackage installed = installed(identity);
        List<String> baseline = new ArrayList<>();
        for (ApplicationExitInfo value : physical(identity.pid)) baseline.add(key(value));
        try {
            boolean eligible = journal.seal(identity.packageName, installed.appId, installed.firstInstallTime, identity.processName,
                    physicalName(identity.slot), identity.pid, identity.generation, clock(), baseline);
            if (!eligible) android.util.Log.w("MyliteExitHistory", "PID collision has no stable OS identity; new exit ownership will not be inferred");
        } catch (IOException failure) { storageFailure = failure; throw failure; }
    }

    void retired(ProcessRegistry.Identity identity) {
        try { requireStorage(); journal.retired(identity.pid, identity.generation, clock()); }
        catch (IOException failure) {
            // A Binder-death callback cannot return an error; every subsequent query/seal fails visibly.
            storageFailure = failure;
            android.util.Log.e("MyliteExitHistory", "Exit history retirement persistence failed", failure);
        }
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        if (code != FIRST_CALL_TRANSACTION) return false;
        EngineProtocol.requireCaller(); data.enforceInterface(DESCRIPTOR);
        try {
            Bundle request = data.readBundle(getClass().getClassLoader());
            if (request == null || request.getInt("protocol") != EngineProtocol.VERSION) throw new IllegalArgumentException("Invalid exit history protocol");
            ProcessRegistry.Identity caller = EngineServerProvider.registeredProcess(Binder.getCallingUid(), Binder.getCallingPid());
            String claimed = request.getString("package");
            if (claimed != null && !claimed.isEmpty() && !caller.packageName.equals(claimed)) throw new SecurityException("Exit history belongs to another guest");
            if (request.getInt("user", -1) != 0) throw new SecurityException("Only virtual user 0 exists");
            int pid = request.getInt("pid"), maximum = request.getInt("max");
            ArrayList<ApplicationExitInfo> values = query(caller, pid, maximum);
            Bundle result = EngineProtocol.response(); result.putParcelableArrayList("history", values);
            reply.writeNoException(); reply.writeBundle(result);
        } catch (RuntimeException failure) { reply.writeException(failure); }
        catch (Exception failure) { reply.writeException(new IllegalStateException("Actual exit history is unavailable", failure)); }
        return true;
    }

    private ArrayList<ApplicationExitInfo> query(ProcessRegistry.Identity caller, int pid, int maximum) throws Exception {
        requireStorage(); InstalledPackage installed = installed(caller);
        List<ApplicationExitInfo> actual = physical(pid, maximum < 0 ? maximum : 0);
        List<ExitHistoryJournal.Row> rows = new ArrayList<>();
        Map<String, ApplicationExitInfo> byKey = new HashMap<>();
        for (ApplicationExitInfo value : actual) {
            if (value.getRealUid() != Process.myUid() || value.getPackageUid() != Process.myUid()) continue;
            String key = key(value); rows.add(new ExitHistoryJournal.Row(key, value.getProcessName(), value.getPid(), value.getTimestamp()));
            byKey.put(key, value);
        }
        long previousDiscontinuities = journal.discontinuities();
        Map<String, ExitHistoryJournal.Owner> owned;
        try { owned = journal.resolve(caller.packageName, installed.appId, installed.firstInstallTime, rows, clock()); }
        catch (IOException failure) { storageFailure = failure; throw failure; }
        if (journal.discontinuities() != previousDiscontinuities) {
            android.util.Log.w("MyliteExitHistory", "Clock or boot changed; unresolved process ownership was retired");
        }
        ArrayList<ApplicationExitInfo> result = new ArrayList<>();
        for (Map.Entry<String, ExitHistoryJournal.Owner> entry : owned.entrySet()) {
            ApplicationExitInfo raw = byKey.get(entry.getKey());
            Parcel copy = Parcel.obtain(); ApplicationExitInfo value;
            try { raw.writeToParcel(copy, 0); copy.setDataPosition(0); value = ApplicationExitInfo.CREATOR.createFromParcel(copy); }
            finally { copy.recycle(); }
            RuntimeReflection.set(value, "mProcessName", entry.getValue().process);
            RuntimeReflection.set(value, "mPackageName", caller.packageName);
            ExitTraceCapability.install(this, raw, value, caller, entry.getValue().epoch, entry.getKey());
            result.add(value);
        }
        Collections.sort(result, Comparator.comparingLong(ApplicationExitInfo::getTimestamp).reversed());
        if (maximum > 0 && result.size() > maximum) result.subList(maximum, result.size()).clear();
        return result;
    }

    void authorizeTrace(ProcessRegistry.Identity issuer, String epoch, String expected, int pid) throws Exception {
        requireStorage();
        ProcessRegistry.Identity actual = EngineServerProvider.registeredProcess(Binder.getCallingUid(), Binder.getCallingPid());
        if (!issuer.same(actual)) throw new SecurityException("Exit trace capability has another caller");
        InstalledPackage installed = installed(actual);
        if (!epoch.equals(journal.epoch(actual.packageName, installed.appId, installed.firstInstallTime))) throw new SecurityException("Exit trace installation expired");
        for (ApplicationExitInfo value : physical(pid)) if (key(value).equals(expected)) return;
        throw new IllegalStateException("Actual OS exit record was replaced or expired");
    }

    private InstalledPackage installed(ProcessRegistry.Identity identity) throws IOException {
        InstalledPackage installed = engine.packages().get(identity.packageName);
        if (installed == null || !installed.baseApk.getAbsolutePath().equals(identity.packageGeneration)) throw new SecurityException("Exit history caller installation changed");
        return installed;
    }
    private String physicalName(int slot) { return engine.hostContext().getPackageName() + ":engine_p" + slot; }
    private ExitHistoryJournal.Clock clock() { return new ExitHistoryJournal.Clock(boot, System.currentTimeMillis(), SystemClock.elapsedRealtime()); }
    private void requireStorage() throws IOException { if (storageFailure != null) throw new IOException("Exit history persistence previously failed", storageFailure); }

    private List<ApplicationExitInfo> physical(int pid) {
        return physical(pid, 0);
    }
    private List<ApplicationExitInfo> physical(int pid, int maximum) {
        long token = Binder.clearCallingIdentity();
        try { return manager.getHistoricalProcessExitReasons(engine.hostContext().getPackageName(), pid, maximum); }
        finally { Binder.restoreCallingIdentity(token); }
    }

    static String key(ApplicationExitInfo value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(value.getPid()); output.writeLong(value.getTimestamp()); output.writeInt(value.getRealUid());
            output.writeInt(value.getPackageUid()); output.writeInt(value.getDefiningUid());
            output.writeUTF(value.getProcessName() == null ? "" : value.getProcessName());
            byte[] summary = value.getProcessStateSummary(); output.writeInt(summary == null ? -1 : summary.length);
            if (summary != null) output.write(summary);
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        StringBuilder key = new StringBuilder(64); for (byte part : hash) key.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
        return key.toString();
    }

    static Object request(Engine engine, Object[] args) throws Exception {
        if (args == null || args.length != 4) throw new UnsupportedOperationException("Unknown historical exit query signature");
        Bundle service = EngineProtocol.response(); service.putString("service", "exit_history");
        IBinder target = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.GET_SERVICE, service).getBinder("service");
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            Bundle request = EngineProtocol.response(); request.putString("package", (String) args[0]);
            request.putInt("pid", (Integer) args[1]); request.putInt("max", (Integer) args[2]); request.putInt("user", (Integer) args[3]);
            input.writeInterfaceToken(DESCRIPTOR); input.writeBundle(request);
            if (!target.transact(FIRST_CALL_TRANSACTION, input, output, 0)) throw new UnsupportedOperationException("Exit history service is unavailable");
            output.readException(); Bundle result = output.readBundle(ServerExitHistory.class.getClassLoader());
            if (result == null || result.getInt("protocol") != EngineProtocol.VERSION) throw new IllegalStateException("Invalid exit history response");
            List<ApplicationExitInfo> values = result.getParcelableArrayList("history");
            if (values == null) throw new IllegalStateException("Missing actual exit history");
            return Class.forName("android.content.pm.ParceledListSlice").getConstructor(List.class).newInstance(values);
        } finally { input.recycle(); output.recycle(); }
    }
}
