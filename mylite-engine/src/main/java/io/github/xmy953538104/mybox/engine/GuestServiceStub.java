package io.github.xmy953538104.mybox.engine;

import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.IBinder;

/** Each class owns one Android ServiceRecord, so foreground state cannot spill between guests. */
public abstract class GuestServiceStub extends Service {
    private static GuestRuntime runtime;
    static void install(GuestRuntime value) { runtime = value; }
    private GuestComponents components() {
        if (runtime == null || runtime.components() == null) {
            // shortcut: process-death redelivery needs a server-authorized package/generation recovery protocol.
            throw new EngineException("Foreground Service cold recovery is not implemented");
        }
        return runtime.components();
    }
    @Override public final void onCreate() {
        super.onCreate();
        try { components().physicalCreated(this); }
        catch (RuntimeException failure) { stopSelf(); throw failure; }
    }
    @Override public final IBinder onBind(Intent intent) { return null; }
    @Override public final int onStartCommand(Intent intent, int flags, int startId) {
        try { return components().physicalStart(this, intent, flags, startId); }
        catch (RuntimeException failure) { stopSelf(); throw failure; }
        catch (Exception failure) { stopSelf(); throw new EngineException("Guest started Service failed", failure); }
    }
    @Override public final void onDestroy() { components().physicalDestroyed(this); super.onDestroy(); }
    @Override public final void onTaskRemoved(Intent rootIntent) { components().physicalTaskRemoved(this, rootIntent); }
    @Override public final void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); components().physicalConfiguration(this, config); }
    @Override public final void onLowMemory() { super.onLowMemory(); components().physicalMemory(this, -1); }
    @Override public final void onTrimMemory(int level) { super.onTrimMemory(level); components().physicalMemory(this, level); }
    @Override public final void onTimeout(int startId) { components().physicalTimeout(this, startId, -1); }
    @Override public final void onTimeout(int startId, int type) { components().physicalTimeout(this, startId, type); }

    public static final class P0S0 extends GuestServiceStub { }
    public static final class P0S1 extends GuestServiceStub { }
    public static final class P0S2 extends GuestServiceStub { }
    public static final class P0S3 extends GuestServiceStub { }
    public static final class P1S0 extends GuestServiceStub { }
    public static final class P1S1 extends GuestServiceStub { }
    public static final class P1S2 extends GuestServiceStub { }
    public static final class P1S3 extends GuestServiceStub { }
    public static final class P2S0 extends GuestServiceStub { }
    public static final class P2S1 extends GuestServiceStub { }
    public static final class P2S2 extends GuestServiceStub { }
    public static final class P2S3 extends GuestServiceStub { }
    public static final class P3S0 extends GuestServiceStub { }
    public static final class P3S1 extends GuestServiceStub { }
    public static final class P3S2 extends GuestServiceStub { }
    public static final class P3S3 extends GuestServiceStub { }
}
