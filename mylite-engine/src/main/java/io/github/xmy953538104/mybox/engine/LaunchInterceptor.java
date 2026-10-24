package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;

/** Replaces an authenticated stub launch before ActivityThread constructs the Activity. */
final class LaunchInterceptor implements Handler.Callback {
    private final GuestRuntime runtime;
    private final Handler.Callback previous;

    private LaunchInterceptor(GuestRuntime runtime, Handler.Callback previous) {
        this.runtime = runtime;
        this.previous = previous;
    }

    static void install(Object thread, GuestRuntime runtime) throws Exception {
        Handler handler = (Handler) RuntimeReflection.get(thread, "mH");
        Handler.Callback previous = (Handler.Callback) RuntimeReflection.get(handler, "mCallback");
        RuntimeReflection.set(handler, "mCallback", new LaunchInterceptor(runtime, previous));
    }

    @Override public boolean handleMessage(Message message) {
        List<Runnable> newIntents = new ArrayList<>();
        try {
            if (message.obj != null) {
                String type = message.obj.getClass().getName();
                if (type.equals("android.app.servertransaction.ClientTransaction")) {
                    IBinder token = (IBinder) RuntimeReflection.invoke(message.obj,
                            RuntimeReflection.method(message.obj.getClass(), "getActivityToken"));
                    runtime.activities().beforeTransaction(message.obj, token);
                    Method callbacks = RuntimeReflection.method(message.obj.getClass(), "getCallbacks");
                    List<?> items = (List<?>) RuntimeReflection.invoke(message.obj, callbacks);
                    if (items != null) {
                        for (Object item : items) {
                            if (item.getClass().getName().equals("android.app.servertransaction.LaunchActivityItem")) {
                                rewrite(item, "mIntent", "mInfo", false, token);
                            } else if (item.getClass().getName().equals("android.app.servertransaction.NewIntentItem")) {
                                rewriteNewIntents(item, "mIntents", token, newIntents);
                            }
                        }
                    }
                } else if (type.equals("android.app.ActivityThread$ActivityClientRecord")) {
                    rewrite(message.obj, "intent", "activityInfo", true,
                            (IBinder) RuntimeReflection.get(message.obj, "token"));
                } else if (type.equals("android.app.ActivityThread$NewIntentData")) {
                    IBinder token = (IBinder) RuntimeReflection.get(message.obj, "token");
                    rewriteNewIntents(message.obj, "intents", token, newIntents);
                }
            }
        } catch (Exception error) {
            throw new EngineException("Android Activity launch adaptation failed", error);
        }
        boolean consumed = previous != null && previous.handleMessage(message);
        if (!consumed) for (Runnable acknowledgement : newIntents) {
            // The framework handles this message before the queued acknowledgement can run.
            runtime.post(acknowledgement);
        }
        return consumed;
    }

    private void rewrite(Object record, String intentField, String infoField, boolean legacy, IBinder token) throws Exception {
        Intent stub = (Intent) RuntimeReflection.get(record, intentField);
        if (stub == null || stub.getComponent() == null
                || !stub.getComponent().getClassName().startsWith(GuestActivity.class.getName() + "$P")) return;
        Bundle request = runtime.activities().claim(stub, token);
        ActivityInfo target = runtime.activity(request);
        Intent guest = runtime.activities().restore(request);
        RuntimeReflection.set(record, intentField, guest);
        RuntimeReflection.set(record, infoField, target);
        RuntimeReflection.set(record, legacy ? "referrer" : "mReferrer", request.getString("referrer"));
        if (legacy) RuntimeReflection.set(record, "packageInfo", runtime.loadedApk());
        else if (android.os.Build.VERSION.SDK_INT == 31 || android.os.Build.VERSION.SDK_INT == 32) {
            // Android 12 constructs the record in preExecute, before the Handler callback.
            Object thread = RuntimeReflection.activityThread();
            Object launching = RuntimeReflection.invoke(thread,
                    RuntimeReflection.method(thread.getClass(), "getLaunchingActivity", IBinder.class), token);
            if (launching == null || !token.equals(RuntimeReflection.get(launching, "token"))) {
                throw new SecurityException("Android's pre-created Activity record has no matching token");
            }
            Intent cached = (Intent) RuntimeReflection.get(launching, "intent");
            if (cached == null || !stub.filterEquals(cached)) {
                throw new SecurityException("Android's pre-created Activity filter differs from its envelope");
            }
            RuntimeReflection.set(launching, "intent", guest);
            RuntimeReflection.set(launching, "activityInfo", target);
            RuntimeReflection.set(launching, "packageInfo", runtime.loadedApk());
            RuntimeReflection.set(launching, "referrer", request.getString("referrer"));
        }
    }

    private void rewriteNewIntents(Object item, String field, IBinder token, List<Runnable> acknowledgements) throws Exception {
        List<?> incoming = (List<?>) RuntimeReflection.get(item, field);
        if (incoming == null) return;
        List<Intent> restored = new ArrayList<>();
        for (Object itemIntent : incoming) {
            Intent intent = (Intent) itemIntent;
            Bundle operation = intent.getBundleExtra(EngineProtocol.LAUNCH);
            restored.add(runtime.activities().unwrapNewIntent(token, intent));
            acknowledgements.add(() -> runtime.activities().newIntentDelivered(token, operation));
        }
        RuntimeReflection.set(item, field, restored);
    }
}
