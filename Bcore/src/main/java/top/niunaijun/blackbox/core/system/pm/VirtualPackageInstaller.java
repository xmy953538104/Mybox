package top.niunaijun.blackbox.core.system.pm;

import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.IPackageInstallerCallback;
import android.content.pm.PackageInstaller;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import android.util.SparseArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.installer.AtomicPackageDirectory;
import top.niunaijun.blackbox.core.system.pm.installer.InstallSessionState;
import top.niunaijun.blackbox.core.system.pm.installer.SessionFileBridge;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;

/** Server-side virtual sessions. No package installation is delegated to the physical device. */
final class VirtualPackageInstaller {
    private static final int MAX_SESSIONS_PER_PACKAGE = 64;
    private static final class Holder { static final VirtualPackageInstaller INSTANCE = new VirtualPackageInstaller(); }
    static VirtualPackageInstaller get() { return Holder.INSTANCE; }

    private final SparseArray<Session> sessions = new SparseArray<>();
    private final AtomicInteger nextId = new AtomicInteger((int) (System.currentTimeMillis() & 0x3fffffff));
    private final RemoteCallbackList<IPackageInstallerCallback> callbacks = new RemoteCallbackList<>();
    private final ExecutorService commits = Executors.newSingleThreadExecutor();
    private final Handler ioHandler;
    private final File root = new File(BEnvironment.getSystemDir(), "install-sessions");

    private VirtualPackageInstaller() {
        HandlerThread io = new HandlerThread("Mybox-installer-fd");
        io.start();
        ioHandler = new Handler(io.getLooper());
        FileUtils.mkdirs(root);
        restoreSessions();
    }

