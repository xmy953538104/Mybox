package io.github.xmy953538104.mybox.engine;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Debug-only real ServiceRecord for the production carrier token/late-start boundary. */
public final class BoundCarrierTokenProbeService extends Service {
    static final String DESCRIPTOR = "mybox.synthetic.BoundCarrierToken";
    static final int DESCRIBE = IBinder.FIRST_CALL_TRANSACTION, BARRIER = DESCRIBE + 1, GROUP = DESCRIBE + 2;
    private final String instance = UUID.randomUUID().toString();
    private final Bundle owner = BoundServiceWire.owner(new Binder(), 1, instance, Process.myPid());
    private BoundCarrierLifecycle lifecycle;
    private int serverPid, validStarts, staleStarts;
    private String run;
    private void initialize(Intent intent) {
        String nonce = intent.getStringExtra("run"); UUID.fromString(nonce);
        int caller = intent.getIntExtra("server_pid", -1);
        if (caller <= 0 || (serverPid != 0 && serverPid != caller) || (run != null && !run.equals(nonce))) throw new SecurityException("Probe scope changed");
        run = nonce; serverPid = caller;
        if (lifecycle == null) lifecycle = new BoundCarrierLifecycle(this, owner, serverPid);
    }
    @Override public IBinder onBind(Intent intent) { initialize(intent); return endpoint; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        initialize(intent);
        if (BoundCarrierLifecycle.acceptStart(this, owner, true, intent, startId)) validStarts++; else staleStarts++;
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        try { if (run != null) Files.write(new File(getFilesDir(), "carrier-token-" + run + "-destroy-" + instance).toPath(), new byte[]{1}); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        finally { super.onDestroy(); }
    }
    private Bundle group(Bundle input) throws Exception {
        // Debug-only observation of this fixture's actual server capabilities, never a production routing API.
        android.content.ComponentName component = input.getParcelable("component");
        if (component == null || !component.getClassName().equals("io.github.xmy953538104.mybox.fixture.BoundServiceProbe$One"))
            throw new SecurityException("Only the owned bound fixture may be observed");
        Object provider = RuntimeReflection.field(EngineServerProvider.class, "instance").get(null);
        Object router = RuntimeReflection.get(provider, "components"), server = RuntimeReflection.get(router, "boundServices");
        Object selected = null;
        synchronized (server) {
            for (Object candidate : (java.util.List<?>) RuntimeReflection.get(server, "groups")) {
                if (component.equals(RuntimeReflection.get(candidate, "component")) && !(Boolean) RuntimeReflection.get(candidate, "retired")) {
                    android.content.Intent.FilterComparison filter = (android.content.Intent.FilterComparison) RuntimeReflection.get(candidate, "filter");
                    if ("incarnation".equals(filter.getIntent().getAction())) { selected = candidate; break; }
                }
            }
        }
        if (selected == null) throw new IllegalStateException("Owned fixture has no live incarnation group");
        ProcessRegistry.Identity actual = (ProcessRegistry.Identity) RuntimeReflection.get(selected, "owner");
        Bundle result = EngineProtocol.response(); result.putInt("slot", actual.slot); result.putInt("pid", actual.pid);
        result.putParcelable("outer", (Intent) RuntimeReflection.invoke(server,
                RuntimeReflection.method(server.getClass(), "outer", selected.getClass()), selected)); return result;
    }
    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            if (code != DESCRIBE && code != BARRIER && code != GROUP) return false;
            EngineProtocol.requireCaller(); data.enforceInterface(DESCRIPTOR);
            if (Binder.getCallingPid() != serverPid) throw new SecurityException("Unexpected probe client");
            Bundle input = data.readBundle(getClass().getClassLoader());
            if (code == GROUP) {
                Bundle result;
                try { result = group(input); } catch (Exception failure) { result = EngineProtocol.failure(failure); }
                reply.writeNoException(); reply.writeBundle(result); return true;
            }
            FutureTask<Bundle> capture = new FutureTask<>(() -> {
                Bundle result = EngineProtocol.response(); result.putString("instance", instance); result.putBundle("owner", new Bundle(owner));
                result.putBinder("lifecycle", lifecycle); result.putInt("valid_starts", validStarts); result.putInt("stale_starts", staleStarts); return result;
            });
            new Handler(getMainLooper()).post(capture);
            Bundle result;
            try { result = capture.get(5, TimeUnit.SECONDS); } catch (Exception failure) { result = EngineProtocol.failure(failure); }
            reply.writeNoException(); reply.writeBundle(result); return true;
        }
    };
}
