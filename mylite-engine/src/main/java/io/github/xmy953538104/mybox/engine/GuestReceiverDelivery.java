package io.github.xmy953538104.mybox.engine;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/** Real Receiver/PendingResult execution with a unique completion token for each delivery. */
final class GuestReceiverDelivery {
    static final String COMPLETION = "io.github.xmy953538104.mybox.engine.ReceiverCompletion.v1";
    private static final Map<ReceiverToken, Delivery> pending = new HashMap<>();
    private static final Handler timeout = new Handler(Looper.getMainLooper());
    private static final class ReceiverToken extends Binder { }
    private static final class Delivery {
        final IBinder completion;
        final Bundle initial;
        final Runnable expiration;
        Delivery(IBinder completion, Bundle initial, ReceiverToken token) {
            this.completion = completion; this.initial = initial;
            expiration = () -> complete(token, "Receiver did not finish within ten seconds");
        }
    }

    static Bundle deliver(GuestRuntime runtime, Bundle input) throws Exception {
        IBinder completion = input.getBinder("completion");
        Intent intent = input.getParcelable("intent");
        if (completion == null || intent == null) throw new IllegalArgumentException("Receiver delivery is incomplete");
        PendingIntentExtras.restore(intent, runtime.classLoader());
        long remainingBudget = AlarmDeliveryTicket.remaining(input, 10_000);
        IBinder registered = input.getBinder("receiver");
        Handler handler = new Handler(Looper.getMainLooper());
        Object dispatcher = null;
        if (registered != null) {
            IInterface receiver = registered.queryLocalInterface("android.content.IIntentReceiver");
            if (receiver == null || receiver.asBinder() != registered) throw new SecurityException("Receiver is not local");
            Object reference = RuntimeReflection.get(receiver, "mDispatcher");
            if (!(reference instanceof WeakReference<?>)) throw new EngineException("Unknown registered receiver dispatcher");
            dispatcher = ((WeakReference<?>) reference).get();
            if (dispatcher == null) throw new EngineException("Registered receiver was collected");
            handler = (Handler) RuntimeReflection.get(dispatcher, "mActivityThread");
        }
        ReceiverToken token = new ReceiverToken();
        Bundle initial = EngineProtocol.response();
        initial.putInt("result_code", input.getInt("result_code"));
        initial.putString("result_data", input.getString("result_data"));
        initial.putBundle("result_extras", input.getBundle("result_extras"));
        Delivery delivery = new Delivery(completion, initial, token);
        synchronized (pending) { pending.put(token, delivery); }
        final Object registeredDispatcher = dispatcher;
        timeout.postDelayed(delivery.expiration, remainingBudget);
        if (!handler.post(() -> {
            try {
                synchronized (pending) { if (!pending.containsKey(token)) return; }
                AlarmDeliveryTicket.remaining(input, 10_000);
                String expectedGeneration = input.getString("target_package_generation");
                if (expectedGeneration != null) {
                    io.github.xmy953538104.mybox.engine.packages.InstalledPackage current = Engine.get().packages().get(runtime.packageInfo().packageName);
                    if (current == null || !expectedGeneration.equals(current.baseApk.getAbsolutePath())
                            || !expectedGeneration.equals(runtime.packageInfo().applicationInfo.sourceDir)) {
                        throw new SecurityException("Receiver package generation changed before execution");
                    }
                }
                BroadcastReceiver receiver;
                Context context;
                if (registeredDispatcher != null) {
                    if (Boolean.TRUE.equals(RuntimeReflection.get(registeredDispatcher, "mForgotten"))) { complete(token, null); return; }
                    receiver = (BroadcastReceiver) RuntimeReflection.get(registeredDispatcher, "mReceiver");
                    context = (Context) RuntimeReflection.get(registeredDispatcher, "mContext");
                } else {
                    ActivityInfo info = receiverInfo(runtime, input.getParcelable("component"));
                    context = runtime.application().createPackageContext(info.packageName,
                            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                    if (info.splitName != null) context = context.createContextForSplit(info.splitName);
                    ClassLoader loader = context.getClassLoader();
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        Object factory = RuntimeReflection.invoke(runtime.loadedApk(), RuntimeReflection.method(runtime.loadedApk().getClass(), "getAppFactory"));
                        receiver = (BroadcastReceiver) RuntimeReflection.invoke(factory, RuntimeReflection.method(factory.getClass(),
                                "instantiateReceiver", ClassLoader.class, String.class, Intent.class), loader, info.name, intent);
                    } else receiver = (BroadcastReceiver) loader.loadClass(info.name).getDeclaredConstructor().newInstance();
                    context = (Context) RuntimeReflection.invoke(context,
                            RuntimeReflection.method(context.getClass(), "getReceiverRestrictedContext"));
                }
                intent.setExtrasClassLoader(receiver.getClass().getClassLoader());
                BroadcastReceiver.PendingResult result = pendingResult(token, intent.getFlags(), input);
                RuntimeReflection.invoke(receiver, RuntimeReflection.method(BroadcastReceiver.class,
                        "setPendingResult", BroadcastReceiver.PendingResult.class), result);
                AlarmDeliveryTicket.admit(input);
                receiver.onReceive(context, intent);
                // goAsync clears mPendingResult and its real PendingResult.finish calls our IAM
                // finishReceiver hook later. A synchronous receiver finishes after onReceive.
                if (RuntimeReflection.invoke(receiver, RuntimeReflection.method(BroadcastReceiver.class, "getPendingResult")) != null) result.finish();
            } catch (Throwable failure) {
                complete(token, "Receiver failed: " + failure.getClass().getSimpleName());
                android.util.Log.w("MyboxEngine", "Virtual receiver execution failed", failure);
            }
        })) complete(token, "Receiver Handler rejected delivery");
        return EngineProtocol.response();
    }

