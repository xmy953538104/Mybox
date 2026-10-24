package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import java.lang.reflect.Method;
import java.util.List;

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
        try {
            if (message.obj != null) {
                String type = message.obj.getClass().getName();
                if (type.equals("android.app.servertransaction.ClientTransaction")) {
                    Method callbacks = RuntimeReflection.method(message.obj.getClass(), "getCallbacks");
                    List<?> items = (List<?>) RuntimeReflection.invoke(message.obj, callbacks);
                    if (items != null) {
                        for (Object item : items) {
                            if (item.getClass().getName().equals("android.app.servertransaction.LaunchActivityItem")) {
                                rewrite(item, "mIntent", "mInfo", false);
                            }
                        }
                    }
                } else if (type.equals("android.app.ActivityThread$ActivityClientRecord")) {
                    rewrite(message.obj, "intent", "activityInfo", true);
                }
            }
        } catch (Exception error) {
            throw new EngineException("Android Activity launch adaptation failed", error);
        }
        return previous != null && previous.handleMessage(message);
    }

    private void rewrite(Object record, String intentField, String infoField, boolean legacy) throws Exception {
        Intent stub = (Intent) RuntimeReflection.get(record, intentField);
        if (stub == null || stub.getComponent() == null
                || !stub.getComponent().getClassName().startsWith(GuestActivity.class.getName() + "$P")) return;
        Bundle request = stub.getBundleExtra(EngineProtocol.LAUNCH);
        ActivityInfo target = runtime.activity(request);
        Intent guest = new Intent(stub);
        guest.removeExtra(EngineProtocol.LAUNCH);
        guest.setComponent(new ComponentName(target.packageName, target.name));
        guest.setExtrasClassLoader(runtime.classLoader());
        RuntimeReflection.set(record, intentField, guest);
        RuntimeReflection.set(record, infoField, target);
        if (legacy) RuntimeReflection.set(record, "packageInfo", runtime.loadedApk());
    }
}
