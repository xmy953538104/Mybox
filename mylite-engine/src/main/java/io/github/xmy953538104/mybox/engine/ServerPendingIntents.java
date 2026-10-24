package io.github.xmy953538104.mybox.engine;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Transferable broadcast capabilities, scoped to the creator's committed APK generation. */
final class ServerPendingIntents {
    static final String INTENT_SENDER = "android.content.IIntentSender";
    static final int CANCELED = -96; // ActivityManager.START_CANCELED
    private final Engine engine;
    private final ServerBroadcasts broadcasts;
    private final Map<Key, Token> active = new HashMap<>();
    private final ExecutorService delivery = Executors.newSingleThreadExecutor();

    interface StatusDelivery { void send(Intent status) throws Exception; }

    private static final class Key {
        final String packageName, generation, type;
        final int requestCode, flags;
        final Intent.FilterComparison intent;
        Key(String packageName, String generation, int requestCode, int flags, Intent intent, String type) {
            this.packageName = packageName;
            this.generation = generation;
            this.requestCode = requestCode;
            this.flags = flags;
            this.intent = new Intent.FilterComparison(new Intent(intent));
            this.type = type;
        }
        @Override public int hashCode() { return Objects.hash(packageName, generation, requestCode, flags, intent, type); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return requestCode == key.requestCode && flags == key.flags && packageName.equals(key.packageName)
                    && generation.equals(key.generation) && intent.equals(key.intent) && Objects.equals(type, key.type);
        }
    }

    private final class Token extends Binder {
        final Key key;
        final CallerIdentity creator;
        final Map<IBinder, IBinder.DeathRecipient> cancelListeners = new HashMap<>();
        Intent intent;
        boolean canceled;
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
                if (status == CANCELED) finished(finished, new Intent(intent), CANCELED);
            } catch (Exception failure) {
                android.util.Log.w("MyboxEngine", "Virtual PendingIntent send rejected: " + failure.getClass().getSimpleName());
                finished(finished, new Intent(intent), CANCELED);
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }

    ServerPendingIntents(Engine engine, ServerBroadcasts broadcasts) {
        this.engine = engine;
        this.broadcasts = broadcasts;
    }

    Bundle route(Bundle input, CallerIdentity caller) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION) throw new SecurityException("Invalid PendingIntent request");
        Bundle result = EngineProtocol.response();
        String operation = input.getString("operation");
        if ("create".equals(operation)) {
            result.putBinder("sender", create(input, caller));
            return result;
        }
        Token token = requireToken(input.getBinder("sender"));
        if ("info".equals(operation)) {
            result.putString("package", token.creator.packageName);
            result.putInt("uid", token.creator.appId);
            result.putBoolean("immutable", (token.key.flags & PendingIntent.FLAG_IMMUTABLE) != 0);
            synchronized (active) { result.putParcelable("intent", new Intent(token.intent)); }
        } else if ("cancel".equals(operation)) {
            requireCreator(token, caller);
            List<IBinder> listeners;
            synchronized (active) { listeners = cancelLocked(token); }
            notifyCanceled(listeners);
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
                        IBinder.DeathRecipient death = () -> { synchronized (active) { token.cancelListeners.remove(listener); } };
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
        if (input.getInt("type") != 1) throw new EngineException("Only broadcast PendingIntents are implemented");
        Intent intent = input.getParcelable("intent");
        validateIntent(intent, caller.packageName);
        int flags = input.getInt("flags");
        int controls = PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_UPDATE_CURRENT;
        int supported = controls | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_MUTABLE | 0xff;
        if ((flags & ~supported) != 0 || (flags & PendingIntent.FLAG_IMMUTABLE) != 0 && (flags & PendingIntent.FLAG_MUTABLE) != 0) {
            throw new IllegalArgumentException("Unsupported PendingIntent flags");
        }
        InstalledPackage installed = engine.packages().get(caller.packageName);
        if (installed == null || !installed.certificateSha256.equals(caller.certificateSha256)) throw new SecurityException("PendingIntent creator changed");
        Key key = new Key(caller.packageName, installed.baseApk.getAbsolutePath(), input.getInt("request_code"),
                flags & ~controls, intent, input.getString("resolved_type"));
        List<IBinder> canceled = new ArrayList<>();
        Token token;
        synchronized (active) {
            token = active.get(key);
            if (token != null && (flags & PendingIntent.FLAG_CANCEL_CURRENT) != 0) {
                canceled = cancelLocked(token);
                token = null;
            }
            if (token != null && (flags & PendingIntent.FLAG_UPDATE_CURRENT) != 0) token.intent.replaceExtras(intent);
            if (token == null && (flags & PendingIntent.FLAG_NO_CREATE) == 0) {
                if (active.size() >= 1024) throw new EngineException("Too many active virtual PendingIntents");
                token = new Token(key, caller, intent);
                active.put(key, token);
            }
        }
        notifyCanceled(canceled);
        return token;
    }

    StatusDelivery prepareStatus(CallerIdentity caller, IntentSender sender) throws Exception {
        Token token = requireToken(binderOf(RuntimeReflection.get(sender, "mTarget")));
        requireCreator(token, caller);
        requireGeneration(token);
        synchronized (active) {
            if (token.canceled || (token.key.flags & PendingIntent.FLAG_IMMUTABLE) != 0) {
                throw new SecurityException("Installer status requires a live mutable virtual PendingIntent");
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
        if (caller.userId != token.creator.userId || !caller.packageName.equals(token.creator.packageName)
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
        requireGeneration(token);
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
            validateIntent(intent, token.creator.packageName);
            if ((token.key.flags & PendingIntent.FLAG_ONE_SHOT) != 0) canceled = cancelLocked(token);
        }
        notifyCanceled(canceled);
        final String deliveryType = type;
        delivery.execute(() -> {
            int resultCode = code;
            try {
                requireGeneration(token);
                broadcasts.sendPending(intent, deliveryType, token.creator);
            } catch (Exception failure) {
                resultCode = CANCELED;
                android.util.Log.w("MyboxEngine", "Virtual PendingIntent receiver failed", failure);
            }
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

    private static void validateIntent(Intent intent, String creator) {
        if (intent == null || intent.getSelector() != null || intent.getClipData() != null
                || (intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) != 0) {
            throw new EngineException("PendingIntent selectors and URI grants are not implemented");
        }
        if (intent.getComponent() != null && !creator.equals(intent.getComponent().getPackageName())
                || intent.getPackage() != null && !creator.equals(intent.getPackage())) {
            throw new SecurityException("Cross-package PendingIntent targets are not implemented");
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
        if (callback == null) return;
        try {
            Class<?> contract = Class.forName("android.content.IIntentReceiver");
            Object receiver = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName("android.content.IIntentReceiver$Stub"), "asInterface", IBinder.class), callback);
            RuntimeReflection.invoke(receiver, RuntimeReflection.method(contract, "performReceive", Intent.class,
                    int.class, String.class, Bundle.class, boolean.class, boolean.class, int.class),
                    intent, resultCode, null, null, false, false, 0);
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
