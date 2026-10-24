package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.os.IInterface;
import android.os.Looper;
import android.os.Process;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Eager startup and reentrant local acquisition share the same real framework installation. */
final class LocalProviders {
    private enum State { DECLARED, INITIALIZING, READY, FAILED }
    private static final class Entry {
        final ComponentName component;
        final ProviderInfo info;
        State state = State.DECLARED;
        Object holder;
        EngineException failure;
        Entry(ProviderInfo info) {
            this.info = info;
            component = new ComponentName(info.packageName, info.name);
        }
    }

    private final Context context;
    private final Object thread;
    private final String packageName, processName, packageGeneration;
    private final Map<ComponentName, Entry> components = new LinkedHashMap<>();
    private final Map<String, Entry> authorities = new LinkedHashMap<>();
    private final List<Entry> initializing = new ArrayList<>();

    LocalProviders(Context context, Object thread, String processName, List<ProviderInfo> declared) {
        this.context = context;
        this.thread = thread;
        packageName = context.getPackageName();
        this.processName = processName;
        packageGeneration = context.getApplicationInfo().sourceDir;
        for (ProviderInfo original : declared) {
            checkIdentity(original);
            if (original.authority == null || original.authority.isEmpty()) {
                throw new EngineException("Local Provider has no authority: " + original.name);
            }
            ProviderInfo info = new ProviderInfo(original);
            info.applicationInfo = new ApplicationInfo(original.applicationInfo);
            Entry entry = new Entry(info);
            if (components.put(entry.component, entry) != null) {
                throw new EngineException("Duplicate local Provider component: " + entry.component);
            }
            for (String authority : info.authority.split(";", -1)) {
                if (authority.isEmpty() || authorities.put(authority, entry) != null) {
                    throw new EngineException("Duplicate or empty local Provider authority: " + authority);
                }
            }
        }
    }

    void installAll() throws Exception {
        List<Entry> ordered = new ArrayList<>(components.values());
        ordered.sort((left, right) -> Integer.compare(right.info.initOrder, left.info.initOrder));
        for (Entry entry : ordered) ensureInstalled(entry);
    }

    Object acquire(ProviderInfo requested, String authority, int userId) throws Exception {
        if (userId != Process.myUid() / 100000) throw new SecurityException("Local Provider belongs to another Android user");
        checkIdentity(requested);
        Entry entry = authorities.get(authority);
        if (entry == null || !entry.component.equals(new ComponentName(requested.packageName, requested.name))) {
            throw new SecurityException("Authority does not belong to the bound local Provider: " + authority);
        }
        return ensureInstalled(entry);
    }

    private void checkIdentity(ProviderInfo info) {
        if (info == null || info.name == null || info.name.isEmpty() || info.applicationInfo == null
                || !info.enabled || !info.applicationInfo.enabled
                || !packageName.equals(info.packageName) || !packageName.equals(info.applicationInfo.packageName)
                || !processName.equals(info.processName == null ? packageName : info.processName)
                || packageGeneration == null || !packageGeneration.equals(info.applicationInfo.sourceDir)) {
            throw new SecurityException("Provider does not belong to the bound package/process/generation");
        }
    }

    private Object ensureInstalled(Entry entry) throws Exception {
        synchronized (this) {
            if (entry.state == State.FAILED) throw entry.failure;
            if (entry.state == State.READY) return entry.holder;
            // shortcut: reject pre-READY worker reads to avoid main/worker join deadlocks; add wait-cycle tracking before supporting them.
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new EngineException("Local Provider is not ready during main-thread initialization: " + entry.component);
            }
            if (entry.state == State.INITIALIZING) {
                StringBuilder chain = new StringBuilder();
                for (Entry active : initializing) chain.append(active.component.getClassName()).append(" -> ");
                entry.failure = new EngineException("Provider initialization cycle: " + chain + entry.component.getClassName());
                entry.state = State.FAILED;
                throw entry.failure;
            }
            entry.state = State.INITIALIZING;
            initializing.add(entry);
        }
        try {
            Object holder = existingHolder(entry);
            if (holder == null) {
                Class<?> holderType = Class.forName("android.app.ContentProviderHolder");
                holder = RuntimeReflection.invoke(thread, RuntimeReflection.method(thread.getClass(),
                        "installProvider", Context.class, holderType, ProviderInfo.class,
                        boolean.class, boolean.class, boolean.class), context, null, entry.info, false, true, true);
            }
            if (holder == null) throw new EngineException("Android did not install Provider " + entry.component);
            ProviderInfo installed = (ProviderInfo) RuntimeReflection.get(holder, "info");
            checkIdentity(installed);
            if (!entry.component.equals(new ComponentName(installed.packageName, installed.name))) {
                throw new EngineException("Android returned a different local Provider");
            }
            IInterface provider = (IInterface) RuntimeReflection.get(holder, "provider");
            if (provider == null || !provider.asBinder().isBinderAlive()) {
                throw new EngineException("Android returned no live local Provider " + entry.component);
            }
            synchronized (this) {
                // A provider may catch a recursive lookup exception itself. The cycle still
                // invalidates this binding; it cannot turn a partial initialization into READY.
                if (entry.state == State.FAILED) throw entry.failure;
                entry.holder = holder;
                entry.state = State.READY;
                return holder;
            }
        } catch (Throwable failure) {
            synchronized (this) {
                if (entry.failure == null) entry.failure = new EngineException(
                        "Local Provider initialization failed: " + entry.component, failure);
                entry.state = State.FAILED;
                throw entry.failure;
            }
        } finally {
            synchronized (this) { initializing.remove(entry); }
        }
    }

    private Object existingHolder(Entry entry) throws Exception {
        Object lock = RuntimeReflection.get(thread, "mProviderMap");
        Map<?, ?> local = (Map<?, ?>) RuntimeReflection.get(thread, "mLocalProvidersByName");
        synchronized (lock) {
            Object record = local.get(entry.component);
            return record == null ? null : RuntimeReflection.get(record, "mHolder");
        }
    }
}
