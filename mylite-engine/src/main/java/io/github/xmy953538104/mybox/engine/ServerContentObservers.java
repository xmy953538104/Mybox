package io.github.xmy953538104.mybox.engine;

import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Container-local subscriptions; neither registration nor notification touches physical GSF. */
final class ServerContentObservers extends Binder {
    static final String DESCRIPTOR = "io.github.xmy953538104.mybox.engine.ContentObservers.v1";
    private final Engine engine;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final Method callback;

    private final class Entry implements IBinder.DeathRecipient {
        final ProcessRegistry.Identity owner;
        final IBinder observer;
        final Object target;
        final String provider;
        final List<String> path;
        final boolean descendants;
        boolean active = true;
        Entry(ProcessRegistry.Identity owner, IBinder observer, String provider, List<String> path, boolean descendants) throws Exception {
            this.owner = owner; this.observer = observer; this.provider = provider; this.path = path; this.descendants = descendants;
            target = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName("android.database.IContentObserver$Stub"),
                    "asInterface", IBinder.class), observer);
        }
        @Override public void binderDied() { remove(this); }
    }

    ServerContentObservers(Engine engine) throws Exception {
        this.engine = engine;
        Class<?> contract = Class.forName("android.database.IContentObserver");
        callback = Build.VERSION.SDK_INT >= 30
                ? contract.getMethod("onChangeEtc", boolean.class, Uri[].class, int.class, int.class)
                : contract.getMethod("onChange", boolean.class, Uri.class, int.class);
        attachInterface(null, DESCRIPTOR);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        if (code != FIRST_CALL_TRANSACTION) return false;
        EngineProtocol.requireCaller(); data.enforceInterface(DESCRIPTOR);
        Bundle result;
        try {
            Bundle input = data.readBundle(getClass().getClassLoader());
            if (input == null || input.getInt("protocol") != EngineProtocol.VERSION || input.getInt("user", -1) != 0) {
                throw new SecurityException("Invalid content observer protocol or user");
            }
            ProcessRegistry.Identity owner = EngineServerProvider.registeredProcess(Binder.getCallingUid(), Binder.getCallingPid());
            if (!owner.packageName.equals(input.getString("package"))) throw new SecurityException("Content observer caller differs from its process");
            String operation = input.getString("operation");
            IBinder observer = input.getBinder("observer");
            if ("register".equals(operation)) register(owner, observer, input.getParcelable("uri"), input.getBoolean("descendants"));
            else if ("unregister".equals(operation)) {
                if (observer == null) throw new IllegalArgumentException("Missing observer");
                synchronized (entries) {
                    for (Entry entry : new ArrayList<>(entries)) if (entry.owner.same(owner) && entry.observer.equals(observer)) remove(entry);
                }
            } else if ("notify".equals(operation)) notifyChanges(owner, input, observer);
            else throw new UnsupportedOperationException("Unknown virtual content observer operation");
            result = EngineProtocol.response();
        } catch (Exception failure) { result = EngineProtocol.failure(failure); }
        reply.writeNoException(); reply.writeBundle(result); return true;
    }

    private ProviderInfo provider(String caller, Uri uri) throws Exception {
        if (uri == null || !"content".equals(uri.getScheme()) || uri.toString().length() > 4096 || uri.getPathSegments().size() > 64) {
            throw new IllegalArgumentException("Invalid or oversized content URI");
        }
        ProviderInfo target = VirtualProviders.find(engine, uri.getAuthority());
        if (target == null) throw new SecurityException("Content URI has no virtual Provider");
        VirtualProviders.requireOpen(caller, target); return target;
    }

    private static List<String> path(Uri uri) {
        ArrayList<String> result = new ArrayList<>(); result.add(uri.getAuthority()); result.addAll(uri.getPathSegments()); return result;
    }

    private void register(ProcessRegistry.Identity owner, IBinder observer, Uri uri, boolean descendants) throws Exception {
        if (observer == null) throw new IllegalArgumentException("Missing observer");
        if (!"android.database.IContentObserver".equals(observer.getInterfaceDescriptor())) {
            throw new SecurityException("Observer Binder has another interface");
        }
        ProviderInfo provider = provider(owner.packageName, uri);
        Entry entry = new Entry(owner, observer, provider.packageName, path(uri), descendants);
        synchronized (entries) {
            int count = 0;
            for (Entry current : entries) if (current.owner.same(owner)) count++;
            if (entries.size() >= 256 || count >= 64) throw new IllegalStateException("Virtual content observer quota exceeded");
            observer.linkToDeath(entry, 0);
            try { owner.client.linkToDeath(entry, 0); }
            catch (Exception failure) { observer.unlinkToDeath(entry, 0); throw failure; }
            if (!observer.isBinderAlive() || !owner.client.isBinderAlive()) {
                observer.unlinkToDeath(entry, 0); owner.client.unlinkToDeath(entry, 0);
                throw new android.os.DeadObjectException();
            }
            entries.add(entry);
        }
    }

    private void remove(Entry entry) {
        synchronized (entries) {
            if (!entry.active) return;
            entry.active = false; entries.remove(entry);
            entry.observer.unlinkToDeath(entry, 0); entry.owner.client.unlinkToDeath(entry, 0);
        }
    }

    private void notifyChanges(ProcessRegistry.Identity owner, Bundle input, IBinder source) throws Exception {
        int flags = input.getInt("flags");
        EngineDiagnostics.event("content", "notify_flags", 0, flags);
        // Sync is a separate real operation; never claim that a notification scheduled it.
        if ((flags & ~30) != 0) throw new UnsupportedOperationException("Virtual sync requests and unknown notification flags are not implemented");
        ArrayList<Uri> uris = input.getParcelableArrayList("uris");
        if (uris == null || uris.isEmpty() || uris.size() > 64) throw new IllegalArgumentException("Invalid content notification batch");
        ArrayList<ProviderInfo> providers = new ArrayList<>();
        for (Uri uri : uris) providers.add(provider(owner.packageName, uri));
        Map<IBinder, ArrayList<Uri>> grouped = new LinkedHashMap<>();
        Map<IBinder, Entry> targets = new LinkedHashMap<>();
        synchronized (entries) {
            for (int index = 0; index < uris.size(); index++) {
                Uri uri = uris.get(index); List<String> changed = path(uri);
                for (Entry entry : entries) {
                    boolean self = entry.observer.equals(source);
                    if (self && !input.getBoolean("self")) continue;
                    if (!entry.provider.equals(providers.get(index).packageName)
                            || !ContentObserverPolicy.matches(entry.path, changed, entry.descendants, flags)) continue;
                    targets.put(entry.observer, entry);
                    grouped.computeIfAbsent(entry.observer, key -> new ArrayList<>()).add(uri);
                }
            }
        }
        for (Map.Entry<IBinder, ArrayList<Uri>> delivery : grouped.entrySet()) {
            Entry entry = targets.get(delivery.getKey());
            synchronized (entries) { if (!entry.active) continue; }
            try {
                boolean self = entry.observer.equals(source);
                if (Build.VERSION.SDK_INT >= 30) RuntimeReflection.invoke(entry.target, callback, self, delivery.getValue().toArray(new Uri[0]), flags, 0);
                else for (Uri uri : delivery.getValue()) RuntimeReflection.invoke(entry.target, callback, self, uri, 0);
            } catch (android.os.RemoteException dead) { remove(entry); }
        }
    }
}
