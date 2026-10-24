package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

/** Bootstrap authority for the engine's dedicated server process. */
public final class EngineServerProvider extends EngineProvider {
    private static volatile ProcessRegistry registry;
    private ProcessRegistry processes;
    private ComponentRouter components;
    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(EngineProtocol.SERVER);
                return true;
            }
            EngineProtocol.requireCaller();
            data.enforceInterface(EngineProtocol.SERVER);
            Bundle result;
            try {
                Bundle input = data.readBundle(getClass().getClassLoader());
                if (code == EngineProtocol.ACQUIRE) {
                    EngineProtocol.requireProcess(getContext(), getContext().getPackageName());
                    result = processes.acquire(input);
                } else if (code >= EngineProtocol.START_SERVICE && code <= EngineProtocol.ACQUIRE_PROVIDER) {
                    result = components.route(code, input, resolveCaller());
                } else return false;
            } catch (Exception e) {
                result = EngineProtocol.failure(e);
            }
            reply.writeNoException();
            reply.writeBundle(result);
            return true;
        }
    };

    @Override public boolean onCreate() {
        super.onCreate();
        processes = new ProcessRegistry(Engine.get());
        registry = processes;
        components = new ComponentRouter(Engine.get(), processes);
        binder.attachInterface(null, EngineProtocol.SERVER);
        return true;
    }

    public static CallerIdentity resolveCaller() {
        ProcessRegistry current = registry;
        if (current == null) throw new SecurityException("Guest identity resolution requires the engine server");
        try {
            return current.resolveCaller(Binder.getCallingUid(), Binder.getCallingPid());
        } catch (SecurityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Guest identity could not be verified", failure);
        }
    }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        EngineProtocol.requireCaller();
        if (!"connect".equals(method)) throw new IllegalArgumentException("Unknown server operation");
        Bundle result = EngineProtocol.response();
        result.putBinder("server", binder);
        return result;
    }
}