    private static BroadcastReceiver.PendingResult pendingResult(IBinder token, int flags, Bundle input) throws Exception {
        Class<BroadcastReceiver.PendingResult> type = BroadcastReceiver.PendingResult.class;
        try {
            Constructor<BroadcastReceiver.PendingResult> constructor = type.getDeclaredConstructor(int.class, String.class,
                    Bundle.class, int.class, boolean.class, boolean.class, boolean.class, IBinder.class, int.class,
                    int.class, int.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(input.getInt("result_code"), input.getString("result_data"),
                    input.getBundle("result_extras"), 0, input.getBoolean("ordered"), false, false,
                    token, 0, flags, input.getInt("sender_uid"), input.getString("sender_package"));
        } catch (NoSuchMethodException olderAndroid) {
            Constructor<BroadcastReceiver.PendingResult> constructor = type.getDeclaredConstructor(int.class, String.class,
                    Bundle.class, int.class, boolean.class, boolean.class, IBinder.class, int.class, int.class);
            constructor.setAccessible(true);
            // TYPE_COMPONENT guarantees completion, including queued work on older Android.
            return constructor.newInstance(input.getInt("result_code"), input.getString("result_data"),
                    input.getBundle("result_extras"), 0, input.getBoolean("ordered"), false, token, 0, flags);
        }
    }

    private static ActivityInfo receiverInfo(GuestRuntime runtime, ComponentName component) throws Exception {
        android.content.pm.PackageInfo current = Engine.get().packages().getPackageInfo(runtime.packageInfo().packageName,
                android.content.pm.PackageManager.GET_RECEIVERS);
        if (current == null || !current.applicationInfo.sourceDir.equals(runtime.packageInfo().applicationInfo.sourceDir)) {
            throw new SecurityException("Receiver installation changed before delivery");
        }
        if (component != null && runtime.packageInfo().packageName.equals(component.getPackageName())
                && current.receivers != null) {
            for (ActivityInfo info : current.receivers) {
                if (component.getClassName().equals(info.name) && info.enabled && info.applicationInfo.enabled
                        && runtime.ownsProcess(info.processName)) return info;
            }
        }
        throw new SecurityException("Receiver is not declared in the bound guest process");
    }

    static boolean finish(IBinder token, int code, String data, Bundle extras, boolean abort) {
        if (!(token instanceof ReceiverToken)) return false;
        Bundle result = EngineProtocol.response();
        result.putInt("result_code", code);
        result.putString("result_data", data);
        result.putBundle("result_extras", extras);
        result.putBoolean("abort", abort);
        complete((ReceiverToken) token, null, result);
        return true;
    }

    private static void complete(ReceiverToken token, String failure) {
        complete(token, failure, null);
    }

    private static void complete(ReceiverToken token, String failure, Bundle result) {
        Delivery delivery;
        synchronized (pending) { delivery = pending.remove(token); }
        if (delivery == null) return; // A timed-out delivery cannot complete a different one.
        timeout.removeCallbacks(delivery.expiration);
        if (result == null) result = new Bundle(delivery.initial);
        if (failure != null) result.putString("failure", failure);
        try { EngineProtocol.request(delivery.completion, COMPLETION, IBinder.FIRST_CALL_TRANSACTION, result); }
        catch (RuntimeException disconnected) { android.util.Log.w("MyboxEngine", "Receiver completion service disconnected"); }
    }

    private GuestReceiverDelivery() { }
}
