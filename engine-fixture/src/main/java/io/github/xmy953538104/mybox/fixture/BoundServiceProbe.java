package io.github.xmy953538104.mybox.fixture;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Ordinary public Android API journeys; no engine dependency or synthetic callbacks. */
public final class BoundServiceProbe {
    private static final String DESCRIPTOR = "mybox.fixture.bound";
    private static File file(Context context, String run, String name) { return new File(context.getFilesDir(), "bound-" + run + "-" + name); }
    private static void mark(Context context, String run, String name) {
        try { Files.write(file(context, run, name).toPath(), new byte[]{1}); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void waitFile(File file) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 12_000;
        while (!file.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10);
        if (!file.isFile()) throw new IllegalStateException("Missing owned fixture phase " + file.getName());
    }
    private static final class Connection implements ServiceConnection {
        final CountDownLatch delivered = new CountDownLatch(1), died = new CountDownLatch(1), terminal = new CountDownLatch(1);
        volatile IBinder binder;
        volatile boolean nullBinding;
        volatile int callbacks, terminalCallbacks;
        @Override public void onServiceConnected(ComponentName name, IBinder value) { binder = value; callbacks++; delivered.countDown(); }
        @Override public void onNullBinding(ComponentName name) { nullBinding = true; callbacks++; delivered.countDown(); }
        @Override public void onServiceDisconnected(ComponentName name) { died.countDown(); }
        @Override public void onBindingDied(ComponentName name) { terminalCallbacks++; terminal.countDown(); died.countDown(); }
        void await() throws Exception { if (!delivered.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("No actual Service publication"); }
    }
    private static Intent intent(Context context, Class<?> type, String run, String action) {
        return new Intent(context, type).setAction(action).putExtra("run", run);
    }
    private static JSONObject inspect(IBinder binder) throws Exception {
        if (binder == null || !DESCRIPTOR.equals(binder.getInterfaceDescriptor())) throw new IllegalStateException("Received control or absent guest Binder");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) throw new IllegalStateException("Guest transaction rejected");
            reply.readException(); return new JSONObject(reply.readString());
        } finally { data.recycle(); reply.recycle(); }
    }
    private static void terminateOwnedWorker(IBinder binder) throws Exception {
        inspect(binder);
        CountDownLatch death = new CountDownLatch(1); IBinder.DeathRecipient observed = death::countDown;
        binder.linkToDeath(observed, 0);
        try {
            Parcel input = Parcel.obtain(), reply = Parcel.obtain();
            try {
                input.writeInterfaceToken(DESCRIPTOR);
                require(binder.transact(IBinder.FIRST_CALL_TRANSACTION + 1, input, reply, 0), "Owned worker termination rejected"); reply.readException();
            } finally { input.recycle(); reply.recycle(); }
            long end = SystemClock.elapsedRealtime() + 10_000;
            while ((death.getCount() != 0 || binder.pingBinder()) && SystemClock.elapsedRealtime() < end) death.await(10, TimeUnit.MILLISECONDS);
            require(death.getCount() == 0 && !binder.pingBinder(), "Owned worker did not actually exit");
        } finally {
            try { binder.unlinkToDeath(observed, 0); } catch (java.util.NoSuchElementException alreadyDead) { /* Death already consumed this link. */ }
        }
    }
    private static void rejected(Context context, Intent intent, int flags, String reason) throws Exception {
        Connection connection = new Connection(); boolean bound = false; RuntimeException denied = null;
        try { bound = context.bindService(intent, connection, flags); }
        catch (RuntimeException expected) { denied = expected; }
        finally {
            try { context.unbindService(connection); }
            catch (IllegalArgumentException notRegistered) { if (bound) throw notRegistered; }
        }
        require(denied != null && denied.getMessage() != null && denied.getMessage().contains(reason), "Missing or wrong bind rejection: " + denied);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    public static void run(Context context, String run) {
        UUID.fromString(run);
        new Thread(() -> {
            JSONObject report = new JSONObject(); ArrayList<Connection> owned = new ArrayList<>(); ArrayList<Intent> starts = new ArrayList<>();
            try {
                // A blocked real Application.onCreate cannot delay real bind admission.
                Files.write(new File(context.getFilesDir(), "bound-prepare-run").toPath(), run.getBytes(StandardCharsets.UTF_8));
                Connection gate = new Connection(); Intent gateIntent = intent(context, Gate.class, run, "prepare");
                long before = SystemClock.elapsedRealtime();
                require(context.bindService(gateIntent, gate, Context.BIND_AUTO_CREATE), "Android rejected gate binding"); owned.add(gate);
                require(SystemClock.elapsedRealtime() - before < 8_000, "bind admission waited for Application completion");
                waitFile(file(context, run, "application-entered"));
                require(gate.callbacks == 0 && !file(context, run, "application-returned").exists(), "Publication preceded Application completion");
                mark(context, run, "application-release"); gate.await(); inspect(gate.binder);
                context.unbindService(gate); owned.remove(gate); waitFile(file(context, run, "Gate-destroy")); terminateOwnedWorker(gate.binder);
                report.put("preparedAdmission", true);

                // No AUTO_CREATE, no carrier: accepted pending request produces neither object nor null callback.
                Intent passiveIntent = intent(context, One.class, run, "passive"); Connection passive = new Connection();
                require(context.bindService(passiveIntent, passive, 0), "Android rejected passive binding"); owned.add(passive);
                Thread.sleep(200);
                require(passive.callbacks == 0 && !file(context, run, "One-create").exists(), "Passive bind created or published an inactive Service");
                require(context.startService(passiveIntent) != null, "Explicit start failed"); starts.add(passiveIntent);
                passive.await(); JSONObject passiveInfo = inspect(passive.binder);
                require(context.stopService(passiveIntent), "Started passive Service did not stop"); starts.remove(passiveIntent);
                require(passive.terminal.await(10, TimeUnit.SECONDS), "Passive binding incorrectly retained stopped Service");
                waitFile(file(context, run, passiveInfo.getString("instance") + "-destroy"));
                require(passive.terminalCallbacks == 1, "Explicit stop must terminate the old no-AUTO binding");
                int previousCallbacks = passive.callbacks;
                require(context.startService(passiveIntent) != null, "Explicit restart failed"); starts.add(passiveIntent);
                Connection restarted = new Connection(); require(context.bindService(passiveIntent, restarted, 0), "Fresh passive bind after restart failed"); owned.add(restarted); restarted.await();
                require(!passiveInfo.getString("instance").equals(inspect(restarted.binder).getString("instance")), "Restart reused destroyed Service instance");
                require(passive.callbacks == previousCallbacks, "Explicit restart resurrected a dead old connection");
                context.unbindService(restarted); owned.remove(restarted); context.stopService(passiveIntent); starts.remove(passiveIntent);
                context.unbindService(passive); owned.remove(passive); waitFile(file(context, run, "One-destroy"));
                report.put("passivePendingThenStart", true);

                // Same-filter clients share an actual publication, rebind after last unbind while started.
                Intent sharedIntent = intent(context, Two.class, run, "rebind").putExtra("first", "kept")
                        .setDataAndType(android.net.Uri.parse("mybox://bound/original"), "text/plain").addCategory("fixture.bound.original").setFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);
                Bundle originalExtras = new Bundle(); originalExtras.putInt("number", 11); originalExtras.putBinder("marker", new Binder());
                sharedIntent.putExtra("callback_numbers", new int[]{7}).putExtra("callback_nested", originalExtras).putExtra("mutate_callback_intent", true);
                require(context.startService(sharedIntent) != null, "Shared start failed"); starts.add(sharedIntent);
                Connection first = new Connection(), second = new Connection();
                require(context.bindService(sharedIntent, first, Context.BIND_AUTO_CREATE), "First shared bind rejected"); owned.add(first); first.await();
                Bundle laterExtras = new Bundle(originalExtras); laterExtras.putInt("number", 22);
                require(context.bindService(new Intent(sharedIntent).putExtra("first", "changed").putExtra("callback_numbers", new int[]{55}).putExtra("callback_nested", laterExtras), second, Context.BIND_AUTO_CREATE), "Second shared bind rejected"); owned.add(second); second.await();
                require(first.binder.equals(second.binder), "Same filter returned different guest Binders");
                JSONObject shared = inspect(second.binder); require(shared.getInt("binds") == 1 && "kept".equals(shared.getString("first")), "Filter sharing used later extras");
                require("mybox://bound/original".equals(shared.getString("data")) && "text/plain".equals(shared.getString("type"))
                        && shared.getInt("flags") == Intent.FLAG_DEBUG_LOG_RESOLUTION && shared.getBoolean("category"), "Original Intent semantics were lost");
                report.put("originalIntent", true);
                context.unbindService(first); owned.remove(first); context.unbindService(second); owned.remove(second);
                waitFile(file(context, run, "Two-unbind"));
                requireCanonical(inspect(second.binder).getJSONObject("unbindIntent"), "onUnbind");
                Bundle reboundExtras = new Bundle(originalExtras); reboundExtras.putInt("number", 33);
                Intent newExtras = new Intent(sharedIntent).putExtra("first", "rebind-new").putExtra("callback_numbers", new int[]{77}).putExtra("callback_nested", reboundExtras);
                Connection rebound = new Connection(); require(context.bindService(newExtras, rebound, Context.BIND_AUTO_CREATE), "Rebind rejected"); owned.add(rebound); rebound.await();
                JSONObject reboundInfo = inspect(rebound.binder); require(reboundInfo.getInt("binds") == 1 && reboundInfo.getInt("rebinds") == 1, "Actual onRebind did not preserve cached publication");
                requireCanonical(reboundInfo.getJSONObject("bindIntent"), "onBind");
                requireCanonical(reboundInfo.getJSONObject("rebindIntent"), "onRebind after mutating onUnbind");
                report.put("canonicalLifecycleIntent", true);
                context.unbindService(rebound); owned.remove(rebound); context.stopService(sharedIntent); starts.remove(sharedIntent); waitFile(file(context, run, "Two-destroy"));
                report.put("sharedFilterAndRebind", true);

                // Keep one carrier alive while a different no-AUTO target remains inactive.
                Connection keep = new Connection(); Intent keepIntent = intent(context, Three.class, run, "keep");
                require(context.bindService(keepIntent, keep, Context.BIND_AUTO_CREATE), "Keeper bind rejected"); owned.add(keep); keep.await();
                Connection pending = new Connection(); Intent pendingIntent = intent(context, Four.class, run, "pending");
                require(context.bindService(pendingIntent, pending, 0), "Existing-carrier passive bind rejected"); owned.add(pending);
                Thread.sleep(200); require(pending.callbacks == 0 && !file(context, run, "Four-create").exists(), "Existing carrier faked null or created passive target");
                context.unbindService(keep); owned.remove(keep);
                Thread.sleep(200); require(pending.callbacks == 0 && pending.terminalCallbacks == 0, "Another filter's last AUTO unbind terminated a pending connection");
                require(context.startService(pendingIntent) != null, "Pending explicit start rejected"); starts.add(pendingIntent); pending.await(); inspect(pending.binder);
                context.unbindService(pending); owned.remove(pending); context.stopService(pendingIntent); starts.remove(pendingIntent); waitFile(file(context, run, "Four-destroy"));
                report.put("existingCarrierPending", true);
                keep = new Connection(); require(context.bindService(keepIntent, keep, Context.BIND_AUTO_CREATE), "Keeper rebind rejected"); owned.add(keep); keep.await();

                // Real onBind(null) is different from a pending/no-AUTO connection.
                Connection nullable = new Connection(); Intent nullIntent = intent(context, Five.class, run, "null");
                require(context.bindService(nullIntent, nullable, Context.BIND_AUTO_CREATE), "Null binding admission rejected"); owned.add(nullable); nullable.await();
                require(nullable.nullBinding && nullable.binder == null, "Actual null did not call onNullBinding");
                context.unbindService(nullable); owned.remove(nullable); waitFile(file(context, run, "Five-destroy"));
                report.put("actualNull", true);
                rejected(context, new Intent(keepIntent).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), Context.BIND_AUTO_CREATE, "URI grants");
                Intent clipped = new Intent(keepIntent); clipped.setClipData(android.content.ClipData.newPlainText("probe", "owned"));
                rejected(context, clipped, Context.BIND_AUTO_CREATE, "URI grants");
                rejected(context, new Intent(keepIntent), Context.BIND_AUTO_CREATE | Context.BIND_EXTERNAL_SERVICE, "External or isolated");
                if (android.os.Build.VERSION.SDK_INT >= 34) rejected(context, new Intent(keepIntent), Context.BIND_AUTO_CREATE | Context.BIND_SHARED_ISOLATED_PROCESS, "External or isolated");
                report.put("negativeBoundaries", true);

                // A blocking real onBind cannot block admission or early unbind; late publication is suppressed.
                Intent slowIntent = intent(context, Slow.class, run, "slow"); Connection early = new Connection();
                before = SystemClock.elapsedRealtime(); require(context.bindService(slowIntent, early, Context.BIND_AUTO_CREATE), "Slow bind rejected"); owned.add(early);
                require(SystemClock.elapsedRealtime() - before < 8_000, "Admission waited for actual onBind"); waitFile(file(context, run, "Slow-entered"));
                before = SystemClock.elapsedRealtime(); context.unbindService(early); owned.remove(early);
                require(SystemClock.elapsedRealtime() - before < 2_000, "Early unbind waited for blocked guest main");
                mark(context, run, "Slow-release"); waitFile(file(context, run, "Slow-destroy")); Thread.sleep(100);
                require(early.callbacks == 0, "Late onBind resurrected a closed lease"); report.put("earlyUnbind", true);

                // Five simultaneous virtual objects use one bound carrier, independently of four started slots.
                for (Class<?> type : new Class<?>[]{One.class, Two.class, Four.class, Five.class}) {
                    Connection value = new Connection(); require(context.bindService(intent(context, type, run, "capacity"), value, Context.BIND_AUTO_CREATE), "Bound capacity rejected"); owned.add(value); value.await(); inspect(value.binder);
                }
                report.put("fiveBoundObjects", owned.size() == 5); report.put("workerPid", inspect(keep.binder).getInt("pid"));
                mark(context, run, "capacity-ready"); waitFile(file(context, run, "capacity-ack"));
                for (Connection value : new ArrayList<>(owned)) { context.unbindService(value); owned.remove(value); }
                waitFile(file(context, run, "Three-destroy"));

                // Recreated target while another filter keeps the physical ServiceRecord alive gets a new Binder.
                Connection keeper = new Connection(); require(context.bindService(keepIntent, keeper, Context.BIND_AUTO_CREATE), "Recreation keeper rejected"); owned.add(keeper); keeper.await();
                Connection old = new Connection(); Intent recreate = intent(context, One.class, run, "incarnation");
                require(context.bindService(recreate, old, Context.BIND_AUTO_CREATE), "Old incarnation rejected"); owned.add(old); old.await(); String oldId = inspect(old.binder).getString("instance");
                context.unbindService(old); owned.remove(old);
                // Wait for a new observed destroy rather than reusing a stale earlier phase file.
                waitFile(file(context, run, oldId + "-destroy"));
                Connection fresh = new Connection(); require(context.bindService(recreate, fresh, Context.BIND_AUTO_CREATE), "New incarnation rejected"); owned.add(fresh); fresh.await();
                JSONObject freshInfo = inspect(fresh.binder);
                require(!oldId.equals(freshInfo.getString("instance")), "Cached carrier resurrected destroyed guest Binder"); report.put("newIncarnation", true);
                context.unbindService(fresh); owned.remove(fresh); context.unbindService(keeper); owned.remove(keeper);
                Connection death = new Connection(); require(context.bindService(recreate, death, Context.BIND_AUTO_CREATE), "Death fixture bind rejected"); owned.add(death); death.await();
                String deadId = inspect(death.binder).getString("instance");
                mark(context, run, "before-death"); waitFile(file(context, run, "before-death-ack"));
                terminateOwnedWorker(death.binder);
                require(death.died.await(10, TimeUnit.SECONDS), "Target death did not disconnect the real connection");
                try { context.unbindService(death); } finally { owned.remove(death); }
                Connection replacement = new Connection(); require(context.bindService(recreate, replacement, Context.BIND_AUTO_CREATE), "Explicit bind after actual process death failed"); owned.add(replacement); replacement.await();
                require(!deadId.equals(inspect(replacement.binder).getString("instance")), "Dead reservation was reused");
                String replacementId = inspect(replacement.binder).getString("instance");
                mark(context, run, "after-death"); waitFile(file(context, run, "after-death-ack"));
                require(replacementId.equals(inspect(replacement.binder).getString("instance")), "Old OS group redelivery replaced or killed the new guest Service");
                report.put("staleGroupSafe", true);
                context.unbindService(replacement); owned.remove(replacement); report.put("processDeath", true);
                report.put("complete", true);
            } catch (Throwable failure) {
                try { report.put("failure", android.util.Log.getStackTraceString(failure)); } catch (Exception ignored) { }
            } finally {
                for (String gate : new String[]{"application-release", "Slow-release"}) try { mark(context, run, gate); }
                catch (Exception cleanup) { cleanupFailure(report, cleanup); }
                for (Connection value : owned) try { context.unbindService(value); } catch (Exception cleanup) { cleanupFailure(report, cleanup); }
                for (Intent value : starts) try { context.stopService(value); } catch (Exception cleanup) { cleanupFailure(report, cleanup); }
                try { Files.deleteIfExists(new File(context.getFilesDir(), "bound-prepare-run").toPath()); } catch (Exception cleanup) { cleanupFailure(report, cleanup); }
            }
            try {
                report.put("run", run); File result = file(context, run, "result.json");
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(result + ".tmp"))) { output.write(report.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
                if (!new File(result + ".tmp").renameTo(result)) throw new java.io.IOException("Cannot publish bound result");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }, "fixture-bound").start();
    }

