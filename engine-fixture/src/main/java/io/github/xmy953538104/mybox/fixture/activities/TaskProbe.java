package io.github.xmy953538104.mybox.fixture.activities;

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

/** An ordinary APK's Activities. Commands always go through their public Android Activity APIs. */
public final class TaskProbe {
    public static class Root extends Base { }
    public static class OtherRoot extends Base { }
    public static class Single extends Base { }
    public static class Top extends Base { }
    public static class Standard extends Base { }
    public static class Child extends Base { }
    public static class Remote extends Base { }

    public static class Base extends Activity {
        private final String instance = UUID.randomUUID().toString();
        private IBinder observer;
        private String run;
        private final Handler main = new Handler(android.os.Looper.getMainLooper());
        private final Binder control = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
                Bundle command = data.readBundle(TaskProbe.class.getClassLoader());
                main.post(() -> command(command));
                reply.writeNoException(); return true;
            }
        };

        @Override protected void onCreate(Bundle saved) {
            super.onCreate(saved);
            observer = getIntent().getExtras().getBinder("observer");
            run = getIntent().getStringExtra("run");
            TextView title = new TextView(this);
            title.setText(getClass().getSimpleName() + " " + instance);
            setContentView(title);
            publish("create", getIntent(), null);
        }
        @Override protected void onResume() { super.onResume(); publish("resume", null, null); }
        @Override protected void onPause() { publish("pause", null, null); super.onPause(); }
        @Override protected void onDestroy() { publish("destroy", null, null); super.onDestroy(); }
        @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); publish("intent", intent, null); }

        private void command(Bundle command) {
            try {
                switch (command.getString("action", "")) {
                    case "start": startActivity(command.getParcelable("intent")); break;
                    case "result": startActivityForResult(command.getParcelable("intent"), 73); break;
                    case "finish": finish(); break;
                    case "recreate": recreate(); break;
                    case "back": moveTaskToBack(true); break;
                    case "private":
                        Bundle connection = getContentResolver().call(Uri.parse("content://" +
                                command.getString("host") + ".engine.server"), "connect", null, new Bundle());
                        IBinder server = connection.getBinder("server");
                        Parcel input = Parcel.obtain(), output = Parcel.obtain();
                        try {
                            input.writeInterfaceToken("io.github.xmy953538104.mybox.engine.Server.v1");
                            input.writeBundle(command.getBundle("request"));
                            if (!server.transact(IBinder.FIRST_CALL_TRANSACTION + 12, input, output, 0)) {
                                throw new AssertionError("Activity protocol endpoint is absent");
                            }
                            output.readException();
                            String error = output.readBundle(TaskProbe.class.getClassLoader()).getString("error");
                            if (error != null) throw new IllegalStateException(error);
                        } finally { input.recycle(); output.recycle(); }
                        break;
                    default: throw new AssertionError("Unknown fixture command");
                }
                publish("command", command.getParcelable("intent"), command.getString("tag"));
            } catch (Throwable failure) {
                publish("error", null, command.getString("tag") + ": " + failure);
            }
        }

        private void publish(String event, Intent delivered, String detail) {
            if (observer == null) return;
            Bundle state = new Bundle();
            state.putString("event", event); state.putString("class", getClass().getName());
            state.putString("instance", instance); state.putString("run", run);
            state.putInt("task", getTaskId()); state.putInt("pid", android.os.Process.myPid());
            state.putInt("initial_sequence", getIntent().getIntExtra("sequence", -1));
            state.putBoolean("finishing", isFinishing());
            state.putBinder("control", control);
            try { state.putBinder("token", ActivityProbe.token(this)); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            if (delivered != null) {
                state.putParcelable("intent", new Intent(delivered));
                state.putInt("sequence", delivered.getIntExtra("sequence", -1));
            }
            if (detail != null) state.putString("detail", detail);
            Parcel input = Parcel.obtain(), output = Parcel.obtain();
            try {
                input.writeBundle(state);
                if (!observer.transact(IBinder.FIRST_CALL_TRANSACTION, input, output, 0)) {
                    throw new AssertionError("Fixture observer is unavailable");
                }
                output.readException();
            } catch (android.os.RemoteException ignored) {
                // Instrumentation teardown may remove its observer before the OS destroys the task.
            } finally { input.recycle(); output.recycle(); }
        }
    }

    private TaskProbe() { }
}