    Bundle call(String operation, Bundle args) {
        ProcessRecord caller = caller();
        if (args == null) args = new Bundle();
        args.setClassLoader(PackageInstaller.SessionParams.class.getClassLoader());
        Bundle result = new Bundle();
        if ("commit".equals(operation)) {
            Session session;
            synchronized (sessions) { session = owned(args.getInt("id"), caller); }
            // Raw descriptors report close asynchronously; do not hold the monitor needed by that callback.
            session.state.awaitClosed(5000);
        }
        synchronized (sessions) {
            switch (operation) {
                case "createSession": {
                    PackageInstaller.SessionParams params = args.getParcelable("params");
                    validateParams(params);
                    int count = 0;
                    for (int i = 0; i < sessions.size(); i++) if (owns(sessions.valueAt(i), caller)) count++;
                    if (count >= MAX_SESSIONS_PER_PACKAGE) throw new IllegalStateException("Too many active sessions");
                    int id;
                    do { id = nextId.incrementAndGet() & 0x7fffffff; } while (sessions.get(id) != null);
                    Session session = new Session(id, caller.getPackageName(), caller.userId, params);
                    try {
                        if (!session.directory.mkdir()) throw new IOException("Cannot create session directory");
                        save(session);
                    } catch (IOException e) { throw new IllegalStateException("Cannot persist session", e); }
                    sessions.put(id, session);
                    result.putInt("id", id);
                    notifyCallback(session, "created", 0);
                    return result;
                }
                case "registerCallback": {
                    IBinder binder = args.getBinder("callback");
                    if (binder == null) throw new IllegalArgumentException("Missing callback");
                    callbacks.register(IPackageInstallerCallback.Stub.asInterface(binder), new Owner(caller));
                    return result;
                }
                case "unregisterCallback":
                    callbacks.unregister(IPackageInstallerCallback.Stub.asInterface(args.getBinder("callback")));
                    return result;
                case "getAllSessions":
                case "getMySessions":
                case "getStagedSessions": {
                    ArrayList<PackageInstaller.SessionInfo> infos = new ArrayList<>();
                    if (!"getStagedSessions".equals(operation)) {
                        for (int i = 0; i < sessions.size(); i++) {
                            Session session = sessions.valueAt(i);
                            if (session.userId == caller.userId && ("getAllSessions".equals(operation) || owns(session, caller))) {
                                infos.add(info(session));
                            }
                        }
                    }
                    result.putParcelableArrayList("sessions", infos);
                    return result;
                }
                case "getSessionInfo": {
                    Session session = sessions.get(args.getInt("id"));
                    if (session != null && session.userId == caller.userId) result.putParcelable("info", info(session));
                    return result;
                }
                default:
                    break;
            }
            Session session = owned(args.getInt("id"), caller);
            switch (operation) {
                case "openSession":
                    if (session.state.isFinished()) throw new IllegalStateException("Session has finished");
                    session.active = true;
                    notifyCallback(session, "active", 1);
                    break;
                case "close":
                    session.active = false;
                    notifyCallback(session, "active", 0);
                    break;
                case "getNames":
                    result.putStringArray("names", apkNames(session));
                    break;
                case "setClientProgress":
                case "addClientProgress": {
                    session.state.requireMutable();
                    float progress = args.getFloat("progress");
                    if (Float.isNaN(progress) || Float.isInfinite(progress)) throw new IllegalArgumentException("Invalid progress");
                    session.progress = Math.max(0, Math.min(1, "addClientProgress".equals(operation) ? session.progress + progress : progress));
                    notifyCallback(session, "progress", session.progress);
                    break;
                }
                case "updateSessionAppIcon":
                    session.state.requireMutable();
                    set(session.params, "appIcon", args.getParcelable("icon"));
                    persist(session);
                    notifyCallback(session, "badging", 0);
                    break;
                case "updateSessionAppLabel":
                    session.state.requireMutable();
                    set(session.params, "appLabel", args.getString("label"));
                    persist(session);
                    notifyCallback(session, "badging", 0);
                    break;
                case "removeSplit": {
                    session.state.requireMutable();
                    String split = args.getString("name");
                    if (split == null || !split.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid split name");
                    session.removedSplits.add(split);
                    persist(session);
                    break;
                }
                case "commit": {
                    for (SessionFileBridge bridge : session.bridges) if (bridge.failed()) {
                        throw new IllegalStateException("APK write did not finish successfully");
                    }
                    if (session.writeFailed) throw new IllegalStateException("APK write failed");
                    session.state.seal();
                    persist(session);
                    IntentSender receiver = args.getParcelable("receiver");
                    commits.execute(() -> commit(session, receiver));
                    break;
                }
                case "abandonSession":
                case "abandon":
                    session.state.requireMutable();
                    for (SessionFileBridge bridge : session.bridges) bridge.cancel();
                    finish(session, false);
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported virtual installer operation: " + operation);
            }
        }
        return result;
    }

    ParcelFileDescriptor openWrite(int id, String name, long offset, long length, boolean bridgeProtocol) {
        ProcessRecord caller = caller();
        synchronized (sessions) {
            Session session = owned(id, caller);
            session.state.requireMutable();
            if (offset < 0 || length < -1) throw new IllegalArgumentException("Invalid write range");
            File target = sessionFile(session, name);
            if (!session.writingNames.add(name)) throw new IllegalStateException("File already has an open writer");
            session.state.openWriter();
            try {
                if (bridgeProtocol) {
                    SessionFileBridge bridge = new SessionFileBridge(target, offset, () -> writerClosed(session, name));
                    session.bridges.add(bridge);
                    bridge.start();
                    return bridge.client();
                }
                ParcelFileDescriptor fd = ParcelFileDescriptor.open(target,
                        ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE,
                        ioHandler, error -> {
                            if (error != null) session.writeFailed = true;
                            writerClosed(session, name);
                        });
                try {
                    if (offset == 0) Os.ftruncate(fd.getFileDescriptor(), 0);
                    if (offset > Os.fstat(fd.getFileDescriptor()).st_size) throw new IOException("Offset beyond EOF");
                    Os.lseek(fd.getFileDescriptor(), offset, OsConstants.SEEK_SET);
                } catch (Exception e) { fd.close(); throw e; }
                return fd;
            } catch (Exception e) {
                if (session.writingNames.remove(name)) session.state.closeWriter();
                throw new IllegalStateException("Cannot open session write", e);
            }
        }
    }

    ParcelFileDescriptor openRead(int id, String name) {
        ProcessRecord caller = caller();
        synchronized (sessions) {
            Session session = owned(id, caller);
            try { return ParcelFileDescriptor.open(sessionFile(session, name), ParcelFileDescriptor.MODE_READ_ONLY); }
            catch (IOException e) { throw new IllegalStateException("Cannot open session read", e); }
        }
    }

    private void writerClosed(Session session, String name) {
        synchronized (sessions) {
            if (session.writingNames.remove(name)) session.state.closeWriter();
        }
    }

    private void commit(Session session, IntentSender receiver) {
        InstallResult result;
        try {
            String[] names = apkNames(session);
            String[] files = new String[names.length];
            for (int i = 0; i < files.length; i++) files[i] = new File(session.directory, names[i]).getAbsolutePath();
            result = BPackageManagerService.get().installFromSession(files,
                    session.removedSplits.toArray(new String[0]), (Integer) get(session.params, "mode"),
                    (String) get(session.params, "appPackageName"), session.userId, session.installer);
        } catch (Exception e) {
            result = new InstallResult().installError("Install session could not complete: " + e.getClass().getSimpleName());
            result.status = PackageInstaller.STATUS_FAILURE;
        }
        if (receiver != null) {
            Intent intent = new Intent();
            intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, session.id);
            intent.putExtra(PackageInstaller.EXTRA_STATUS, result.success ? PackageInstaller.STATUS_SUCCESS : result.status);
            intent.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, result.success ? "Installed" : result.msg);
            intent.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, result.packageName);
            try { receiver.sendIntent(BlackBoxCore.getContext(), 0, intent, null, null); }
            catch (IntentSender.SendIntentException e) { Slog.w("MyboxInstaller", "Install status receiver is no longer available"); }
        }
        synchronized (sessions) { finish(session, result.success); }
    }

    private void finish(Session session, boolean success) {
        session.state.finish();
        notifyCallback(session, "finished", success ? 1 : 0);
        sessions.remove(session.id);
        try { AtomicPackageDirectory.delete(session.directory); }
        catch (IOException e) { Slog.w("MyboxInstaller", "Session cleanup deferred"); }
    }

    private static void validateParams(PackageInstaller.SessionParams params) {
        if (params == null) throw new IllegalArgumentException("Missing SessionParams");
        int mode = (Integer) get(params, "mode");
        if (mode != PackageInstaller.SessionParams.MODE_FULL_INSTALL && mode != PackageInstaller.SessionParams.MODE_INHERIT_EXISTING) {
            throw new IllegalArgumentException("Unsupported install mode");
        }
        if (optionalBoolean(params, "isMultiPackage") || optionalBoolean(params, "isStaged")) {
            throw new UnsupportedOperationException("Multi-package and staged system installs are not supported");
        }
        if (mode == PackageInstaller.SessionParams.MODE_INHERIT_EXISTING && get(params, "appPackageName") == null) {
            throw new IllegalArgumentException("Inherit mode requires a package name");
        }
    }

    private static ProcessRecord caller() {
        if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Foreign installer UID");
        ProcessRecord record = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (record == null) throw new SecurityException("Installer caller is not a registered guest process");
        return record;
    }

    private Session owned(int id, ProcessRecord caller) {
        Session session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException("Unknown install session");
        if (!owns(session, caller)) throw new SecurityException("Install session belongs to another package");
        return session;
    }

    private static boolean owns(Session session, ProcessRecord caller) {
        return session.userId == caller.userId && session.installer.equals(caller.getPackageName());
    }

    private static File sessionFile(Session session, String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,195}\\.apk")) {
            throw new IllegalArgumentException("Only flat APK filenames are supported");
        }
        return new File(session.directory, name);
    }

    private static String[] apkNames(Session session) {
        String[] names = session.directory.list((directory, name) -> name.endsWith(".apk"));
        if (names == null) throw new IllegalStateException("Session directory is missing");
        java.util.Arrays.sort(names);
        return names;
    }

    private static PackageInstaller.SessionInfo info(Session session) {
        PackageInstaller.SessionInfo info;
        try {
            java.lang.reflect.Constructor<PackageInstaller.SessionInfo> constructor = PackageInstaller.SessionInfo.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            info = constructor.newInstance();
        } catch (Exception e) { throw new IllegalStateException("Cannot create session info", e); }
        set(info, "sessionId", session.id);
        set(info, "installerPackageName", session.installer);
        set(info, "progress", session.progress);
        set(info, "sealed", session.state.isSealed());
        set(info, "active", session.active);
        set(info, "mode", get(session.params, "mode"));
        set(info, "sizeBytes", get(session.params, "sizeBytes"));
        set(info, "appPackageName", get(session.params, "appPackageName"));
        set(info, "appLabel", get(session.params, "appLabel"));
        set(info, "appIcon", get(session.params, "appIcon"));
        return info;
    }

    private void notifyCallback(Session session, String event, float value) {
        int count = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                Owner owner = (Owner) callbacks.getBroadcastCookie(i);
                if (owner.userId != session.userId || !owner.packageName.equals(session.installer)) continue;
                IPackageInstallerCallback callback = callbacks.getBroadcastItem(i);
                try {
                    switch (event) {
                        case "created": callback.onSessionCreated(session.id); break;
                        case "active": callback.onSessionActiveChanged(session.id, value != 0); break;
                        case "progress": callback.onSessionProgressChanged(session.id, value); break;
                        case "badging": callback.onSessionBadgingChanged(session.id); break;
                        case "finished": callback.onSessionFinished(session.id, value != 0); break;
                    }
                } catch (android.os.RemoteException ignored) { }
            }
        } finally { callbacks.finishBroadcast(); }
    }

    private static Object get(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (Exception e) { throw new IllegalStateException("Unsupported PackageInstaller field: " + name, e); }
    }

    private static void set(Object owner, String name, Object value) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner, value);
        } catch (Exception e) { throw new IllegalStateException("Unsupported PackageInstaller field: " + name, e); }
    }

    private static boolean optionalBoolean(Object owner, String name) {
        try { return Boolean.TRUE.equals(get(owner, name)); }
        catch (IllegalStateException missingOnOldAndroid) { return false; }
    }

    private void persist(Session session) {
        try { save(session); }
        catch (IOException e) { throw new IllegalStateException("Cannot save session", e); }
    }

    private void save(Session session) throws IOException {
        AtomicFile file = new AtomicFile(new File(session.directory, "session.conf"));
        Parcel parcel = Parcel.obtain();
        FileOutputStream output = null;
        try {
            parcel.writeInt(1);
            parcel.writeInt(session.id);
            parcel.writeString(session.installer);
            parcel.writeInt(session.userId);
            parcel.writeParcelable(session.params, 0);
            parcel.writeStringList(new ArrayList<>(session.removedSplits));
            output = file.startWrite();
            output.write(parcel.marshall());
            file.finishWrite(output);
        } catch (Exception e) {
            file.failWrite(output);
            throw new IOException("Cannot persist install session", e);
        } finally { parcel.recycle(); }
    }

    private void restoreSessions() {
        File[] directories = root.listFiles();
        if (directories == null) return;
        for (File directory : directories) {
            Parcel parcel = Parcel.obtain();
            try {
                byte[] bytes = FileUtils.toByteArray(new File(directory, "session.conf"));
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);
                if (parcel.readInt() != 1) throw new IOException("Unsupported session format");
                int id = parcel.readInt();
                String installer = parcel.readString();
                int userId = parcel.readInt();
                PackageInstaller.SessionParams params = parcel.readParcelable(PackageInstaller.SessionParams.class.getClassLoader());
                validateParams(params);
                if (!String.valueOf(id).equals(directory.getName())) throw new IOException("Session ID mismatch");
                Session session = new Session(id, installer, userId, params);
                ArrayList<String> removed = parcel.createStringArrayList();
                if (removed != null) session.removedSplits.addAll(removed);
                // No result is fabricated after a process death; the owner can reopen and recommit verified files.
                sessions.put(id, session);
            } catch (Exception e) {
                Slog.w("MyboxInstaller", "Unreadable install session retained for diagnosis");
            } finally { parcel.recycle(); }
        }
    }

    private final class Session {
        final int id;
        final String installer;
        final int userId;
        final PackageInstaller.SessionParams params;
        final File directory;
        final InstallSessionState state = new InstallSessionState();
        final Set<String> removedSplits = new LinkedHashSet<>();
        final Set<String> writingNames = new HashSet<>();
        final List<SessionFileBridge> bridges = new ArrayList<>();
        float progress;
        boolean active;
        volatile boolean writeFailed;
        Session(int id, String installer, int userId, PackageInstaller.SessionParams params) {
            this.id = id; this.installer = installer; this.userId = userId; this.params = params;
            directory = new File(root, String.valueOf(id));
        }
    }

    private static final class Owner {
        final String packageName;
        final int userId;
        Owner(ProcessRecord caller) { packageName = caller.getPackageName(); userId = caller.userId; }
    }
}
