package io.github.xmy953538104.mybox.engine;

import android.accounts.AccountManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.ComponentName;
import android.os.Binder;
import android.os.Parcel;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.xmy953538104.mybox.engine.accounts.AccountNotifier;

/** Registered receiver delivery, with ownership derived from the live process registry. */
final class ServerBroadcasts implements AccountNotifier {
    static final String VISIBLE_ACCOUNTS_CHANGED = "android.accounts.action.VISIBLE_ACCOUNTS_CHANGED";
    private final Engine engine;
    private final ProcessRegistry processes;
    private final Map<IBinder, Registration> receivers = new HashMap<>();
    private final ExecutorService delivery = Executors.newSingleThreadExecutor();
    private long nextRegistration = 1;

    private static final class FilterRegistration {
        final long id;
        final IntentFilter filter;
        FilterRegistration(long id, IntentFilter filter) { this.id = id; this.filter = new IntentFilter(filter); }
    }

    private final class Registration {
        final IBinder receiver, client;
        final CallerIdentity owner;
        final List<FilterRegistration> filters = new ArrayList<>();
        final IBinder.DeathRecipient death = () -> remove(this);
        Registration(IBinder receiver, CallerIdentity owner) {
            this.receiver = receiver;
            this.owner = owner;
            client = processes.clientForCaller(owner);
        }
    }

    ServerBroadcasts(Engine engine, ProcessRegistry processes) {
        this.engine = engine;
        this.processes = processes;
    }

