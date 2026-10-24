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
            if (intent == null || intent.getAction() == null && intent.getComponent() == null) {
                throw new EngineException("A broadcast action or component is required");
            }
            if (intent.getPackage() != null && !caller.packageName.equals(intent.getPackage())
                    || intent.getComponent() != null && !caller.packageName.equals(intent.getComponent().getPackageName())) {
                throw new SecurityException("Cross-package broadcast permissions are not implemented");
            }
            Intent scoped = new Intent(intent).setPackage(caller.packageName);
            // Resolve before accepting; execution remains asynchronous like Android broadcasts.
            List<Target> targets = targets(scoped, input.getString("resolved_type"), caller.packageName);
            IBinder finalReceiver = input.getBinder("result_to");
            IBinder senderClient = finalReceiver == null ? null : processes.clientForCaller(caller);
            Bundle initial = new Bundle(input);
            delivery.execute(() -> {
                try {
                    Bundle state = dispatch(scoped, targets, caller, initial);
                    if (finalReceiver != null) {
                        Bundle command = receiverCommand(scoped, caller);
                        command.putAll(state);
                        command.putBoolean("ordered", input.getBoolean("ordered"));
                        command.putBinder("receiver", finalReceiver);
                        deliverAndWait(senderClient, caller.pid, caller.packageName, command);
                    }
                } catch (Exception failure) { android.util.Log.w("MyboxEngine", "Broadcast execution failed", failure); }
            });
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
        dispatch(intent, targets(intent, resolvedType, creator.packageName), creator, EngineProtocol.response());
    }

    private static final class Target {
        final Registration registered;
        final ActivityInfo manifest;
        final int priority;
        final long order;
        Target(Registration registered, ActivityInfo manifest, int priority, long order) {
            this.registered = registered; this.manifest = manifest; this.priority = priority; this.order = order;
        }
    }

    private List<Target> targets(Intent intent, String resolvedType, String packageName) throws Exception {
        List<Target> targets = new ArrayList<>();
        if (intent.getComponent() == null) synchronized (receivers) {
            for (Registration record : receivers.values()) {
                if (!record.owner.packageName.equals(packageName)) continue;
                FilterRegistration best = null;
                for (FilterRegistration filter : record.filters) {
                    if (filter.filter.match(intent.getAction(), resolvedType, intent.getScheme(), intent.getData(),
                            intent.getCategories(), "MyboxEngine") >= 0
                            && (best == null || filter.filter.getPriority() > best.filter.getPriority())) best = filter;
                }
                if (best != null) targets.add(new Target(record, null, best.filter.getPriority(), best.id));
            }
        }
        if ((intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) != 0) {
            // Android must not start a manifest component for a registered-only broadcast.
        } else if (intent.getComponent() != null) {
            PackageInfo pkg = engine.packages().getPackageInfo(packageName, PackageManager.GET_RECEIVERS);
            if (pkg != null && pkg.receivers != null) for (ActivityInfo receiver : pkg.receivers) {
                if (receiver.name.equals(intent.getComponent().getClassName()) && receiver.enabled && receiver.applicationInfo.enabled) {
                    targets.add(new Target(null, receiver, 0, Long.MAX_VALUE));
                }
            }
            if (targets.isEmpty()) throw new EngineException("Broadcast receiver is absent or disabled");
        } else {
            for (ResolveInfo match : ManifestIntents.query(engine, intent, resolvedType, PackageManager.GET_RECEIVERS, 0)) {
                targets.add(new Target(null, match.activityInfo, match.priority, Long.MAX_VALUE));
            }
        }
        targets.sort(java.util.Comparator.<Target>comparingInt(target -> target.priority).reversed()
                .thenComparingLong(target -> target.order));
        return targets;
    }

    private Bundle dispatch(Intent intent, List<Target> targets, CallerIdentity creator, Bundle initial) throws Exception {
        boolean ordered = initial.getBoolean("ordered");
        Bundle state = EngineProtocol.response();
        state.putInt("result_code", initial.getInt("result_code"));
        state.putString("result_data", initial.getString("result_data"));
        state.putBundle("result_extras", initial.getBundle("result_extras"));
        for (Target target : targets) {
            Bundle command = receiverCommand(intent, creator);
            command.putAll(state);
            command.putBoolean("ordered", ordered);
            Bundle received;
            if (target.registered != null) {
                Registration record = target.registered;
                synchronized (receivers) { if (receivers.get(record.receiver) != record) continue; }
                command.putBinder("receiver", record.receiver);
                received = deliverAndWait(record.client, record.owner.pid, creator.packageName, command);
            } else {
                ActivityInfo info = target.manifest;
                Bundle request = EngineProtocol.response();
                request.putString("package", info.packageName);
                request.putString("process", info.processName == null ? info.packageName : info.processName);
                Bundle process = processes.acquire(request);
                command.putParcelable("component", new ComponentName(info.packageName, info.name));
                received = deliverAndWait(process.getBinder("client"), process.getInt("pid"), info.packageName, command);
            }
            if (ordered) {
                state = received;
                if (state.getBoolean("abort") && (intent.getFlags() & Intent.FLAG_RECEIVER_NO_ABORT) == 0) break;
            }
        }
        return state;
    }

    private static Bundle receiverCommand(Intent intent, CallerIdentity creator) {
        Bundle command = EngineProtocol.response();
        command.putParcelable("intent", new Intent(intent));
        command.putInt("sender_uid", creator.appId);
        command.putString("sender_package", creator.packageName);
        return command;
    }

    private static Bundle deliverAndWait(IBinder client, int pid, String packageName, Bundle command) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        String[] failure = new String[1];
        Bundle[] returned = new Bundle[1];
        Binder completion = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
                data.enforceInterface(GuestReceiverDelivery.COMPLETION);
                CallerIdentity caller = EngineServerProvider.resolveCaller();
                if (caller.pid != pid || !caller.packageName.equals(packageName)) throw new SecurityException("Receiver completion belongs to another process");
                Bundle result = data.readBundle(getClass().getClassLoader());
                if (result == null || result.getInt("protocol") != EngineProtocol.VERSION) throw new IllegalArgumentException("Invalid receiver completion");
                synchronized (failure) {
                    if (completed.getCount() != 0) {
                        failure[0] = result.getString("failure"); returned[0] = new Bundle(result); completed.countDown();
                    }
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
            return returned[0];
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
