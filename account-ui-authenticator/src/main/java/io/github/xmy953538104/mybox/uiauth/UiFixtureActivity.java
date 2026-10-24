package io.github.xmy953538104.mybox.uiauth;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.widget.TextView;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** An actual Activity and Binder control, with bounded main-thread holds for protocol races. */
abstract class UiFixtureActivity extends Activity {
    final Handler main = new Handler(android.os.Looper.getMainLooper());
    final String instance = UUID.randomUUID().toString();
    IBinder observer;
    private volatile CountDownLatch held;
    private final Binder control = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != FIRST_CALL_TRANSACTION) return false;
            Bundle command = data.readBundle(getClass().getClassLoader());
            String action = command.getString("action");
            if ("release-main".equals(action)) {
                CountDownLatch latch = held; if (latch != null) latch.countDown();
            } else if ("private".equals(action)) {
                // This Binder thread must not need a held Activity main thread to test a forged ACK.
                try { protocol(command); publish("ui-command", command.getString("tag"), null); }
                catch (Throwable failure) { error(command.getString("tag"), failure); }
            } else main.post(() -> {
                try {
                    switch (action == null ? "" : action) {
                        case "launch": startActivity(command.getParcelable("intent")); break;
                        case "finish": finish(); break;
                        case "recreate": recreate(); break;
                        case "complete": complete(); break;
                        case "hold-main":
                            held = new CountDownLatch(1); publish("ui-held", command.getString("tag"), null);
                            if (!held.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture main-thread hold expired");
                            held = null; break;
                        default: throw new IllegalArgumentException("Unknown UI fixture command");
                    }
                    publish("ui-command", command.getString("tag"), null);
                } catch (Throwable failure) { error(command.getString("tag"), failure); }
            });
            reply.writeNoException(); return true;
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); observer = getIntent().getExtras().getBinder("observer");
        TextView title = new TextView(this); title.setText(getClass().getSimpleName()); setContentView(title);
    }
    @Override public void onDestroy() { publish("ui-destroyed", getIntent().getStringExtra("tag"), null); super.onDestroy(); }
    void complete() { throw new IllegalStateException("This Activity has no account response"); }

    void publish(String event, String tag, Bundle extra) {
        if (observer == null) return;
        Bundle value = extra == null ? new Bundle() : new Bundle(extra);
        value.putString("event", event); value.putString("tag", tag); value.putString("instance", instance);
        value.putString("package", getPackageName()); value.putString("class", getClass().getName());
        value.putInt("pid", android.os.Process.myPid()); value.putInt("task", getTaskId());
        value.putString("initial_tag", getIntent().getStringExtra("tag")); value.putBinder("control", control);
        try {
            java.lang.reflect.Field token = Activity.class.getDeclaredField("mToken"); token.setAccessible(true);
            value.putBinder("token", (IBinder) token.get(this));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        Parcel input = Parcel.obtain(), reply = Parcel.obtain();
        try { input.writeBundle(value); observer.transact(IBinder.FIRST_CALL_TRANSACTION, input, reply, 0); reply.readException(); }
        catch (android.os.RemoteException ignored) { /* The observer can disappear during task teardown. */ }
        finally { input.recycle(); reply.recycle(); }
    }
    private void error(String tag, Throwable failure) {
        Bundle value = new Bundle(); value.putString("failure", failure.toString()); publish("ui-error", tag, value);
    }
    private void protocol(Bundle command) throws Exception {
        Bundle connection = getContentResolver().call(Uri.parse("content://" + command.getString("host")
                + ".engine.server"), "connect", null, new Bundle());
        IBinder server = connection.getBinder("server"); Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken("io.github.xmy953538104.mybox.engine.Server.v1"); input.writeBundle(command.getBundle("request"));
            if (!server.transact(IBinder.FIRST_CALL_TRANSACTION + 12, input, output, 0)) throw new IllegalStateException("Missing Activity endpoint");
            output.readException(); Bundle result = output.readBundle(getClass().getClassLoader());
            if (result.getString("error") != null) throw new SecurityException(result.getString("error"));
        } finally { input.recycle(); output.recycle(); }
    }
}
