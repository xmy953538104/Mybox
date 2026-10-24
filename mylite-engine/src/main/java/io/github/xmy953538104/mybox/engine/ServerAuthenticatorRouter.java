package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.xmy953538104.mybox.engine.accounts.AuthenticatorRouter;

/** Account sessions bind through the same guest Service lifecycle as ordinary applications. */
final class ServerAuthenticatorRouter implements AuthenticatorRouter {
    private final ComponentRouter components;

    ServerAuthenticatorRouter(ComponentRouter components) { this.components = components; }

    @Override public Binding bind(ComponentName component, int userId, Connection connection) throws Exception {
        if (userId != 0) throw new SecurityException("Only virtual user 0 is supported");
        Bundle request = EngineProtocol.response();
        request.putParcelable("component", component);
        request.putParcelable("intent", new Intent("android.accounts.AccountAuthenticator").setComponent(component));
        request.putBinder("connection", new Binder());
        request.putLong("flags", Context.BIND_AUTO_CREATE);
        Bundle result = components.routeInternal(EngineProtocol.BIND_SERVICE, request);
        IBinder authenticator = result.getBinder("service");
        IBinder owner = result.getBinder("owner");
        if (!result.getBoolean("bound") || authenticator == null || !authenticator.isBinderAlive()) {
            components.routeInternal(EngineProtocol.UNBIND_SERVICE, request);
            throw new EngineException("Virtual authenticator returned no live Service Binder");
        }
        AtomicBoolean closed = new AtomicBoolean();
        IBinder.DeathRecipient death = () -> {
            if (closed.compareAndSet(false, true)) connection.onDisconnected();
        };
        owner.linkToDeath(death, 0);
        Binding binding = () -> {
            if (!closed.compareAndSet(false, true)) return;
            owner.unlinkToDeath(death, 0);
            if (owner.isBinderAlive()) {
                try { components.routeInternal(EngineProtocol.UNBIND_SERVICE, request); }
                catch (Exception failure) { throw new EngineException("Authenticator unbind failed", failure); }
            }
        };
        try { connection.onConnected(authenticator); }
        catch (RuntimeException failure) {
            binding.close();
            throw failure;
        }
        return binding;
    }
}