    Bundle route(int operation, Bundle input, CallerIdentity caller) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION || input.getInt("user", 0) != 0) {
            throw new SecurityException("Invalid virtual broadcast scope");
        }
        Bundle result = EngineProtocol.response();
        if (operation == EngineProtocol.REGISTER_RECEIVER) {
            IBinder receiver = input.getBinder("receiver");
            IntentFilter filter = input.getParcelable("filter");
            if (receiver == null || filter == null || filter.countActions() == 0) {
                throw new IllegalArgumentException("A registered receiver and action filter are required");
            }
            synchronized (receivers) {
                Registration record = receivers.get(receiver);
                if (record != null) requireOwner(record, caller);
                else {
                    record = new Registration(receiver, caller);
                    receiver.linkToDeath(record.death, 0);
                    if (!receiver.isBinderAlive()) {
                        receiver.unlinkToDeath(record.death, 0);
                        throw new RemoteException("Receiver died during registration");
                    }
                    receivers.put(receiver, record);
                }
                long id = nextRegistration++;
                record.filters.add(new FilterRegistration(id, filter));
                result.putLong("registration", id);
            }
        } else if (operation == EngineProtocol.UNREGISTER_RECEIVER) {
            IBinder receiver = input.getBinder("receiver");
            Registration record;
            synchronized (receivers) {
                record = receivers.get(receiver);
                if (record != null) requireOwner(record, caller);
                if (record != null && input.containsKey("registration")) {
                    long id = input.getLong("registration");
                    record.filters.removeIf(filter -> filter.id == id);
                    if (!record.filters.isEmpty()) record = null;
                }
            }
            if (record != null) remove(record);
        } else if (operation == EngineProtocol.SEND_BROADCAST) {
            Intent intent = input.getParcelable("intent");
            if (intent == null || intent.getAction() == null || intent.getComponent() != null) {
                throw new EngineException("Only action-based registered broadcasts are implemented");
            }
            if (intent.getPackage() != null && !caller.packageName.equals(intent.getPackage())) {
                throw new SecurityException("Cross-package broadcast permissions are not implemented");
            }
            Intent scoped = new Intent(intent).setPackage(caller.packageName);
            if (!ManifestIntents.query(engine, scoped, input.getString("resolved_type"),
                    PackageManager.GET_RECEIVERS, 0).isEmpty()) {
                throw new EngineException("Manifest receiver delivery is not implemented");
            }
            enqueue(scoped, input.getString("resolved_type"), caller.appId, caller.packageName);
        } else throw new EngineException("Unknown broadcast operation");
        return result;
    }

    private static void requireOwner(Registration registration, CallerIdentity caller) {
        if (registration.owner.pid != caller.pid || !registration.owner.packageName.equals(caller.packageName)) {
            throw new SecurityException("Receiver belongs to another guest process");
        }
    }

    private void remove(Registration registration) {
        synchronized (receivers) {
            if (receivers.get(registration.receiver) != registration) return;
            receivers.remove(registration.receiver);
        }
        registration.receiver.unlinkToDeath(registration.death, 0);
    }

    private void enqueue(Intent intent, String resolvedType, int senderUid, String senderPackage) {
        List<Registration> targets = new ArrayList<>();
        synchronized (receivers) {
            for (Registration record : receivers.values()) {
                if (!record.owner.packageName.equals(intent.getPackage())) continue;
                for (FilterRegistration registration : record.filters) {
                    if (registration.filter.match(intent.getAction(), resolvedType, intent.getScheme(), intent.getData(),
                            intent.getCategories(), "MyboxEngine") >= 0) {
                        targets.add(record);
                        break;
                    }
                }
            }
        }
        delivery.execute(() -> {
            for (Registration target : targets) {
                synchronized (receivers) { if (receivers.get(target.receiver) != target) continue; }
                if (!target.receiver.isBinderAlive() || !target.client.isBinderAlive()) { remove(target); continue; }
                Bundle command = EngineProtocol.response();
                command.putBinder("receiver", target.receiver);
                command.putParcelable("intent", new Intent(intent));
                command.putInt("sender_uid", senderUid);
                command.putString("sender_package", senderPackage);
                try { EngineProtocol.request(target.client, EngineProtocol.GUEST, EngineProtocol.DELIVER_BROADCAST, command); }
                catch (EngineException failure) {
                    android.util.Log.w("MyboxEngine", "Registered broadcast dispatch failed", failure);
                    if (!target.client.isBinderAlive()) remove(target);
                }
            }
        });
    }

    /** Called on the PendingIntent queue, never under a registry lock or on an app main thread. */
    void sendPending(Intent original, String resolvedType, CallerIdentity creator) throws Exception {
        Intent intent = new Intent(original).setPackage(creator.packageName);
        if (intent.getComponent() == null) {
            List<Registration> targets = new ArrayList<>();
            synchronized (receivers) {
                for (Registration record : receivers.values()) {
                    if (!record.owner.packageName.equals(creator.packageName)) continue;
                    for (FilterRegistration registration : record.filters) {
                        if (registration.filter.match(intent.getAction(), resolvedType, intent.getScheme(), intent.getData(),
                                intent.getCategories(), "MyboxEngine") >= 0) { targets.add(record); break; }
                    }
                }
            }
            for (Registration target : targets) {
                synchronized (receivers) { if (receivers.get(target.receiver) != target) continue; }
                Bundle command = receiverCommand(intent, creator);
                command.putBinder("receiver", target.receiver);
                deliverAndWait(target.client, target.owner.pid, creator.packageName, command);
            }
        }
        List<ActivityInfo> targets = new ArrayList<>();
        if (intent.getComponent() != null) {
            PackageInfo pkg = engine.packages().getPackageInfo(creator.packageName, PackageManager.GET_RECEIVERS);
            if (pkg != null && pkg.receivers != null) for (ActivityInfo receiver : pkg.receivers) {
                if (receiver.name.equals(intent.getComponent().getClassName()) && receiver.enabled && receiver.applicationInfo.enabled) targets.add(receiver);
            }
            if (targets.isEmpty()) throw new EngineException("PendingIntent receiver is absent or disabled");
        } else {
            for (ResolveInfo match : ManifestIntents.query(engine, intent, resolvedType, PackageManager.GET_RECEIVERS, 0)) targets.add(match.activityInfo);
        }
        for (ActivityInfo target : targets) {
            Bundle request = EngineProtocol.response();
            request.putString("package", target.packageName);
            request.putString("process", target.processName == null ? target.packageName : target.processName);
            Bundle process = processes.acquire(request);
            Bundle command = receiverCommand(intent, creator);
            command.putParcelable("component", new ComponentName(target.packageName, target.name));
            deliverAndWait(process.getBinder("client"), process.getInt("pid"), target.packageName, command);
        }
    }

    private static Bundle receiverCommand(Intent intent, CallerIdentity creator) {
        Bundle command = EngineProtocol.response();
        command.putParcelable("intent", new Intent(intent));
        command.putInt("sender_uid", creator.appId);
        command.putString("sender_package", creator.packageName);
        return command;
    }

    private static void deliverAndWait(IBinder client, int pid, String packageName, Bundle command) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        String[] failure = new String[1];
        Binder completion = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
                data.enforceInterface(GuestReceiverDelivery.COMPLETION);
                CallerIdentity caller = EngineServerProvider.resolveCaller();
                if (caller.pid != pid || !caller.packageName.equals(packageName)) throw new SecurityException("Receiver completion belongs to another process");
                Bundle result = data.readBundle(getClass().getClassLoader());
                if (result == null || result.getInt("protocol") != EngineProtocol.VERSION) throw new IllegalArgumentException("Invalid receiver completion");
                synchronized (failure) {
                    if (completed.getCount() != 0) { failure[0] = result.getString("failure"); completed.countDown(); }
                }
                reply.writeNoException();
                reply.writeBundle(EngineProtocol.response());
                return true;
            }
        };
        IBinder.DeathRecipient death = () -> {
            synchronized (failure) { if (completed.getCount() != 0) { failure[0] = "Receiver process died"; completed.countDown(); } }
        };
        client.linkToDeath(death, 0);
        try {
            command.putBinder("completion", completion);
            EngineProtocol.request(client, EngineProtocol.GUEST, EngineProtocol.DELIVER_RECEIVER, command);
            if (!completed.await(11, TimeUnit.SECONDS)) throw new EngineException("Receiver completion timed out");
            if (failure[0] != null) throw new EngineException(failure[0]);
        } finally { client.unlinkToDeath(death, 0); }
    }

    @Override public void notifyAccountsChanged(int userId, Set<String> packages) {
        if (userId != 0) throw new SecurityException("Unknown virtual account user");
        for (String packageName : packages) {
            enqueue(new Intent(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION).setPackage(packageName), null, 1000, "android");
            enqueue(new Intent(VISIBLE_ACCOUNTS_CHANGED).setPackage(packageName), null, 1000, "android");
        }
    }
}
