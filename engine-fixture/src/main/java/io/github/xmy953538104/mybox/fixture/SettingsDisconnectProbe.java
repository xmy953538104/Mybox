package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.Settings;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** Controlled transport errors in this disposable guest; no system settings are changed. */
public final class SettingsDisconnectProbe {
    public static void run(Activity activity, String value) {
        String run = java.util.UUID.fromString(value).toString();
        activity.getWindow().getDecorView().post(() -> {
            JSONObject report = new JSONObject(); Object runtime = null; Field bound = null; IBinder original = null;
            try {
                Class<?> engine = Class.forName("io.github.xmy953538104.mybox.engine.Engine");
                Object instance = engine.getMethod("get").invoke(null);
                Method runtimeMethod = engine.getDeclaredMethod("runtime"); runtimeMethod.setAccessible(true);
                runtime = runtimeMethod.invoke(instance); bound = runtime.getClass().getDeclaredField("boundServer"); bound.setAccessible(true);
                original = (IBinder) bound.get(runtime); require(original != null && original.isBinderAlive(), "No actual bound server");
                Class<?> protocol = Class.forName("io.github.xmy953538104.mybox.engine.EngineProtocol");
                Field descriptor = protocol.getDeclaredField("SERVER"), operation = protocol.getDeclaredField("SETTINGS");
                descriptor.setAccessible(true); operation.setAccessible(true);
                String server = (String) descriptor.get(null); int settings = operation.getInt(null);
                String key = "mylite_disconnect_" + run;
                // Exercise the installed Settings provider adapter before replacing only the test guest's bound handle.
                require(Settings.Global.getString(activity.getContentResolver(), key) == null, "Fresh key was present");
                IBinder dead = endpoint("dead", server, settings);
                Method request = protocol.getDeclaredMethod("request", IBinder.class, String.class, int.class, Bundle.class); request.setAccessible(true);
                try { request.invoke(null, dead, server, settings, new Bundle()); throw new AssertionError("Foreign dead endpoint succeeded"); }
                catch (InvocationTargetException expected) { require(expected.getCause().getCause() instanceof DeadObjectException, "Foreign endpoint error changed"); }
                report.put("foreignEndpointPreserved", true);
                for (String mode : new String[]{"security", "transport", "protocol", "unsupported"}) {
                    IBinder controlled = endpoint(mode, server, settings); bound.set(runtime, controlled);
                    try { Settings.Global.getString(activity.getContentResolver(), key + mode); throw new AssertionError("Live error succeeded: " + mode); }
                    catch (RuntimeException expected) {
                        if (mode.equals("security")) require(expected instanceof SecurityException, "Security error changed");
                        else {
                            require(expected.getClass().getName().equals("io.github.xmy953538104.mybox.engine.EngineException"), "IPC error changed");
                            if (mode.equals("transport")) require(expected.getCause() instanceof RemoteException
                                    && !(expected.getCause() instanceof DeadObjectException), "Ordinary transport error changed");
                            else require(expected.getCause() == null, "Protocol error became transport death");
                        }
                    } finally { bound.set(runtime, original); }
                    require(original.isBinderAlive(), "Live error lost the actual server"); report.put(mode + "Preserved", true);
                }
                require(dead.isBinderAlive(), "Controlled case must expose stale-live liveness");
                bound.set(runtime, dead);
                report.put("run", run).put("pid", android.os.Process.myPid()).put("armed", true).put("cachedAlive", true);
                write(activity, run, report);
                Settings.Global.getString(activity.getContentResolver(), key + "dead");
                throw new AssertionError("Dead bound Settings transaction returned");
            } catch (Throwable failure) {
                try { report.put("failure", failure.getClass().getName()); write(activity, run, report); }
                catch (Exception export) { throw new IllegalStateException(export); }
            } finally {
                if (bound != null && runtime != null && original != null) try { bound.set(runtime, original); }
                catch (Exception restore) { throw new IllegalStateException(restore); }
            }
        });
    }

    private static IBinder endpoint(String mode, String descriptor, int settings) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                require(code == settings, "Unexpected test operation"); data.enforceInterface(descriptor); data.readBundle(getClass().getClassLoader());
                if (mode.equals("security")) throw new SecurityException("Controlled live rejection");
                if (mode.equals("transport")) throw new RemoteException("Controlled live transport failure");
                if (mode.equals("dead")) throw new DeadObjectException();
                if (mode.equals("unsupported")) return false;
                reply.writeNoException(); reply.writeBundle(new Bundle()); return true;
            }
        };
    }
    private static void write(Activity activity, String run, JSONObject report) throws Exception {
        File target = new File(activity.getFilesDir(), "settings-disconnect-" + run + ".json"), pending = new File(target.getPath() + ".tmp");
        Files.write(pending.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private SettingsDisconnectProbe() { }
}
