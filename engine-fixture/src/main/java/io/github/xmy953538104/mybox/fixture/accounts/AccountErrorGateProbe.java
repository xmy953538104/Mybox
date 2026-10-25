package io.github.xmy953538104.mybox.fixture.accounts;

import android.accounts.AccountManager;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Two actual synchronous consumer errors, gated while the same engine server must connect a Job. */
public final class AccountErrorGateProbe {
    public static final String CONTROL = "account-error-null-run";
    public static void run(Context context, String run) {
        if (!run.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid account error run");
        new Thread(() -> {
            try {
                Object manager = AccountManager.get(context);
                java.lang.reflect.Field service = AccountManager.class.getDeclaredField("mService"); service.setAccessible(true);
                Object endpoint = service.get(manager);
                Object handler = java.lang.reflect.Proxy.getInvocationHandler(endpoint);
                java.lang.reflect.Field server = handler.getClass().getDeclaredField("serverPid"); server.setAccessible(true);
                int serverPid = server.getInt(handler);
                Class<?> responseType = Class.forName("android.accounts.IAccountManagerResponse");
                java.lang.reflect.Method label = Class.forName("android.accounts.IAccountManager").getMethod("getAuthTokenLabel", responseType, String.class, String.class);
                for (int index = 0; index < 2; index++) {
                    int slot = index;
                    java.util.concurrent.atomic.AtomicInteger invocations = new java.util.concurrent.atomic.AtomicInteger();
                    Binder callback = new Binder() {
                        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                            data.enforceInterface("android.accounts.IAccountManagerResponse");
                            JSONObject event = event(run);
                            try {
                                if (code != 2 || (flags & IBinder.FLAG_ONEWAY) != 0) throw new AssertionError("Expected real synchronous error callback");
                                event.put("errorCode", data.readInt()); data.readString(); event.put("serverPid", serverPid);
                                event.put("invocations", invocations.incrementAndGet());
                                publish(context, name(run, "entered-" + slot), event);
                                long end = SystemClock.elapsedRealtime() + 20000;
                                File release = new File(context.getFilesDir(), name(run, "release"));
                                while (!release.isFile() && SystemClock.elapsedRealtime() < end) Thread.sleep(10);
                                if (!release.isFile()) throw new java.util.concurrent.TimeoutException("Account error consumer gate expired");
                            } catch (Throwable failure) { putFailure(event, failure); }
                            publish(context, name(run, "returned-" + slot), event);
                            if (reply != null) reply.writeNoException(); return true;
                        }
                    };
                    callback.attachInterface(null, "android.accounts.IAccountManagerResponse");
                    Object response = java.lang.reflect.Proxy.newProxyInstance(responseType.getClassLoader(), new Class<?>[]{responseType},
                            (proxy, method, arguments) -> {
                                if (method.getName().equals("asBinder")) return callback;
                                throw new UnsupportedOperationException("Only the actual Binder response is used");
                            });
                    label.invoke(endpoint, response, FixtureAuthenticatorService.TYPE, "error-gate");
                }
            } catch (Throwable failure) { JSONObject event = event(run); putFailure(event, failure); publish(context, name(run, "failed"), event); }
        }, "actual-account-error-gate").start();
    }
    static void nullBinding(Context context, String run) { publish(context, name(run, "null-bound"), event(run)); }
    private static String name(String run, String phase) { return "account-error-" + run + "-" + phase + ".json"; }
    private static JSONObject event(String run) {
        JSONObject result = new JSONObject();
        try { result.put("run", run); result.put("pid", android.os.Process.myPid()); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        return result;
    }
    private static void putFailure(JSONObject event, Throwable failure) { try { event.put("failure", failure.toString()); } catch (Exception ignored) { } }
    private static synchronized void publish(Context context, String name, JSONObject event) {
        try {
            File pending = new File(context.getFilesDir(), name + ".tmp");
            try (FileOutputStream output = new FileOutputStream(pending)) { output.write(event.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
            if (!pending.renameTo(new File(context.getFilesDir(), name))) throw new java.io.IOException("Cannot publish account error gate receipt");
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private AccountErrorGateProbe() { }
}
