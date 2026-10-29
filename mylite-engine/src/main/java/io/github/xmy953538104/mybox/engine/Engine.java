package io.github.xmy953538104.mybox.engine;

import android.app.Application;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.io.IOException;
import java.util.List;

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;

/** Entry point for the independently reconstructed engine. */
public final class Engine {
    private static volatile Engine instance;
    private final Context hostContext;
    private final PackageStore packageStore;
    private final GuestRuntime guestRuntime;
    private final Object connectionLock = new Object();
    private volatile ProviderConnection server;

    private Engine(Context context) {
        hostContext = context;
        packageStore = new PackageStore(context, this::packagePublished);
        guestRuntime = new GuestRuntime(this);
    }

    public static synchronized void attach(Context context) {
        if (instance != null) return;
        EngineDiagnostics.initialize(context);
        try {
            if (Build.VERSION.SDK_INT >= 28 && !HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/", "Ldalvik/system/")) {
                throw new EngineException("Android rejected the required runtime API access");
            }
            Object thread = RuntimeReflection.activityThread();
            if (RuntimeReflection.get(thread, "mH") == null
                    || RuntimeReflection.get(thread, "mBoundApplication") == null) {
                throw new EngineException("Android runtime binding is unavailable");
            }
            RuntimeReflection.field(android.os.Handler.class, "mCallback");
            RuntimeReflection.method(Class.forName("android.app.LoadedApk"), "makeApplication",
                    boolean.class, android.app.Instrumentation.class);
            instance = new Engine(context);
        } catch (Exception failure) {
            EngineDiagnostics.failure("engine", "attach", 0, failure);
            throw new EngineException("Required Android runtime APIs are inaccessible", failure);
        }
    }

    public static Engine get() {
        Engine value = instance;
        if (value == null) throw new IllegalStateException("Engine.attach must run in attachBaseContext");
        return value;
    }

    public void onCreate(Application application) {
        // Guest Application creation is performed only after a validated process binding request.
        // Merely starting a host provider process does not execute any installed APK.
    }

    public PackageStore packages() { return packageStore; }

    public InstalledPackage install(List<File> files) throws IOException {
        return packageStore.install(files);
    }

    private void packagePublished(InstalledPackage previous, InstalledPackage installed) {
        Bundle request = EngineProtocol.response();
        request.putString("package", installed.packageName);
        request.putString("generation", installed.baseApk.getParentFile().getName());
        request.putBoolean("replacing", previous != null);
        EngineProtocol.request(server(), EngineProtocol.SERVER, EngineProtocol.PACKAGE_PUBLISHED, request);
    }

    /** Performs IPC and package reads; callers must use a worker thread. */
    public void launch(String packageName) {
        GoogleLaunchPolicy.require(this, packageName);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new EngineException("Launch preparation must run on a worker thread");
        }
        ActivityInfo info;
        try {
            info = packageStore.findLaunchActivity(packageName);
        } catch (IOException failure) {
            throw new EngineException("Cannot read installed package " + packageName, failure);
        }
        if (info == null || !info.enabled) {
            throw new EngineException("No enabled launch activity for " + packageName);
        }
        Intent original = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(packageName, info.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Bundle request = EngineProtocol.response();
        request.putString("operation", "prepare");
        request.putParcelable("intent", original);
        Bundle launch = EngineProtocol.request(server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, request);
        if (launch.getBoolean("queued")) return;
        Bundle outcome = ActivityEnvelope.operation(launch, "start_result", null);
        try {
            hostContext.startActivity(launch.getParcelable("outer_intent"));
            outcome.putBoolean("accepted", true);
        } catch (RuntimeException failure) {
            outcome.putBoolean("accepted", false);
            try { EngineProtocol.request(server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, outcome); }
            catch (RuntimeException reportFailure) { failure.addSuppressed(reportFailure); }
            throw failure;
        }
        EngineProtocol.request(server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, outcome);
    }

    public void prepareGoogleServices() {
        Bundle request = EngineProtocol.response(); request.putString("operation", "prepare");
        EngineProtocol.request(server(), EngineProtocol.SERVER, EngineProtocol.GOOGLE_SERVICES, request);
    }

    public String googleServiceStatus() {
        Bundle request = EngineProtocol.response(); request.putString("operation", "status");
        return EngineProtocol.request(server(), EngineProtocol.SERVER, EngineProtocol.GOOGLE_SERVICES, request).getString("notice", "");
    }

    public void captureDiagnostics() {
        if (!EngineDiagnostics.enabled()) return;
        EngineProtocol.request(server(), EngineProtocol.SERVER, EngineProtocol.DIAGNOSTICS, EngineProtocol.response());
    }

    IBinder server() {
        ProviderConnection cached = server;
        if (cached != null && cached.isAlive()) return cached.binder;
        // A bare cached Binder has no Android OOM/freezer dependency. Retain the Android
        // provider lease while using its service, including from a guest process.
        // The owning host can reconnect after server death instead of being killed by its lease.
        ContentProviderClient provider = hostContext.getContentResolver().acquireUnstableContentProviderClient(
                Uri.parse("content://" + hostContext.getPackageName() + ".engine.server"));
        if (provider == null) throw new EngineException("Server provider is unavailable");
        ProviderConnection candidate;
        boolean transferred = false;
        try {
            Bundle result;
            try { result = provider.call("connect", null, EngineProtocol.response()); }
            catch (android.os.DeadObjectException stale) {
                provider.close(); provider = null;
                provider = hostContext.getContentResolver().acquireUnstableContentProviderClient(
                        Uri.parse("content://" + hostContext.getPackageName() + ".engine.server"));
                if (provider == null) throw new EngineException("Replacement server provider is unavailable", stale);
                android.util.Log.i("MyboxProvider", "Reacquiring server bootstrap after cached Provider death");
                result = provider.call("connect", null, EngineProtocol.response());
            }
            if (result == null || result.getInt("protocol") != EngineProtocol.VERSION) {
                throw new EngineException("Server provider did not complete its handshake");
            }
            IBinder connected = result.getBinder("server");
            if (connected == null || !connected.isBinderAlive()) {
                throw new EngineException("Server provider returned no live service");
            }
            transferred = true;
            candidate = ProviderConnection.connect(connected, provider, dead -> {
                synchronized (connectionLock) { if (server == dead) server = null; }
            });
        } catch (android.os.RemoteException failure) {
            throw new EngineException("Server provider disconnected during its handshake", failure);
        } finally {
            if (!transferred && provider != null) provider.close();
        }
        ProviderConnection previous;
        ProviderConnection selected;
        synchronized (connectionLock) {
            previous = server;
            if (previous != null && previous.isAlive()) selected = previous;
            else if (candidate.isAlive()) server = selected = candidate;
            else selected = null;
        }
        // Losing candidates and displaced connections release Android references outside locks.
        if (selected != candidate) candidate.close();
        if (previous != null && selected != previous) previous.close();
        if (selected == null) throw new EngineException("Server died before its connection was published");
        return selected.binder;
    }

    Context hostContext() { return hostContext; }
    GuestRuntime runtime() { return guestRuntime; }
}
