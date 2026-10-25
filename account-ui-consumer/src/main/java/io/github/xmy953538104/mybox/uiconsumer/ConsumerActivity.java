package io.github.xmy953538104.mybox.uiconsumer;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;

public final class ConsumerActivity extends Activity {
    private static final String TYPE = "io.github.xmy953538104.mybox.ui.fixture";
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final Map<String, Intent> returned = new HashMap<>();
    private IBinder observer;
    private final Binder control = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != FIRST_CALL_TRANSACTION) return false;
            Bundle input = data.readBundle(getClass().getClassLoader()); main.post(() -> command(input));
            reply.writeNoException(); return true;
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); observer = getIntent().getExtras().getBinder("observer");
        TextView title = new TextView(this); title.setText("Independent account UI consumer"); setContentView(title);
        publish("consumer", "ready", null);
    }
    @Override public void onResume() { super.onResume(); if (observer != null) publish("consumer-resumed", "ready", null); }
    @Override public void onDestroy() { publish("consumer-destroyed", "ready", null); super.onDestroy(); }

    private void command(Bundle input) {
        String tag = input.getString("tag");
        try {
            switch (input.getString("action", "")) {
                case "request": {
                    Bundle options = new Bundle(); options.putString("tag", tag); options.putBinder("observer", observer);
                    options.putBoolean("hold", input.getBoolean("hold")); options.putString("mode", input.getString("mode"));
                    AccountManager.get(this).addAccount(TYPE, null, null, options,
                            input.getBoolean("interactive") ? this : null, future -> {
                        try {
                            Bundle result = future.getResult(); Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
                            if (intent != null) returned.put(tag, intent);
                            publish(intent == null ? "future-result" : "returned", tag, result);
                        } catch (Exception failure) { error(tag, failure); }
                    }, main);
                    break;
                }
                case "launch": case "result": {
                    Intent intent = input.getParcelable("intent");
                    if (intent == null) intent = returned.get(input.getString("from", tag));
                    if (intent == null) throw new IllegalArgumentException("No returned UI Intent");
                    if (input.getString("action").equals("result")) startActivityForResult(intent, 73); else startActivity(intent);
                    publish("command", tag, null); break;
                }
                case "accounts": {
                    Bundle result = new Bundle();
                    Account[] accounts = AccountManager.get(this).getAccountsByType(TYPE);
                    String[] names = new String[accounts.length]; for (int i = 0; i < names.length; i++) names[i] = accounts[i].name;
                    result.putStringArray("names", names); publish("accounts", tag, result); break;
                }
                case "hidden-access": hiddenAccess(tag); break;
                case "app-task-denied": rejectForeignAppTask(input, tag); break;
                case "finish": finish(); break;
                default: throw new IllegalArgumentException("Unknown fixture command");
            }
        } catch (Throwable failure) { error(tag, failure); }
    }
    private void rejectForeignAppTask(Bundle command, String tag) throws Exception {
        IBinder task = command.getBinder("app_task");
        Class<?> contract = Class.forName("android.app.IAppTask"), stub = Class.forName("android.app.IAppTask$Stub");
        Object remote = stub.getMethod("asInterface", IBinder.class).invoke(null, task);
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        java.util.ArrayList<android.app.ActivityManager.RecentTaskInfo> infos = new java.util.ArrayList<>();
        for (android.app.ActivityManager.AppTask own : manager.getAppTasks()) infos.add(own.getTaskInfo());
        Bundle result = new Bundle(); result.putParcelableArrayList("infos", infos);
        String[] names = {"getTaskInfo", "finishAndRemoveTask", "setExcludeFromRecents", "moveToFront", "startActivity"};
        Object framework = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
        java.lang.reflect.Method getThread = framework.getClass().getDeclaredMethod("getApplicationThread"); getThread.setAccessible(true);
        Object thread = getThread.invoke(framework); IBinder threadBinder = ((android.os.IInterface) thread).asBinder();
        for (String name : names) {
            java.lang.reflect.Method method; Object[] args;
            if (name.equals("setExcludeFromRecents")) { method = contract.getMethod(name, boolean.class); args = new Object[]{true}; }
            else if (name.equals("moveToFront") && android.os.Build.VERSION.SDK_INT >= 29) {
                method = contract.getMethod(name, Class.forName("android.app.IApplicationThread"), String.class); args = new Object[]{thread, getPackageName()};
            } else if (name.equals("startActivity")) {
                Intent target = new Intent(this, ConsumerActivity.class);
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    method = contract.getMethod(name, IBinder.class, String.class, String.class, Intent.class, String.class, Bundle.class);
                    args = new Object[]{threadBinder, getPackageName(), null, target, null, null};
                } else {
                    method = contract.getMethod(name, IBinder.class, String.class, Intent.class, String.class, Bundle.class);
                    args = new Object[]{threadBinder, getPackageName(), target, null, null};
                }
            } else { method = contract.getMethod(name); args = new Object[0]; }
            try { method.invoke(remote, args); throw new AssertionError("Foreign AppTask method succeeded: " + name); }
            catch (java.lang.reflect.InvocationTargetException denied) {
                if (!(denied.getCause() instanceof SecurityException)) throw denied;
                result.putBoolean(name, true);
            }
        }
        publish("app-task-denied", tag, result);
    }
    private void hiddenAccess(String tag) {
        AccountManager manager = AccountManager.get(this);
        Account account = new Account(tag, TYPE);
        Bundle result = new Bundle();
        boolean absent = true;
        for (Account visible : manager.getAccountsByType(TYPE)) if (account.equals(visible)) absent = false;
        result.putBoolean("notEnumerated", absent);
        result.putBoolean("stillHidden", manager.getAccountVisibility(account, getPackageName()) == AccountManager.VISIBILITY_NOT_VISIBLE);
        try { manager.getPassword(account); result.putBoolean("passwordDenied", false); }
        catch (SecurityException denied) { result.putBoolean("passwordDenied", true); }
        try { manager.getUserData(account, "field"); result.putBoolean("userDataDenied", false); }
        catch (SecurityException denied) { result.putBoolean("userDataDenied", true); }
        try { manager.peekAuthToken(account, "fixture"); result.putBoolean("rawTokenDenied", false); }
        catch (SecurityException denied) { result.putBoolean("rawTokenDenied", true); }
        try {
            manager.getAuthToken(account, "fixture", null, false, future -> {
                try { future.getResult(); result.putBoolean("tokenDenied", false); }
                catch (SecurityException denied) { result.putBoolean("tokenDenied", true); }
                catch (Exception failure) { error(tag, failure); return; }
                publish("hidden-access", tag, result);
            }, main);
        } catch (SecurityException denied) {
            result.putBoolean("tokenDenied", true); publish("hidden-access", tag, result);
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Bundle value = new Bundle(); value.putInt("request", request); value.putInt("result", result); value.putParcelable("data", data);
        publish("activity-result", data == null ? "absent" : data.getStringExtra("tag"), value);
    }
    private void error(String tag, Throwable failure) {
        Bundle value = new Bundle(); value.putString("failure", failure.toString()); publish("error", tag, value);
    }
    private void publish(String event, String tag, Bundle values) {
        Bundle value = values == null ? new Bundle() : new Bundle(values);
        value.putString("event", event); value.putString("tag", tag); value.putString("package", getPackageName());
        value.putInt("pid", android.os.Process.myPid()); value.putBinder("control", control);
        Parcel input = Parcel.obtain(), reply = Parcel.obtain();
        try { input.writeBundle(value); observer.transact(IBinder.FIRST_CALL_TRANSACTION, input, reply, 0); reply.readException(); }
        catch (android.os.RemoteException failure) { throw new IllegalStateException(failure); }
        finally { input.recycle(); reply.recycle(); }
    }
}
