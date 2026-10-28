package io.github.xmy953538104.mybox.engine.installer;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import io.github.xmy953538104.mybox.engine.EngineDiagnostics;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exposes revocable bounded I/O and uses the device's FileBridge implementation when required. */
final class InstallerFiles {
    private final StorageManager storage;
    private final Handler handler;
    private final Set<OpenFile> open = new HashSet<>();

    InstallerFiles(Context context) {
        storage = context.getSystemService(StorageManager.class);
        if (storage == null) throw new IllegalStateException("Proxy descriptor storage service is unavailable");
        HandlerThread thread = new HandlerThread("mybox-installer-files"); thread.start(); handler = new Handler(thread.getLooper());
    }

    ParcelFileDescriptor open(int sessionId, SessionRepository.Lease lease, boolean writing, long offset, IBinder lifetime) throws IOException {
        if (lifetime == null) { lease.close(); throw new IllegalArgumentException("Installer descriptor lifetime is required"); }
        OpenFile entry = new OpenFile(sessionId, lease, writing, lifetime);
        synchronized (open) { open.add(entry); }
        String phase = "link-owner";
        try {
            lifetime.linkToDeath(entry.death, 0);
            phase = "open-proxy-descriptor";
            ParcelFileDescriptor proxy = storage.openProxyFileDescriptor(writing ? ParcelFileDescriptor.MODE_WRITE_ONLY : ParcelFileDescriptor.MODE_READ_ONLY,
                    entry.callback, handler);
            synchronized (entry) {
                if (entry.wasReleased.get()) { proxy.close(); throw new IOException("Installer process died while opening a file"); }
                entry.proxy = proxy;
            }
            phase = "seek-proxy-descriptor";
            if (writing && offset != 0) Os.lseek(entry.proxy.getFileDescriptor(), offset, OsConstants.SEEK_SET);
            phase = "select-platform-bridge";
            boolean bridgeRequired = writing && !PackageInstaller.class.getDeclaredField("ENABLE_REVOCABLE_FD").getBoolean(null);
            if (!bridgeRequired) return entry.proxy;
            phase = "create-platform-bridge";
            Class<?> bridgeClass = Class.forName("android.os.FileBridge");
            synchronized (entry) {
                if (entry.wasReleased.get()) throw new IOException("Installer process died while opening a file");
                entry.bridge = bridgeClass.getDeclaredConstructor().newInstance();
                phase = "set-bridge-target";
                try { bridgeClass.getMethod("setTargetFile", ParcelFileDescriptor.class).invoke(entry.bridge, entry.proxy); }
                catch (NoSuchMethodException legacy) { bridgeClass.getMethod("setTargetFile", FileDescriptor.class).invoke(entry.bridge, entry.proxy.getFileDescriptor()); }
                phase = "get-bridge-socket";
                Object socket = bridgeClass.getMethod("getClientSocket").invoke(entry.bridge);
                ParcelFileDescriptor client = socket instanceof ParcelFileDescriptor ? (ParcelFileDescriptor) socket : ParcelFileDescriptor.dup((FileDescriptor) socket);
                ((Thread) entry.bridge).start(); return client;
            }
        } catch (ReflectiveOperationException | RemoteException | ErrnoException | RuntimeException failure) {
            EngineDiagnostics.failure("installer_fd", phase, sessionId, failure);
            Log.w("MyboxInstaller", "Descriptor setup failed at " + phase + " for session " + sessionId, failure);
            entry.revoke(); throw new IOException("Platform installation descriptor setup failed", failure);
        } catch (IOException failure) {
            EngineDiagnostics.failure("installer_fd", phase, sessionId, failure);
            Log.w("MyboxInstaller", "Descriptor setup failed at " + phase + " for session " + sessionId, failure);
            entry.revoke(); throw failure;
        }
    }

    /** FileBridge acknowledges close before a queued FUSE release callback has necessarily run. */
    void finishClosedWriters(int sessionId) throws IOException {
        ArrayList<OpenFile> entries; synchronized (open) { entries = new ArrayList<>(open); }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (OpenFile entry : entries) if (entry.sessionId == sessionId && entry.writing) {
            boolean closed;
            try { closed = entry.bridge == null || (Boolean) entry.bridge.getClass().getMethod("isClosed").invoke(entry.bridge); }
            catch (ReflectiveOperationException failure) { throw new IOException("Platform bridge state is unavailable", failure); }
            if (!closed) continue; // A genuinely open stream is rejected by the repository's seal check.
            try { entry.released.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while closing staged APK", interrupted); }
        }
    }

    void revoke(int sessionId) {
        ArrayList<OpenFile> entries; synchronized (open) { entries = new ArrayList<>(open); }
        for (OpenFile entry : entries) if (entry.sessionId == sessionId) entry.revoke();
    }

    private final class OpenFile {
        final int sessionId;
        final SessionRepository.Lease lease;
        final boolean writing;
        final IBinder lifetime;
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicBoolean wasReleased = new AtomicBoolean();
        final IBinder.DeathRecipient death = this::revoke;
        volatile ParcelFileDescriptor proxy;
        volatile Object bridge;
        final ProxyFileDescriptorCallback callback = new ProxyFileDescriptorCallback() {
            @Override public long onGetSize() throws ErrnoException { try { return lease.size(); } catch (IOException | RuntimeException failure) { throw io("size", failure); } }
            @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                try { return lease.read(offset, data, size); } catch (IOException | RuntimeException failure) { throw io("read", failure); }
            }
            @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
                try { return lease.write(offset, data, size); } catch (IOException | RuntimeException failure) { throw io("write", failure); }
            }
            @Override public void onFsync() throws ErrnoException { try { lease.sync(); } catch (IOException | RuntimeException failure) { throw io("fsync", failure); } }
            @Override public void onRelease() { release(); }
        };
        OpenFile(int sessionId, SessionRepository.Lease lease, boolean writing, IBinder lifetime) {
            this.sessionId = sessionId; this.lease = lease; this.writing = writing; this.lifetime = lifetime;
        }
        synchronized void revoke() {
            Object current = bridge;
            if (current != null) try { current.getClass().getMethod("forceClose").invoke(current); }
            catch (ReflectiveOperationException failure) { Log.w("MyboxInstaller", "Platform file bridge close failed", failure); }
            ParcelFileDescriptor descriptor = proxy;
            if (descriptor != null) try { descriptor.close(); } catch (IOException failure) { Log.w("MyboxInstaller", "Proxy descriptor close failed", failure); }
            release();
        }
        void release() {
            if (!wasReleased.compareAndSet(false, true)) return;
            try { lease.close(); EngineDiagnostics.event("installer_fd", "released", sessionId, writing ? 1 : 0); }
            catch (IOException failure) { EngineDiagnostics.failure("installer_fd", "close_sync", sessionId, failure); Log.w("MyboxInstaller", "Staged APK synchronization failed", failure); }
            synchronized (open) { open.remove(this); }
            try { lifetime.unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException notLinked) { /* Setup failed or the owner already died. */ }
            released.countDown();
        }
    }
    private static ErrnoException io(String operation, Throwable failure) {
        EngineDiagnostics.failure("installer_fd", operation, 0, failure);
        Log.w("MyboxInstaller", "Staged descriptor " + operation + " failed", failure);
        return new ErrnoException(operation, OsConstants.EIO, failure);
    }
}
