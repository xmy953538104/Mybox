package io.github.xmy953538104.mybox.engine;

import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Keeps the Android provider dependency alive for the lifetime of its custom Binder service. */
final class ProviderConnection implements AutoCloseable, IBinder.DeathRecipient {
    final IBinder binder;
    private final AutoCloseable provider;
    private final Consumer<ProviderConnection> onDeath;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ProviderConnection(IBinder binder, AutoCloseable provider,
            Consumer<ProviderConnection> onDeath) {
        this.binder = binder;
        this.provider = provider;
        this.onDeath = onDeath;
    }

    /** Takes ownership of provider, including when linkToDeath fails. Call outside owner locks. */
    static ProviderConnection connect(IBinder binder, AutoCloseable provider,
            Consumer<ProviderConnection> onDeath) throws RemoteException {
        ProviderConnection connection = new ProviderConnection(binder, provider, onDeath);
        try {
            binder.linkToDeath(connection, 0);
            if (!connection.isAlive()) throw new DeadObjectException();
            return connection;
        } catch (RemoteException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    boolean isAlive() { return !closed.get() && binder.isBinderAlive(); }

    @Override public void binderDied() {
        if (!closed.compareAndSet(false, true)) return;
        // The owner only removes this exact connection. A late death cannot evict its replacement.
        try { onDeath.accept(this); }
        finally { release(); }
    }

    /** ContentProviderClient.close makes framework IPC; callers must not hold registry locks. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        release();
    }

    private void release() {
        try { binder.unlinkToDeath(this, 0); }
        catch (java.util.NoSuchElementException alreadyDead) { }
        finally {
            try { provider.close(); }
            catch (Exception failure) {
                android.util.Log.w("MyboxProvider", "Cannot release provider connection", failure);
            }
        }
    }
}