    private static void cleanupFailure(JSONObject report, Exception failure) {
        try { report.put("cleanupFailure", report.optString("cleanupFailure") + android.util.Log.getStackTraceString(failure)); }
        catch (Exception reporting) { throw new IllegalStateException(reporting); }
    }

    public static void applicationBarrier(Context context) {
        if (!(context.getPackageName() + ":bound_gate").equals(context.getApplicationInfo().processName)) return;
        File request = new File(context.getFilesDir(), "bound-prepare-run"); if (!request.isFile()) return;
        try {
            String run = new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8); UUID.fromString(run);
            mark(context, run, "application-entered"); waitFile(file(context, run, "application-release")); mark(context, run, "application-returned");
        } catch (Exception failure) { throw new IllegalStateException("Actual Application barrier failed", failure); }
    }

    public abstract static class Worker extends Service {
        final String instance = UUID.randomUUID().toString(); int binds, rebinds; String run, first = ""; Intent boundIntent;
        IBinder callbackMarker; JSONObject bindIntent, unbindIntent, rebindIntent;
        private final Binder binder = new Binder() {
            { attachInterface(null, DESCRIPTOR); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
                if (code == FIRST_CALL_TRANSACTION + 1) {
                    data.enforceInterface(DESCRIPTOR); reply.writeNoException();
                    new android.os.Handler(getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 100); return true;
                }
                if (code != FIRST_CALL_TRANSACTION) return false;
                data.enforceInterface(DESCRIPTOR);
                try { JSONObject value = new JSONObject(); value.put("pid", Process.myPid()); value.put("instance", instance); value.put("binds", binds); value.put("rebinds", rebinds); value.put("first", first); value.put("data", boundIntent == null ? "" : boundIntent.getDataString()); value.put("type", boundIntent == null ? "" : boundIntent.getType()); value.put("flags", boundIntent == null ? 0 : boundIntent.getFlags()); value.put("category", boundIntent != null && boundIntent.hasCategory("fixture.bound.original")); value.put("bindIntent", bindIntent); value.put("unbindIntent", unbindIntent); value.put("rebindIntent", rebindIntent); reply.writeNoException(); reply.writeString(value.toString()); return true; }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        };
        private void event(String name) {
            if (run != null) { mark(this, run, getClass().getSimpleName() + "-" + name); mark(this, run, instance + "-" + name); }
        }
        @Override public void onCreate() {
            super.onCreate();
            try { run = new String(Files.readAllBytes(new File(getFilesDir(), "bound-prepare-run").toPath()), StandardCharsets.UTF_8); event("create"); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public int onStartCommand(Intent intent, int flags, int startId) { run = intent.getStringExtra("run"); event("create"); event("start"); return START_NOT_STICKY; }
        @Override public IBinder onBind(Intent intent) {
            boundIntent = new Intent(intent); run = intent.getStringExtra("run"); first = intent.getStringExtra("first"); if (first == null) first = ""; binds++; event("create");
            Bundle nested = intent.getBundleExtra("callback_nested"); callbackMarker = nested == null ? null : nested.getBinder("marker");
            bindIntent = observe(intent);
            if ("slow".equals(intent.getAction())) { event("entered"); try { waitFile(file(this, run, "Slow-release")); } catch (Exception failure) { throw new IllegalStateException(failure); } }
            boolean nullable = "null".equals(intent.getAction()); mutate(intent); return nullable ? null : binder;
        }
        @Override public boolean onUnbind(Intent intent) { boolean rebind = "rebind".equals(intent.getAction()); unbindIntent = observe(intent); mutate(intent); event("unbind"); return rebind; }
        @Override public void onRebind(Intent intent) { rebindIntent = observe(intent); mutate(intent); rebinds++; }
        private JSONObject observe(Intent intent) {
            try {
                Bundle nested = intent.getBundleExtra("callback_nested"); int[] numbers = intent.getIntArrayExtra("callback_numbers");
                JSONObject value = new JSONObject(); value.put("first", intent.getStringExtra("first")); value.put("action", intent.getAction());
                value.put("number", numbers == null ? -1 : numbers[0]); value.put("nested", nested == null ? -1 : nested.getInt("number"));
                value.put("binderSame", callbackMarker != null && nested != null && callbackMarker.equals(nested.getBinder("marker"))); return value;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        private void mutate(Intent intent) {
            if (!intent.getBooleanExtra("mutate_callback_intent", false)) return;
            intent.setAction("fixture.bound.mutated"); intent.putExtra("first", "mutated");
            int[] numbers = intent.getIntArrayExtra("callback_numbers"); if (numbers != null) numbers[0] = 99;
            Bundle nested = intent.getBundleExtra("callback_nested"); if (nested != null) { nested.putInt("number", 99); nested.remove("marker"); }
        }
        @Override public void onDestroy() { event("destroy"); super.onDestroy(); }
    }
    public static final class Gate extends Worker { }
    public static final class One extends Worker { }
    public static final class Two extends Worker { }
    public static final class Three extends Worker { }
    public static final class Four extends Worker { }
    public static final class Five extends Worker { }
    public static final class Slow extends Worker { }

    private static void requireCanonical(JSONObject value, String callback) throws Exception {
        require("kept".equals(value.getString("first")) && "rebind".equals(value.getString("action"))
                && value.getInt("number") == 7 && value.getInt("nested") == 11 && value.getBoolean("binderSame"), callback + " did not retain the first filter Intent: " + value);
    }
}
