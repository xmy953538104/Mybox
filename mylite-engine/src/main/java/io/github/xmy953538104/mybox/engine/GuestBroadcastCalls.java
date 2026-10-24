package io.github.xmy953538104.mybox.engine;

import android.accounts.AccountManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Collection;
import java.util.Set;

/** Routes custom and account broadcasts to registered receivers in the virtual package. */
final class GuestBroadcastCalls implements InvocationHandler {
    private final Engine engine;
    private final GuestRuntime runtime;
    private final Object original;
    private final Set<IBinder> virtualReceivers = new HashSet<>();
    private final Set<IBinder> physicalReceivers = new HashSet<>();

    private GuestBroadcastCalls(Engine engine, GuestRuntime runtime, Object original) {
        this.engine = engine;
        this.runtime = runtime;
        this.original = original;
    }

    static void install(Engine engine, GuestRuntime runtime) throws Exception {
        Class<?> manager = Class.forName("android.app.ActivityManager");
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(manager, "getService"));
        Object singleton = RuntimeReflection.field(manager, "IActivityManagerSingleton").get(null);
        Class<?> contract = Class.forName("android.app.IActivityManager");
        RuntimeReflection.set(singleton, "mInstance", Proxy.newProxyInstance(contract.getClassLoader(),
                new Class<?>[]{contract}, new GuestBroadcastCalls(engine, runtime, original)));
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String operation = method.getName();
        if (operation.equals("registerReceiver") || operation.equals("registerReceiverWithFeature")) {
            int filterIndex = -1;
            for (int i = 0; i < args.length; i++) if (args[i] instanceof IntentFilter) { filterIndex = i; break; }
            if (filterIndex < 1) throw new EngineException("Unknown receiver registration signature");
            IntentFilter filter = (IntentFilter) args[filterIndex];
            boolean virtual = false, physical = false;
            for (int i = 0; i < filter.countActions(); i++) {
                if (isVirtualAction(filter.getAction(i))) virtual = true; else physical = true;
            }
            Object callback = args[filterIndex - 1];
            IBinder receiver = callback == null ? null : ((IInterface) callback).asBinder();
            Bundle registration = null;
            if (virtual) {
                if (((Number) args[filterIndex + 2]).intValue() != 0) throw new SecurityException("Only virtual user 0 exists");
                if (receiver != null) {
                    Bundle input = EngineProtocol.response();
                    input.putBinder("receiver", receiver);
                    input.putParcelable("filter", selectActions(filter, true));
                    registration = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.REGISTER_RECEIVER, input);
                }
            }
            Object sticky = null;
            if (physical) {
                Object[] physicalArgs = args.clone();
                physicalArgs[1] = engine.hostContext().getPackageName();
                physicalArgs[filterIndex] = selectActions(filter, false);
                try { sticky = original(method, physicalArgs); }
                catch (Throwable failure) {
                    if (registration != null) {
                        Bundle rollback = EngineProtocol.response();
                        rollback.putBinder("receiver", receiver);
                        rollback.putLong("registration", registration.getLong("registration"));
                        try { EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.UNREGISTER_RECEIVER, rollback); }
                        catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                    }
                    throw failure;
                }
            }
            if (receiver != null) {
                if (virtual) synchronized (virtualReceivers) { virtualReceivers.add(receiver); }
                if (physical) synchronized (physicalReceivers) { physicalReceivers.add(receiver); }
            }
            return sticky;
        } else if (operation.equals("unregisterReceiver")) {
            IBinder receiver = ((IInterface) args[0]).asBinder();
            boolean virtual;
            synchronized (virtualReceivers) { virtual = virtualReceivers.contains(receiver); }
            if (virtual) {
                Bundle input = EngineProtocol.response();
                input.putBinder("receiver", receiver);
                EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.UNREGISTER_RECEIVER, input);
                synchronized (virtualReceivers) { virtualReceivers.remove(receiver); }
                synchronized (physicalReceivers) { if (!physicalReceivers.remove(receiver)) return null; }
            } else {
                synchronized (physicalReceivers) { physicalReceivers.remove(receiver); }
            }
        } else if (operation.equals("broadcastIntent") || operation.equals("broadcastIntentWithFeature")) {
            int intentIndex = operation.equals("broadcastIntent") ? 1 : 2;
            Intent intent = (Intent) args[intentIndex];
            String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
            if (isVirtualAction(intent.getAction()) || (target != null && engine.packages().get(target) != null)) {
                if (Boolean.TRUE.equals(args[args.length - 2])) throw new EngineException("Sticky virtual broadcasts are not implemented");
                if (((Number) args[args.length - 1]).intValue() != 0) throw new SecurityException("Only virtual user 0 exists");
                for (Object argument : args) if (argument instanceof String[] && ((String[]) argument).length > 0) {
                    throw new EngineException("Virtual broadcast permission/exclusion lists are not implemented");
                }
                if (((Number) args[args.length - 5]).intValue() >= 0) throw new EngineException("Broadcast AppOps are not implemented");
                Bundle options = (Bundle) args[args.length - 4];
                if (options != null && !options.isEmpty()) throw new EngineException("Virtual broadcast options are not implemented");
                Bundle input = EngineProtocol.response();
                input.putParcelable("intent", new Intent(intent));
                input.putString("resolved_type", (String) args[intentIndex + 1]);
                input.putBoolean("ordered", Boolean.TRUE.equals(args[args.length - 3]));
                input.putInt("result_code", ((Number) args[intentIndex + 3]).intValue());
                input.putString("result_data", (String) args[intentIndex + 4]);
                input.putBundle("result_extras", (Bundle) args[intentIndex + 5]);
                Object resultTo = args[intentIndex + 2];
                if (resultTo != null) input.putBinder("result_to", ((IInterface) resultTo).asBinder());
                EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.SEND_BROADCAST, input);
                return 0; // Framework BROADCAST_SUCCESS means enqueued, not a delivery acknowledgement.
            }
            if (GuestPackageQueries.isGoogleIdentity(target)) throw new EngineException("Physical Google broadcasts are unavailable");
        }
        return original(method, args);
    }

    private Object original(Method method, Object[] args) throws Throwable {
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static IntentFilter selectActions(IntentFilter source, boolean virtual) throws Exception {
        IntentFilter selected = new IntentFilter(source);
        Object actions = RuntimeReflection.get(selected, "mActions");
        if (actions == RuntimeReflection.get(source, "mActions") || !(actions instanceof Collection<?>)) {
            throw new EngineException("Android did not isolate the receiver filter copy");
        }
        ((Collection<?>) actions).clear();
        for (int index = 0; index < source.countActions(); index++) {
            String action = source.getAction(index);
            if (isVirtualAction(action) == virtual) selected.addAction(action);
        }
        return selected;
    }

    private static boolean isVirtualAction(String action) {
        return action != null && (AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION.equals(action)
                || ServerBroadcasts.VISIBLE_ACCOUNTS_CHANGED.equals(action) || !action.startsWith("android."));
    }

    static Bundle deliver(Bundle input) throws Exception {
        IBinder binder = input.getBinder("receiver");
        IInterface receiver = binder == null ? null : binder.queryLocalInterface("android.content.IIntentReceiver");
        if (receiver == null || receiver.asBinder() != binder) {
            throw new SecurityException("Broadcast receiver is not local to its registered process");
        }
        Intent intent = input.getParcelable("intent");
        if (intent == null) throw new IllegalArgumentException("Broadcast intent is missing");
        Method delivery;
        try {
            delivery = RuntimeReflection.method(receiver.getClass(), "performReceive", Intent.class, int.class,
                    String.class, Bundle.class, boolean.class, boolean.class, boolean.class, int.class, int.class, String.class);
        } catch (NoSuchMethodException olderAndroid) {
            delivery = RuntimeReflection.method(receiver.getClass(), "performReceive", Intent.class, int.class,
                    String.class, Bundle.class, boolean.class, boolean.class, int.class);
            RuntimeReflection.invoke(receiver, delivery, intent, 0, null, null, false, false, 0);
            return EngineProtocol.response();
        }
        // API34's raw seven-argument entry logs a wtf. Its local extension schedules the real
        // ReceiverDispatcher and preserves sending identity and unordered goAsync semantics.
        RuntimeReflection.invoke(receiver, delivery, intent, 0, null, null, false, false, true,
                0, input.getInt("sender_uid"), input.getString("sender_package"));
        return EngineProtocol.response();
    }
}
