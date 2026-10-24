package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Keeps PendingIntent creation, identity queries and delivery inside the virtual service. */
final class GuestPendingIntents implements InvocationHandler {
    private final Engine engine;
    private final Object original;
    private GuestPendingIntents(Engine engine, Object original) { this.engine = engine; this.original = original; }

    static void install(Engine engine) throws Exception {
        Class<?> manager = Class.forName("android.app.ActivityManager");
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(manager, "getService"));
        Object singleton = RuntimeReflection.field(manager, "IActivityManagerSingleton").get(null);
        Class<?> contract = Class.forName("android.app.IActivityManager");
        RuntimeReflection.set(singleton, "mInstance", Proxy.newProxyInstance(contract.getClassLoader(),
                new Class<?>[]{contract}, new GuestPendingIntents(engine, original)));
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String operation = method.getName();
        if (operation.equals("finishReceiver") && GuestReceiverDelivery.finish((IBinder) args[0],
                ((Number) args[1]).intValue(), (String) args[2], (Bundle) args[3], Boolean.TRUE.equals(args[4]))) return null;
        if (operation.equals("getIntentSender") || operation.equals("getIntentSenderWithFeature")) {
            int shift = operation.equals("getIntentSenderWithFeature") ? 1 : 0;
            if (args[2 + shift] != null || args[3 + shift] != null
                    || args[8 + shift] != null && !((Bundle) args[8 + shift]).isEmpty()) {
                throw new EngineException("Activity result and option-bearing PendingIntents are not implemented");
            }
            Intent[] intents = (Intent[]) args[5 + shift];
            String[] types = (String[]) args[6 + shift];
            if (intents == null || intents.length != 1 || types != null && types.length != 1) {
                throw new EngineException("Only one broadcast Intent per PendingIntent is supported");
            }
            Bundle input = request("create");
            input.putInt("type", ((Number) args[0]).intValue());
            input.putString("package", (String) args[1]);
            input.putInt("request_code", ((Number) args[4 + shift]).intValue());
            input.putParcelable("intent", new Intent(intents[0]));
            input.putString("resolved_type", types == null ? null : types[0]);
            input.putInt("flags", ((Number) args[7 + shift]).intValue());
            input.putInt("user", ((Number) args[9 + shift]).intValue());
            IBinder sender = transact(input).getBinder("sender");
            return sender == null ? null : RuntimeReflection.invoke(null,
                    RuntimeReflection.method(Class.forName("android.content.IIntentSender$Stub"), "asInterface", IBinder.class), sender);
        }
        if (operation.equals("sendIntentSender")) {
            int target = senderParameter(method);
            if (target < 0 || args.length != target + 8) throw new EngineException("Unknown sendIntentSender signature");
            Bundle input = request("send");
            input.putBinder("sender", ServerPendingIntents.binderOf(args[target]));
            input.putBinder("whitelist", (IBinder) args[target + 1]);
            input.putInt("code", ((Number) args[target + 2]).intValue());
            input.putParcelable("intent", (Intent) args[target + 3]);
            input.putString("resolved_type", (String) args[target + 4]);
            input.putBinder("finished", ServerPendingIntents.binderOf(args[target + 5]));
            input.putString("permission", (String) args[target + 6]);
            input.putBundle("options", (Bundle) args[target + 7]);
            return transact(input).getInt("result", ServerPendingIntents.CANCELED);
        }
        int target = senderParameter(method);
        if (target >= 0) {
            Bundle input = request("info");
            input.putBinder("sender", ServerPendingIntents.binderOf(args[target]));
            if (operation.equals("cancelIntentSender")) {
                input.putString("operation", "cancel");
                transact(input);
                return null;
            }
            if (operation.equals("registerIntentSenderCancelListenerEx") || operation.equals("registerIntentSenderCancelListener")
                    || operation.equals("unregisterIntentSenderCancelListener")) {
                input.putString("operation", operation.startsWith("unregister") ? "unlisten" : "listen");
                IBinder listener = ServerPendingIntents.binderOf(args[target + 1]);
                input.putBinder("listener", listener);
                boolean registered = transact(input).getBoolean("registered");
                if (operation.equals("registerIntentSenderCancelListener") && !registered) {
                    // Before API 33 registration reports existing cancellation via IResultReceiver.
                    Object receiver = args[target + 1];
                    RuntimeReflection.invoke(receiver, RuntimeReflection.method(method.getParameterTypes()[target + 1],
                            "send", int.class, Bundle.class), 0, null);
                }
                return method.getReturnType() == boolean.class ? registered : null;
            }
            Bundle info = transact(input);
            if (operation.equals("getInfoForIntentSender")) {
                return Class.forName("android.app.ActivityManager$PendingIntentInfo")
                        .getConstructor(String.class, int.class, boolean.class, int.class)
                        .newInstance(info.getString("package"), info.getInt("uid"), info.getBoolean("immutable"), 1);
            }
            if (operation.equals("getPackageForIntentSender")) return info.getString("package");
            if (operation.equals("getUidForIntentSender")) return info.getInt("uid");
            if (operation.equals("isIntentSenderImmutable")) return info.getBoolean("immutable");
            if (operation.equals("isIntentSenderABroadcast") || operation.equals("isIntentSenderTargetedToPackage")) return true;
            if (operation.equals("isIntentSenderAnActivity") || operation.equals("isIntentSenderAService")
                    || operation.equals("isIntentSenderAForegroundService")) return false;
            if (operation.equals("getIntentForIntentSender")) return info.getParcelable("intent");
            if (operation.equals("getTagForIntentSender")) return String.valueOf(args[target + 1]) + info.getString("package");
            throw new EngineException("Virtual PendingIntent operation is not implemented: " + operation);
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static int senderParameter(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int index = 0; index < types.length; index++) {
            if (types[index].getName().equals("android.content.IIntentSender")) return index;
        }
        return -1;
    }
    private static Bundle request(String operation) {
        Bundle input = EngineProtocol.response();
        input.putString("operation", operation);
        return input;
    }
    private Bundle transact(Bundle input) {
        return EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.PENDING_INTENT, input);
    }
}
