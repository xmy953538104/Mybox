package io.github.xmy953538104.mybox.engine;

import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.IBinder;

/** Each class owns one Android ServiceRecord, so foreground state cannot spill between guests. */
public abstract class GuestServiceStub extends Service {
    private static GuestRuntime runtime;
    private GuestComponents owner;
    static void install(GuestRuntime value) { runtime = value; }
    @Override public final void onCreate() {
        super.onCreate();
        GuestComponents current = runtime == null ? null : runtime.components();
        try {
            if (current == null || !current.physicalCreated(this)) {
                // Cold OS redelivery cannot authorize a guest; the server must create a fresh reservation.
                android.util.Log.w("MyliteService", "Cold Service refused: " + getClass().getName());
                stopSelf(); return;
            }
            owner = current;
        } catch (RuntimeException failure) {
            stopSelf(); throw failure;
        }
    }
    @Override public final IBinder onBind(Intent intent) { return null; }
    @Override public final int onStartCommand(Intent intent, int flags, int startId) {
        if (owner == null) { stopSelf(startId); return START_NOT_STICKY; }
        try { return owner.physicalStart(this, intent, flags, startId); }
        catch (RuntimeException failure) { stopSelf(); throw failure; }
        catch (Exception failure) { stopSelf(); throw new EngineException("Guest started Service failed", failure); }
    }
    @Override public final void onDestroy() {
        try { if (owner != null) owner.physicalDestroyed(this); }
        finally { owner = null; super.onDestroy(); }
    }
    @Override public final void onTaskRemoved(Intent rootIntent) { if (owner != null) owner.physicalTaskRemoved(this, rootIntent); }
    @Override public final void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); if (owner != null) owner.physicalConfiguration(this, config); }
    @Override public final void onLowMemory() { super.onLowMemory(); if (owner != null) owner.physicalMemory(this, -1); }
    @Override public final void onTrimMemory(int level) { super.onTrimMemory(level); if (owner != null) owner.physicalMemory(this, level); }
    @Override public final void onTimeout(int startId) { if (owner != null) owner.physicalTimeout(this, startId, -1); }
    @Override public final void onTimeout(int startId, int type) { if (owner != null) owner.physicalTimeout(this, startId, type); }

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
    public static final class P4S0 extends GuestServiceStub { }
    public static final class P4S1 extends GuestServiceStub { }
    public static final class P4S2 extends GuestServiceStub { }
    public static final class P4S3 extends GuestServiceStub { }
    public static final class P5S0 extends GuestServiceStub { }
    public static final class P5S1 extends GuestServiceStub { }
    public static final class P5S2 extends GuestServiceStub { }
    public static final class P5S3 extends GuestServiceStub { }
    public static final class P6S0 extends GuestServiceStub { }
    public static final class P6S1 extends GuestServiceStub { }
    public static final class P6S2 extends GuestServiceStub { }
    public static final class P6S3 extends GuestServiceStub { }
    public static final class P7S0 extends GuestServiceStub { }
    public static final class P7S1 extends GuestServiceStub { }
    public static final class P7S2 extends GuestServiceStub { }
    public static final class P7S3 extends GuestServiceStub { }
    public static final class P8S0 extends GuestServiceStub { }
    public static final class P8S1 extends GuestServiceStub { }
    public static final class P8S2 extends GuestServiceStub { }
    public static final class P8S3 extends GuestServiceStub { }
    public static final class P9S0 extends GuestServiceStub { }
    public static final class P9S1 extends GuestServiceStub { }
    public static final class P9S2 extends GuestServiceStub { }
    public static final class P9S3 extends GuestServiceStub { }
    public static final class P10S0 extends GuestServiceStub { }
    public static final class P10S1 extends GuestServiceStub { }
    public static final class P10S2 extends GuestServiceStub { }
    public static final class P10S3 extends GuestServiceStub { }
    public static final class P11S0 extends GuestServiceStub { }
    public static final class P11S1 extends GuestServiceStub { }
    public static final class P11S2 extends GuestServiceStub { }
    public static final class P11S3 extends GuestServiceStub { }
    public static final class P12S0 extends GuestServiceStub { }
    public static final class P12S1 extends GuestServiceStub { }
    public static final class P12S2 extends GuestServiceStub { }
    public static final class P12S3 extends GuestServiceStub { }
    public static final class P13S0 extends GuestServiceStub { }
    public static final class P13S1 extends GuestServiceStub { }
    public static final class P13S2 extends GuestServiceStub { }
    public static final class P13S3 extends GuestServiceStub { }
    public static final class P14S0 extends GuestServiceStub { }
    public static final class P14S1 extends GuestServiceStub { }
    public static final class P14S2 extends GuestServiceStub { }
    public static final class P14S3 extends GuestServiceStub { }
    public static final class P15S0 extends GuestServiceStub { }
    public static final class P15S1 extends GuestServiceStub { }
    public static final class P15S2 extends GuestServiceStub { }
    public static final class P15S3 extends GuestServiceStub { }
}
