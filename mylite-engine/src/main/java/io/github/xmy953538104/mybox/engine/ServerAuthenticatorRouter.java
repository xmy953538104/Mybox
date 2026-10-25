package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.xmy953538104.mybox.engine.accounts.AuthenticatorRouter;

/** Account sessions bind through the same guest Service lifecycle as ordinary applications. */
final class ServerAuthenticatorRouter implements AuthenticatorRouter {
    private final ComponentRouter components;

    ServerAuthenticatorRouter(ComponentRouter components) { this.components = components; }

    @Override public Binding bind(ComponentName component, int userId, Connection connection) throws Exception {
        if (userId != 0) throw new SecurityException("Only virtual user 0 is supported");
        Session session = new Session(connection);
        try {
            ServerBoundServices.Binding actual = components.bindInternal(component,
                    new Intent("android.accounts.AccountAuthenticator").setComponent(component),
                    Context.BIND_AUTO_CREATE, session);
            session.attach(actual);
            if (!actual.admitted()) throw new EngineException("Android rejected the authenticator binding");
            return session;
        } catch (Exception failure) {
            try { session.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static final class Session implements Binding, ServerBoundServices.Connection {
        final Connection connection;
        final AtomicBoolean closed = new AtomicBoolean();
        volatile ServerBoundServices.Binding binding;
        Session(Connection connection) { this.connection = connection; }

        void attach(ServerBoundServices.Binding actual) throws Exception {
            binding = actual;
            // A real callback or account timeout can close before bindInternal returns its handle.
            if (closed.get()) actual.close();
        }

        @Override public void onConnected(IBinder authenticator, ProcessRegistry.Identity owner) {
            if (closed.get()) return;
            if (authenticator == null || !authenticator.isBinderAlive()) { disconnected(); return; }
            try { connection.onConnected(authenticator); }
            catch (RuntimeException failure) {
                try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        @Override public void onNullBinding(ProcessRegistry.Identity owner) { disconnected(); }
        @Override public void onDisconnected(ProcessRegistry.Identity owner) { disconnected(); }
        @Override public void onBindingDied(ProcessRegistry.Identity owner) { disconnected(); }
        @Override public void onFailure(Exception failure) { disconnected(); }

        private void disconnected() {
            if (!closed.compareAndSet(false, true)) return;
            try { unbind(); } finally { connection.onDisconnected(); }
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) unbind();
        }
        private void unbind() {
            ServerBoundServices.Binding current = binding;
            if (current == null) return;
            try { current.close(); }
            catch (Exception failure) { throw new EngineException("Authenticator unbind failed", failure); }
        }
    }
}
