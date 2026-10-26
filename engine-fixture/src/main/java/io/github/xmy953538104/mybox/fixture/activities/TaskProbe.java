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
    public static class Isolated extends Base { }

    public static class Base extends Activity {
        private final String instance = UUID.randomUUID().toString();
        private IBinder observer;
        private String run;
        private android.app.ActivityManager.AppTask appTask;
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
            observer = getIntent().getExtras().getBinder("observer");
            run = getIntent().getStringExtra("run");
            if (getIntent().getBooleanExtra("app_task_busy_probe", false)) {
                // super.onCreate dispatches CREATED; probe the pending transition before that ACK.
                Bundle command = new Bundle(); command.putString("action", "app-task");
                command.putString("task_operation", "list"); command.putString("tag", "info-on-create");
                command(command);
                command.putString("task_operation", "exclude"); command.putString("tag", "busy-on-create");
                command(command);
            }
            super.onCreate(saved);
            TextView title = new TextView(this);
            title.setText(getClass().getSimpleName() + " " + instance);
            if (getIntent().getBooleanExtra("deferred_gate", false)) {
                android.widget.Button close = new android.widget.Button(this);
                close.setText("Continue deferred launch " + getClass().getSimpleName());
                close.setOnClickListener(view -> finish());
                setContentView(close);
            } else setContentView(title);
            publish("create", getIntent(), null);
            if (getIntent().getBooleanExtra("app_task_busy_probe", false)) {
                Bundle command = new Bundle(); command.putString("action", "app-task");
                command.putString("task_operation", "exclude"); command.putString("tag", "exclude-after-created");
                command.putBoolean("exclude", true); command(command);
            }
            if (getIntent().getBooleanExtra("stock_result_probe", false)) {
                main.post(() -> startActivityForResult(new Intent(this,
                        getIntent().getBooleanExtra("stock_result_single_task", false) ? Single.class : Child.class)
                        .putExtra("stock_result_child", true), 73));
            }
            if (getIntent().getBooleanExtra("stock_result_child", false)) {
                android.util.Log.i("MyboxStockTasks", "child task=" + getTaskId() + " caller=" + getCallingPackage() + " flags=0x" + Integer.toHexString(getIntent().getFlags()));
            }
        }
        @Override protected void onActivityResult(int request, int result, Intent data) {
            super.onActivityResult(request, result, data);
            Intent delivered = data == null ? new Intent() : new Intent(data);
            publish("result", delivered.putExtra("result_request", request).putExtra("result_code", result), null);
            if (getIntent().getBooleanExtra("stock_result_probe", false)) {
                android.util.Log.i("MyboxStockTasks", "result request=" + request + " code=" + result + " task=" + getTaskId());
            }
        }
        @Override protected void onResume() { super.onResume(); publish("resume", null, null); }
        @Override protected void onPause() { publish("pause", null, null); super.onPause(); }
        @Override protected void onDestroy() { publish("destroy", null, null); super.onDestroy(); }
        @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); publish("intent", intent, null); }

        private void command(Bundle command) {
            try {
                switch (command.getString("action", "")) {
                    case "retire-server": {
                        publish("retire-server", null, null);
                        moveTaskToBack(true);
                        break;
                    }
                    case "deferred":
                        startActivity(command.getParcelable("intent"));
                        publish("deferred-submitted", null, null);
                        startActivity(command.getParcelable("direct"));
                        startActivity(command.getParcelable("gate"));
                        finish();
                        break;
                    case "start": startActivity(command.getParcelable("intent")); break;
                    case "result": startActivityForResult(command.getParcelable("intent"), 73); break;
                    case "finish": finish(); break;
                    case "finish_ok": setResult(RESULT_OK, new Intent().putExtra("probe_result", 42)); finish(); break;
                    case "recreate": recreate(); break;
                    case "back": moveTaskToBack(true); break;
                    case "app-task": {
                        Bundle result = appTask(command);
                        publish("app-task", null, command.getString("tag"), result); break;
                    }
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

        private Bundle appTask(Bundle command) throws Exception {
            android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            IBinder supplied = command.getBinder("app_task");
            if (supplied != null) {
                Class<?> contract = Class.forName("android.app.IAppTask");
                Object remote = Class.forName("android.app.IAppTask$Stub").getMethod("asInterface", IBinder.class).invoke(null, supplied);
                java.lang.reflect.Constructor<?> constructor = android.app.ActivityManager.AppTask.class.getDeclaredConstructor(contract);
                constructor.setAccessible(true); appTask = (android.app.ActivityManager.AppTask) constructor.newInstance(remote);
            }
            Bundle result = new Bundle(); String operation = command.getString("task_operation", "info");
            if (operation.equals("list") || appTask == null) {
                java.util.ArrayList<android.app.ActivityManager.RecentTaskInfo> infos = new java.util.ArrayList<>();
                for (android.app.ActivityManager.AppTask task : manager.getAppTasks()) {
                    android.app.ActivityManager.RecentTaskInfo info = task.getTaskInfo(); infos.add(info);
                    if (info.persistentId == getTaskId()) appTask = task;
                }
                result.putParcelableArrayList("infos", infos);
            }
            if (appTask == null) throw new AssertionError("Current guest task is absent from the real AppTasks collection");
            java.lang.reflect.Field field = android.app.ActivityManager.AppTask.class.getDeclaredField("mAppTaskImpl");
            field.setAccessible(true); Object endpoint = field.get(appTask);
            switch (operation) {
                case "list": case "info": result.putParcelable("info", appTask.getTaskInfo()); break;
                case "move": appTask.moveToFront(); break;
                case "exclude": appTask.setExcludeFromRecents(command.getBoolean("exclude")); result.putParcelable("info", appTask.getTaskInfo()); break;
                case "start": appTask.startActivity(getApplicationContext(), command.getParcelable("intent"), command.getBundle("options")); break;
                case "finish": {
                    appTask.finishAndRemoveTask();
                    Intent after = command.getParcelable("after_finish_intent");
                    if (after != null) {
                        try { startActivity(after); throw new AssertionError("Removed task accepted an immediate ordinary Activity start"); }
                        catch (RuntimeException denied) {
                            if (denied.getMessage() == null || !denied.getMessage().contains("Activity source task was removed")) throw denied;
                            result.putBoolean("after_finish_denied", true);
                        }
                    }
                    break;
                }
                case "forged-thread": case "forged-package": {
                    Object framework = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
                    java.lang.reflect.Method getThread = framework.getClass().getDeclaredMethod("getApplicationThread"); getThread.setAccessible(true);
                    IBinder thread = ((android.os.IInterface) getThread.invoke(framework)).asBinder();
                    String pkg = getPackageName(); if (operation.equals("forged-thread")) thread = new Binder(); else pkg = "unrelated.physical.package";
                    Class<?> contract = Class.forName("android.app.IAppTask"); java.lang.reflect.Method method; Object[] args;
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        method = contract.getMethod("startActivity", IBinder.class, String.class, String.class, Intent.class, String.class, Bundle.class);
                        args = new Object[]{thread, pkg, null, new Intent(this, Child.class), null, null};
                    } else {
                        method = contract.getMethod("startActivity", IBinder.class, String.class, Intent.class, String.class, Bundle.class);
                        args = new Object[]{thread, pkg, new Intent(this, Child.class), null, null};
                    }
                    try { method.invoke(endpoint, args); throw new AssertionError("Forged AppTask caller was accepted"); }
                    catch (java.lang.reflect.InvocationTargetException denied) {
                        if (!(denied.getCause() instanceof SecurityException)) throw denied;
                        result.putBoolean("denied", true);
                    }
                    break;
                }
                default: throw new IllegalArgumentException("Unknown AppTask fixture operation");
            }
            result.putBinder("app_task", ((android.os.IInterface) endpoint).asBinder());
            return result;
        }

        private void publish(String event, Intent delivered, String detail) {
            publish(event, delivered, detail, null);
        }

        private void publish(String event, Intent delivered, String detail, Bundle values) {
            if (observer == null) return;
            Bundle state = values == null ? new Bundle() : new Bundle(values);
            state.putString("event", event); state.putString("class", getClass().getName());
            state.putString("instance", instance); state.putString("run", run);
            state.putInt("task", getTaskId()); state.putInt("pid", android.os.Process.myPid());
            state.putInt("initial_sequence", getIntent().getIntExtra("sequence", -1));
            state.putBoolean("finishing", isFinishing());
            state.putString("calling_package", getCallingPackage());
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
