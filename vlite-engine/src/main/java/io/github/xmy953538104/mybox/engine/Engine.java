package io.github.xmy953538104.mybox.engine;

import android.app.Application;
import android.content.ComponentName;
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
    private volatile IBinder server;

    private Engine(Context context) {
        hostContext = context;
        packageStore = new PackageStore(context);
        guestRuntime = new GuestRuntime(this);
    }

    public static synchronized void attach(Context context) {
        if (instance != null) return;
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

    /** Performs IPC and package reads; callers must use a worker thread. */
    public void launch(String packageName) {
        if (GuestPackageQueries.isGoogleIdentity(packageName)) {
            throw new EngineException("Google runtime launch is unavailable until virtual accounts and component routing pass validation");
        }
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
        String processName = info.processName == null ? packageName : info.processName;
        Bundle request = EngineProtocol.response();
        request.putString("package", packageName);
        request.putString("process", processName);
        Bundle process = EngineProtocol.request(server(), EngineProtocol.SERVER,
                EngineProtocol.ACQUIRE, request);
        int slot = process.getInt("slot", -1);
        if (slot < 0 || slot >= EngineProtocol.SLOT_COUNT) {
            throw new EngineException("Server returned an invalid process slot");
        }
        Bundle launch = EngineProtocol.response();
        launch.putString("package", packageName);
        launch.putString("activity", info.name);
        launch.putLong("generation", process.getLong("generation"));
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(hostContext, GuestActivity.class.getName() + "$P" + slot));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        intent.putExtra(EngineProtocol.LAUNCH, launch);
        hostContext.startActivity(intent);
    }

    private IBinder server() {
        IBinder cached = server;
        if (cached != null && cached.isBinderAlive()) return cached;
        Bundle result = hostContext.getContentResolver().call(
                Uri.parse("content://" + hostContext.getPackageName() + ".engine.server"),
                "connect", null, EngineProtocol.response());
        if (result == null || result.getInt("protocol") != EngineProtocol.VERSION) {
            throw new EngineException("Server provider did not complete its handshake");
        }
        IBinder connected = result.getBinder("server");
        if (connected == null || !connected.isBinderAlive()) {
            throw new EngineException("Server provider returned no live service");
        }
        server = connected;
        return connected;
    }

    Context hostContext() { return hostContext; }
    GuestRuntime runtime() { return guestRuntime; }
}
