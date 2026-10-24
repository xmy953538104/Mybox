package io.github.xmy953538104.mybox.engine;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Transferable component capabilities, scoped to the creator's committed APK generation. */
final class ServerPendingIntents {
    static final String INTENT_SENDER = "android.content.IIntentSender";
    static final int CANCELED = -96; // ActivityManager.START_CANCELED
    static final int BROADCAST = 1, SERVICE = 4, FOREGROUND_SERVICE = 5;
    private final Engine engine;
    private final ServerBroadcasts broadcasts;
    private final ComponentRouter components;
    private final Map<Key, Token> active = new HashMap<>();
    private final ExecutorService delivery = Executors.newSingleThreadExecutor();
    private final ExecutorService mutations = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64));
    private volatile Thread mutationThread;
    private final OsPendingStore store;
    private boolean reconciled;
    private final Handler callbacks = new Handler(Looper.getMainLooper());

    interface StatusDelivery { void send(Intent status) throws Exception; }

    private static final class Key {
        final String packageName, generation, type;
        final int senderType, requestCode, flags;
        final Intent.FilterComparison intent;
        Key(String packageName, String generation, int senderType, int requestCode, int flags, Intent intent, String type) {
            this.packageName = packageName;
            this.generation = generation;
            this.senderType = senderType;
            this.requestCode = requestCode;
            this.flags = flags;
            this.intent = new Intent.FilterComparison(new Intent(intent));
            this.type = type;
        }
        @Override public int hashCode() { return Objects.hash(packageName, generation, senderType, requestCode, flags, intent, type); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return senderType == key.senderType && requestCode == key.requestCode && flags == key.flags && packageName.equals(key.packageName)
                    && generation.equals(key.generation) && intent.equals(key.intent) && Objects.equals(type, key.type);
        }
    }

    private final class Token extends Binder {
        final Key key;
        final CallerIdentity creator;
        final Map<IBinder, IBinder.DeathRecipient> cancelListeners = new HashMap<>();
        Intent intent;
        boolean canceled;
        volatile PendingIntent physical;
        OsPendingStore.Snapshot snapshot;
        Object physicalCancelListener;
        Token(Key key, CallerIdentity creator, Intent intent) {
            this.key = key;
            this.creator = creator;
            this.intent = new Intent(intent);
            attachInterface(null, INTENT_SENDER);
        }
        ServerPendingIntents owner() { return ServerPendingIntents.this; }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == IBinder.INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(INTENT_SENDER); return true; }
            if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
            data.enforceInterface(INTENT_SENDER);
            int resultCode = data.readInt();
            Intent fillIn = data.readInt() == 0 ? null : Intent.CREATOR.createFromParcel(data);
            String resolvedType = data.readString();
            IBinder whitelist = data.readStrongBinder();
            IBinder finished = data.readStrongBinder();
            String permission = data.readString();
            Bundle options = data.readInt() == 0 ? null : Bundle.CREATOR.createFromParcel(data);
            try {
                if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected IIntentSender arguments");
                validateSendOptions(whitelist, permission, options);
                int status = send(this, resultCode, fillIn, resolvedType, finished);
                if (status == CANCELED && physical == null) finished(finished, new Intent(intent), CANCELED);
            } catch (Exception failure) {
                android.util.Log.w("MyboxEngine", "Virtual PendingIntent send rejected: " + failure.getClass().getSimpleName());
                if (physical == null) finished(finished, new Intent(intent), CANCELED);
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }

    ServerPendingIntents(Engine engine, ServerBroadcasts broadcasts, ComponentRouter components) throws IOException {
        this.engine = engine;
        this.broadcasts = broadcasts;
        this.components = components;
        store = new OsPendingStore(engine);
        enqueue(() -> {
            try { reconcile(); }
            catch (Exception failure) { android.util.Log.e("MyliteAlarms", "OS capability reconciliation failed: " + failure.getClass().getSimpleName()); }
        });
    }

    Bundle route(Bundle input, CallerIdentity caller) throws Exception {
        return mutate(() -> routeOnQueue(input, caller));
    }

    private Bundle routeOnQueue(Bundle input, CallerIdentity caller) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) throw new SecurityException("Invalid PendingIntent request");
        Bundle result = EngineProtocol.response();
        String operation = input.getString("operation");
        if ("create".equals(operation)) {
            result.putBinder("sender", create(input, caller));
            return result;
        }
        Token token = requireToken(input.getBinder("sender"));
        if (!token.canceled && token.physical != null && lookup(token.snapshot, 0) == null) retire(token);
        if ("info".equals(operation)) {
            result.putString("package", token.creator.packageName);
            result.putInt("uid", token.creator.appId);
            result.putInt("type", token.key.senderType);
            result.putBoolean("immutable", (token.key.flags & PendingIntent.FLAG_IMMUTABLE) != 0);
            synchronized (active) { result.putParcelable("intent", new Intent(token.intent)); }
        } else if ("cancel".equals(operation)) {
            requireCreator(token, caller);
            cancel(token);
        } else if ("send".equals(operation)) {
            validateSendOptions(input.getBinder("whitelist"), input.getString("permission"), input.getBundle("options"));
            result.putInt("result", send(token, input.getInt("code"), input.getParcelable("intent"),
                    input.getString("resolved_type"), input.getBinder("finished")));
        } else if ("listen".equals(operation) || "unlisten".equals(operation)) {
            IBinder listener = input.getBinder("listener");
            if (listener == null) throw new IllegalArgumentException("Cancel listener is missing");
            synchronized (active) {
                if ("unlisten".equals(operation)) {
                    IBinder.DeathRecipient death = token.cancelListeners.remove(listener);
                    if (death != null) listener.unlinkToDeath(death, 0);
                } else {
                    result.putBoolean("registered", !token.canceled);
                    if (!token.canceled && !token.cancelListeners.containsKey(listener)) {
                        if (token.cancelListeners.size() >= 32) throw new EngineException("Too many PendingIntent cancel listeners");
                        IBinder.DeathRecipient death = () -> enqueue(() -> token.cancelListeners.remove(listener));
                        listener.linkToDeath(death, 0);
                        token.cancelListeners.put(listener, death);
                    }
                }
            }
        } else throw new EngineException("Unknown PendingIntent operation");
        return result;
    }

    private IBinder create(Bundle input, CallerIdentity caller) throws Exception {
        if (!caller.packageName.equals(input.getString("package")) || input.getInt("user", -1) != caller.userId) {
            throw new SecurityException("PendingIntent creator differs from the registered process");
        }
        int senderType = input.getInt("type");
        if (senderType != BROADCAST && senderType != SERVICE && senderType != FOREGROUND_SERVICE) {
            throw new EngineException("Only broadcast, Service and foreground Service PendingIntents are implemented");
        }
        Intent intent = input.getParcelable("intent");
        validateIntent(intent, caller.packageName, senderType);
        int flags = input.getInt("flags");
        int controls = PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_UPDATE_CURRENT;
        int supported = controls | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_MUTABLE | 0xff;
        if ((flags & ~supported) != 0 || (flags & PendingIntent.FLAG_IMMUTABLE) != 0 && (flags & PendingIntent.FLAG_MUTABLE) != 0) {
            throw new IllegalArgumentException("Unsupported PendingIntent flags");
        }
        InstalledPackage installed = engine.packages().get(caller.packageName);
        if (installed == null || installed.appId != caller.appId
                || !installed.certificateSha256.equals(caller.certificateSha256)) throw new SecurityException("PendingIntent creator changed");
        Key key = new Key(caller.packageName, installed.baseApk.getAbsolutePath(), senderType, input.getInt("request_code"),
                flags & ~controls, intent, input.getString("resolved_type"));
        Token token = active.get(key);
        if (token != null && token.physical != null && lookup(token.snapshot, 0) == null) { retire(token); token = null; }
        if (token == null && eligible(key)) token = restore(key, caller, installed, intent);
        if (token != null && (flags & PendingIntent.FLAG_CANCEL_CURRENT) != 0) {
            cancel(token);
            token = null;
        }
        if (token != null && (flags & PendingIntent.FLAG_UPDATE_CURRENT) != 0) {
            Intent updated = new Intent(token.intent).replaceExtras(intent);
            if (token.physical == null) token.intent = updated;
            else if (!update(token, updated)) token = null;
        }
        if (token == null && (flags & PendingIntent.FLAG_NO_CREATE) == 0) {
            if (active.size() >= 1024) throw new EngineException("Too many active virtual PendingIntents");
            token = new Token(key, caller, intent);
            active.put(key, token);
        }
        return token;
    }

    StatusDelivery prepareStatus(CallerIdentity caller, IntentSender sender) throws Exception {
        return mutate(() -> prepareStatusOnQueue(caller, sender));
    }

    private StatusDelivery prepareStatusOnQueue(CallerIdentity caller, IntentSender sender) throws Exception {
        Token token = requireToken(binderOf(RuntimeReflection.get(sender, "mTarget")));
        requireCreator(token, caller);
        requireGeneration(token);
        synchronized (active) {
            if (token.canceled || token.key.senderType != BROADCAST || (token.key.flags & PendingIntent.FLAG_IMMUTABLE) != 0) {
                throw new SecurityException("Installer status requires a live mutable virtual broadcast PendingIntent");
            }
        }
        return status -> {
            if (send(token, 0, status, null, null) == CANCELED) throw new IntentSender.SendIntentException("Virtual status receiver was canceled");
        };
    }

    private Token requireToken(IBinder binder) {
        if (!(binder instanceof ServerPendingIntents.Token) || ((Token) binder).owner() != this) {
            throw new SecurityException("IntentSender is not a registered virtual capability");
        }
        return (Token) binder;
    }

    private static void requireCreator(Token token, CallerIdentity caller) {
        if (caller.userId != token.creator.userId || caller.appId != token.creator.appId || !caller.packageName.equals(token.creator.packageName)
                || !caller.certificateSha256.equals(token.creator.certificateSha256)) {
            throw new SecurityException("PendingIntent belongs to another virtual creator");
        }
    }

    private void requireGeneration(Token token) throws Exception {
        InstalledPackage current = engine.packages().get(token.creator.packageName);
        if (current == null || !token.key.generation.equals(current.baseApk.getAbsolutePath())
                || current.appId != token.creator.appId || !current.certificateSha256.equals(token.creator.certificateSha256)) {
            throw new SecurityException("PendingIntent APK generation is no longer installed");
        }
    }

    private int send(Token token, int code, Intent fillIn, String resolvedType, IBinder finished) throws Exception {
        return mutate(() -> sendOnQueue(token, code, fillIn, resolvedType, finished));
    }

    private int sendOnQueue(Token token, int code, Intent fillIn, String resolvedType, IBinder finished) throws Exception {
        requireGeneration(token);
        if (token.canceled) return CANCELED;
        if (token.physical != null) {
            try {
                token.physical.send(engine.hostContext(), code, null, (pending, outer, result, data, extras) -> {
                    try {
                        OsPendingStore.Snapshot delivered = store.verify(outer);
                        finished(finished, delivered.intent, result, data, extras);
                    } catch (Exception invalid) {
                        // The callback itself came from the real OS dispatch; never invent one on send rejection.
                        finished(finished, new Intent(token.intent), CANCELED, null, null);
                    }
                }, callbacks);
                return 0;
            } catch (PendingIntent.CanceledException canceled) {
                retire(token);
                return CANCELED;
            }
        }
        Intent intent;
        String type;
        List<IBinder> canceled = new ArrayList<>();
        synchronized (active) {
            if (token.canceled) return CANCELED;
            intent = new Intent(token.intent);
            type = token.key.type;
            if (fillIn != null && (token.key.flags & PendingIntent.FLAG_IMMUTABLE) == 0) {
                int changed = intent.fillIn(fillIn, token.key.flags & 0xff);
                if ((changed & Intent.FILL_IN_DATA) != 0) type = resolvedType;
            }
            validateIntent(intent, token.creator.packageName, token.key.senderType);
            if ((token.key.flags & PendingIntent.FLAG_ONE_SHOT) != 0) canceled = cancelLocked(token);
        }
        notifyCanceled(canceled);
        final String deliveryType = type;
        delivery.execute(() -> {
            int resultCode = token.key.senderType == BROADCAST ? code : 0;
            try {
                requireGeneration(token);
                if (token.key.senderType == BROADCAST) broadcasts.sendPending(intent, deliveryType, token.creator);
                else {
                    Bundle request = EngineProtocol.response();
                    request.putParcelable("component", intent.getComponent());
                    request.putParcelable("intent", intent);
                    request.putBoolean("require_foreground", token.key.senderType == FOREGROUND_SERVICE);
                    Bundle started = components.routeInternal(EngineProtocol.START_SERVICE, request);
                    if (!intent.getComponent().equals(started.getParcelable("component"))) {
                        throw new EngineException("PendingIntent Service start was not accepted");
                    }
                }
            } catch (Exception failure) {
                resultCode = CANCELED;
                android.util.Log.w("MyboxEngine", "Virtual PendingIntent delivery failed, type="
                        + token.key.senderType + ", error=" + failure.getClass().getSimpleName());
            }
            // Service completion acknowledges the actual start request, not asynchronous guest work.
            finished(finished, intent, resultCode);
        });
        return 0;
    }

    private List<IBinder> cancelLocked(Token token) {
        if (token.canceled) return new ArrayList<>();
        token.canceled = true;
        if (active.get(token.key) == token) active.remove(token.key);
        List<IBinder> listeners = new ArrayList<>(token.cancelListeners.keySet());
        for (Map.Entry<IBinder, IBinder.DeathRecipient> item : token.cancelListeners.entrySet()) item.getKey().unlinkToDeath(item.getValue(), 0);
        token.cancelListeners.clear();
        return listeners;
    }

    interface AlarmOperation { void run(PendingIntent physical) throws Exception; }

    void alarm(CallerIdentity caller, IBinder sender, String operation, AlarmOperation action) throws Exception {
        mutate(() -> {
            Token token = requireToken(sender);
            requireCreator(token, caller);
            requireGeneration(token);
            if (token.canceled) throw new PendingIntent.CanceledException();
            if ("remove".equals(operation) && token.physical == null) return null;
            materialize(token);
            store.put(token.snapshot, operation);
            try {
                action.run(token.physical);
            } catch (SecurityException | IllegalArgumentException rejectedBeforeMutation) {
                store.put(token.snapshot, null);
                throw rejectedBeforeMutation;
            } catch (Exception uncertain) {
                // No cross-system transaction exists. Cancel future uses; an already accepted delivery remains valid.
                token.physical.cancel();
                notifyCanceled(cancelLocked(token));
                throw new EngineException("OS alarm mutation is uncertain; capability retired", uncertain);
            }
            try { store.put(token.snapshot, null); }
            catch (IOException uncertain) {
                token.physical.cancel();
                notifyCanceled(cancelLocked(token));
                throw new EngineException("OS alarm accepted but durable commit failed; capability retired", uncertain);
            }
            return null;
        });
    }

    void removeAlarms(CallerIdentity caller, AlarmOperation action) throws Exception {
        mutate(() -> {
            InstalledPackage current = engine.packages().get(caller.packageName);
            if (current == null) throw new SecurityException("Alarm creator is no longer installed");
            for (OsPendingStore.Record record : store.all()) {
                OsPendingStore.Snapshot snapshot = record.snapshot;
                if (!snapshot.creator.packageName.equals(caller.packageName)
                        || !snapshot.creator.generation.equals(current.baseApk.getAbsolutePath())) continue;
                snapshot.creator.requireCaller(caller);
                snapshot.creator.requireCurrent(engine);
                PendingIntent physical = lookup(snapshot, 0);
                if (physical == null) { store.remove(snapshot.keyId); continue; }
                store.put(snapshot, "remove");
                try { action.run(physical); store.put(snapshot, null); }
                catch (Exception uncertain) {
                    physical.cancel();
                    throw new EngineException("OS alarm removal is uncertain; capability retired", uncertain);
                }
            }
            return null;
        });
    }

    OsPendingStore.Snapshot verifyOsDelivery(Intent intent) throws Exception { return store.verify(intent); }

    private static boolean eligible(Key key) {
        return key.senderType == BROADCAST && (key.flags & PendingIntent.FLAG_IMMUTABLE) != 0
                && key.intent.getIntent().getComponent() != null;
    }

    private void materialize(Token token) throws Exception {
        if (token.physical != null) return;
        if (!eligible(token.key)) throw new UnsupportedOperationException("OS alarms require an immutable manifest broadcast capability");
        OsPendingStore.Creator creator = new OsPendingStore.Creator(engine.packages().get(token.creator.packageName));
        creator.requireCaller(token.creator);
        OsPendingStore.validate(token.intent, token.key.flags, creator);
        broadcasts.requireOsReceiver(creator, token.intent);
        String keyId = OsPendingStore.keyId(creator, token.key.requestCode, token.key.flags, token.intent, token.key.type);
        OsPendingStore.Snapshot snapshot = new OsPendingStore.Snapshot(UUID.randomUUID().toString(), keyId, 1,
                store.boot, token.key.requestCode, token.key.flags, creator, token.intent, token.key.type);
        store.put(snapshot, "create");
        PendingIntent physical = null;
        try {
            physical = PendingIntent.getBroadcast(engine.hostContext(), 0, store.envelope(snapshot), snapshot.flags);
            if (physical == null) throw new EngineException("OS did not create the broadcast capability");
            token.physical = physical;
            token.snapshot = snapshot;
            watchCancellation(token);
            if (token.canceled) throw new PendingIntent.CanceledException();
            store.put(snapshot, null);
        } catch (Exception failure) {
            if (physical != null) physical.cancel();
            notifyCanceled(cancelLocked(token));
            // A failed journal remains for bounded cancellation on the next server start.
            throw failure;
        }
    }

    private Token restore(Key key, CallerIdentity caller, InstalledPackage installed, Intent intent) throws Exception {
        OsPendingStore.Creator creator = new OsPendingStore.Creator(installed);
        String keyId = OsPendingStore.keyId(creator, key.requestCode, key.flags, intent, key.type);
        OsPendingStore.Record record = store.get(keyId);
        if (record == null) return null;
        creator.requireCaller(caller);
        PendingIntent physical = lookup(record.snapshot, 0);
        if (physical == null) { store.remove(keyId); return null; }
        Token token = new Token(key, caller, record.snapshot.intent);
        token.physical = physical; token.snapshot = record.snapshot;
        active.put(key, token);
        watchCancellation(token);
        return token.canceled ? null : token;
    }

    private boolean update(Token token, Intent intent) throws Exception {
        OsPendingStore.Snapshot old = token.snapshot;
        OsPendingStore.validate(intent, token.key.flags, old.creator);
        broadcasts.requireOsReceiver(old.creator, intent);
        OsPendingStore.Snapshot next = new OsPendingStore.Snapshot(old.capId, old.keyId, old.revision + 1,
                old.boot, old.requestCode, old.flags, old.creator, intent, old.resolvedType);
        store.put(next, "update");
        try {
            PendingIntent physical = lookup(next, PendingIntent.FLAG_UPDATE_CURRENT);
            if (physical == null) { retire(token); return false; }
            if (!physical.equals(token.physical)) throw new SecurityException("OS PendingIntent identity changed during update");
            store.put(next, null);
            token.snapshot = next; token.intent = new Intent(next.intent);
            return true;
        } catch (Exception uncertain) {
            token.physical.cancel(); notifyCanceled(cancelLocked(token));
            throw uncertain;
        }
    }

    private PendingIntent lookup(OsPendingStore.Snapshot snapshot, int control) throws Exception {
        return PendingIntent.getBroadcast(engine.hostContext(), 0,
                control == 0 ? store.identity(snapshot) : store.envelope(snapshot),
                snapshot.flags | PendingIntent.FLAG_NO_CREATE | control);
    }

    private void cancel(Token token) throws Exception {
        if (token.canceled) return;
        if (token.physical != null) {
            store.put(token.snapshot, "cancel");
            token.physical.cancel();
        }
        retire(token);
    }

    private void retire(Token token) throws IOException {
        notifyCanceled(cancelLocked(token));
        if (token.snapshot != null) {
            OsPendingStore.Record current = store.get(token.snapshot.keyId);
            if (current != null && current.snapshot.capId.equals(token.snapshot.capId)) store.remove(token.snapshot.keyId);
        }
    }

    private void watchCancellation(Token token) throws Exception {
        Class<?> listenerClass = Class.forName("android.app.PendingIntent$CancelListener");
        Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass}, (proxy, method, args) -> {
            if (method.getName().equals("onCanceled")) {
                enqueue(() -> { try { retire(token); } catch (IOException failure) { android.util.Log.e("MyliteAlarms", "Canceled capability journal could not be removed"); } });
                return null;
            }
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return "MylitePhysicalCancelListener";
        });
        token.physicalCancelListener = listener;
        try {
            java.lang.reflect.Method method = RuntimeReflection.method(PendingIntent.class, "addCancelListener", java.util.concurrent.Executor.class, listenerClass);
            boolean alive = (Boolean) RuntimeReflection.invoke(token.physical, method,
                    (java.util.concurrent.Executor) this::enqueue, listener);
            if (!alive) retire(token);
        } catch (NoSuchMethodException olderPlatform) {
            RuntimeReflection.invoke(token.physical, RuntimeReflection.method(PendingIntent.class, "registerCancelListener", listenerClass), listener);
        }
    }

    private void reconcile() throws Exception {
        if (reconciled) return;
        for (OsPendingStore.Record record : store.all()) {
            boolean invalid = record.snapshot.boot != store.boot || record.journal != null;
            try { record.snapshot.creator.requireCurrent(engine); }
            catch (SecurityException retired) { invalid = true; }
            PendingIntent physical = lookup(record.snapshot, 0);
            if (invalid && physical != null) physical.cancel();
            if (invalid || physical == null) store.remove(record.snapshot.keyId);
        }
        reconciled = true;
    }

    private <T> T mutate(Callable<T> operation) throws Exception {
        if (Thread.currentThread() == mutationThread) return operation.call();
        try {
            return mutations.submit(() -> {
                mutationThread = Thread.currentThread();
                long identity = Binder.clearCallingIdentity();
                try { reconcile(); return operation.call(); }
                finally { Binder.restoreCallingIdentity(identity); }
            }).get();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new EngineException("PendingIntent mutation failed", cause);
        }
    }

    private void enqueue(Runnable operation) {
        try { mutations.execute(() -> { mutationThread = Thread.currentThread(); operation.run(); }); }
        catch (java.util.concurrent.RejectedExecutionException overloaded) {
            // NO_CREATE/set/send still consult the OS record. A dropped notification cannot recreate it.
            android.util.Log.e("MyliteAlarms", "PendingIntent cancellation queue is full");
        }
    }

    private static void validateIntent(Intent intent, String creator, int senderType) {
        if (intent == null || intent.getSelector() != null || intent.getClipData() != null
                || (intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) != 0) {
            throw new EngineException("PendingIntent selectors and URI grants are not implemented");
        }
        if (intent.getComponent() != null && !creator.equals(intent.getComponent().getPackageName())
                || intent.getPackage() != null && !creator.equals(intent.getPackage())) {
            throw new SecurityException("Cross-package PendingIntent targets are not implemented");
        }
        if (senderType != BROADCAST && intent.getComponent() == null) {
            throw new EngineException("Service PendingIntents require an explicit same-package component");
        }
        if (intent.getComponent() == null && intent.getAction() == null) throw new IllegalArgumentException("A broadcast target or action is required");
    }

    private static void validateSendOptions(IBinder whitelist, String permission, Bundle options) {
        if (whitelist != null || permission != null || options != null && !options.isEmpty()) {
            throw new EngineException("PendingIntent whitelist, permissions and options are not implemented");
        }
    }

    static IBinder binderOf(Object value) {
        if (value == null) return null;
        return value instanceof IBinder ? (IBinder) value : ((IInterface) value).asBinder();
    }

    static void finished(IBinder callback, Intent intent, int resultCode) {
        finished(callback, intent, resultCode, null, null);
    }

    static void finished(IBinder callback, Intent intent, int resultCode, String resultData, Bundle resultExtras) {
        if (callback == null) return;
        try {
            Class<?> contract = Class.forName("android.content.IIntentReceiver");
            Object receiver = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName("android.content.IIntentReceiver$Stub"), "asInterface", IBinder.class), callback);
            RuntimeReflection.invoke(receiver, RuntimeReflection.method(contract, "performReceive", Intent.class,
                    int.class, String.class, Bundle.class, boolean.class, boolean.class, int.class),
                    intent, resultCode, resultData, resultExtras, false, false, 0);
        } catch (Exception failure) { android.util.Log.w("MyboxEngine", "PendingIntent completion callback disconnected"); }
    }

    private static void notifyCanceled(List<IBinder> listeners) {
        for (IBinder listener : listeners) {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.android.internal.os.IResultReceiver");
                data.writeInt(0);
                data.writeInt(0); // nullable result Bundle
                listener.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY);
            } catch (android.os.RemoteException ignored) { }
            finally { data.recycle(); }
        }
    }
}
