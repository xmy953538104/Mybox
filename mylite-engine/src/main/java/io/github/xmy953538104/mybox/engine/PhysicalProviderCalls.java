package io.github.xmy953538104.mybox.engine;

import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Build;
import android.os.Process;
import android.os.Bundle;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Uses the physical host identity only at the boundary to a physical provider. */
final class PhysicalProviderCalls implements InvocationHandler {
    private final Context host;
    private final String guestPackage;
    private final Object original;
    private final boolean settings;

    private PhysicalProviderCalls(Context host, String guestPackage, Object original, boolean settings) {
        this.host = host;
        this.guestPackage = guestPackage;
        this.original = original;
        this.settings = settings;
    }

    static Object wrap(Context host, String guestPackage, Object provider) throws ClassNotFoundException {
        return wrap(host, guestPackage, provider, false);
    }

    static Object wrap(Context host, String guestPackage, Object provider, boolean settings) throws ClassNotFoundException {
        if (provider == null) return null;
        if (Proxy.isProxyClass(provider.getClass()) && Proxy.getInvocationHandler(provider) instanceof PhysicalProviderCalls) {
            PhysicalProviderCalls current = (PhysicalProviderCalls) Proxy.getInvocationHandler(provider);
            if (!guestPackage.equals(current.guestPackage) || !host.getPackageName().equals(current.host.getPackageName())) {
                throw new SecurityException("Physical provider belongs to another bound identity");
            }
            if (!settings || current.settings) return provider;
            provider = current.original;
        }
        Class<?> contract = Class.forName("android.content.IContentProvider");
        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                new PhysicalProviderCalls(host, guestPackage, provider, settings));
    }

    static void installCachedSettings(Context host, String guestPackage) throws Exception {
        Map<IBinder, Object> wrappers = new HashMap<>();
        Object thread = RuntimeReflection.activityThread();
        Map<?, ?> records = (Map<?, ?>) RuntimeReflection.get(thread, "mProviderMap");
        Map<?, ?> references = (Map<?, ?>) RuntimeReflection.get(thread, "mProviderRefCountMap");
        // acquireExistingProvider returns this alias without calling the ActivityManager hook.
        synchronized (records) {
            Map<Object, Boolean> seen = new IdentityHashMap<>();
            for (Object record : records.values()) {
                if (seen.put(record, Boolean.TRUE) != null) continue;
                Object holder = RuntimeReflection.get(record, "mHolder");
                if (holder == null || RuntimeReflection.get(record, "mLocalProvider") != null) continue;
                ProviderInfo info = (ProviderInfo) RuntimeReflection.get(holder, "info");
                if (info == null || !hasSettingsAuthority(info.authority)) continue;
                Object provider = RuntimeReflection.get(record, "mProvider");
                requireSameBinder(provider, RuntimeReflection.get(holder, "provider"));
                Object wrapped = settingsProvider(host, guestPackage, provider, wrappers);
                RuntimeReflection.set(record, "mProvider", wrapped);
                RuntimeReflection.set(holder, "provider", wrapped);
                Object reference = references.get(((IInterface) provider).asBinder());
                if (reference != null) {
                    Object referenceHolder = RuntimeReflection.get(reference, "holder");
                    requireSameBinder(provider, RuntimeReflection.get(referenceHolder, "provider"));
                    RuntimeReflection.set(referenceHolder, "provider", wrapped);
                }
            }
        }
        String[] scopes = Build.VERSION.SDK_INT >= 29
                ? new String[]{"System", "Secure", "Global", "Config"}
                : new String[]{"System", "Secure", "Global"};
        for (String scope : scopes) {
            Object cache = RuntimeReflection.field(Class.forName("android.provider.Settings$" + scope), "sNameValueCache").get(null);
            if (scope.equals("Secure") || scope.equals("Global")) {
                // Close pre-bind physical generation arrays under the framework's own cache lock.
                RuntimeReflection.invoke(cache, RuntimeReflection.method(cache.getClass(), "clearGenerationTrackerForTest"));
            }
            Object holder = RuntimeReflection.get(cache, "mProviderHolder");
            if (!"settings".equals(((android.net.Uri) RuntimeReflection.get(holder, "mUri")).getAuthority()))
                throw new SecurityException("Settings cache is not the physical settings authority");
            // Settings' own getter uses mLock, not the holder object. The scope's static
            // sProviderHolder is the same object, including direct reset/config calls.
            synchronized (RuntimeReflection.get(holder, "mLock")) {
                Object provider = RuntimeReflection.get(holder, "mContentProvider");
                if (provider != null) RuntimeReflection.set(holder, "mContentProvider",
                        settingsProvider(host, guestPackage, provider, wrappers));
            }
        }
    }

    static boolean hasSettingsAuthority(String authorities) {
        if (authorities != null) for (String authority : authorities.split(";"))
            if ("settings".equals(authority)) return true;
        return false;
    }

    private static void requireSameBinder(Object first, Object second) {
        if (!(first instanceof IInterface) || !(second instanceof IInterface)
                || ((IInterface) first).asBinder() == null
                || !((IInterface) first).asBinder().equals(((IInterface) second).asBinder()))
            throw new SecurityException("Settings provider aliases disagree");
    }

    private static Object settingsProvider(Context host, String guestPackage, Object provider,
                                           Map<IBinder, Object> wrappers) throws ClassNotFoundException {
        IBinder binder = ((IInterface) provider).asBinder();
        if (binder == null) throw new SecurityException("Settings provider has no OS Binder");
        // Validate an existing adapter even if another alias has already supplied this Binder.
        Object wrapped = wrap(host, guestPackage, provider, true);
        Object existing = wrappers.get(binder);
        if (existing != null) return existing;
        wrappers.put(binder, wrapped);
        return wrapped;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args != null && args.length > 0) {
            if (Build.VERSION.SDK_INT >= 31 && method.getParameterTypes()[0].getName()
                    .equals("android.content.AttributionSource")) {
                args = args.clone();
                args[0] = Api31.physicalSource(host, guestPackage, args[0]);
            } else if (args[0] instanceof String && hasCallingPackage(method.getName())) {
                if (guestPackage.equals(args[0])) {
                    args = args.clone();
                    args[0] = host.getPackageName();
                }
            }
        }
        if (settings && method.getName().equals("call") && args != null && args.length >= 4) {
            GuestRuntime runtime = Engine.get().runtime();
            if (runtime != null && runtime.packageInfo() != null && guestPackage.equals(runtime.packageInfo().packageName)) {
                int index = args.length - 3;
                String command = args[index] instanceof String ? (String) args[index] : "";
                if (command.equals("GET_secure") || command.equals("GET_global")
                        || command.equals("PUT_secure") || command.equals("PUT_global")) {
                    Bundle extras = args[index + 2] == null ? new Bundle() : new Bundle((Bundle) args[index + 2]);
                    if (extras.getInt("_user", Process.myUid() / 100000) != Process.myUid() / 100000)
                        throw new SecurityException("Settings call targets another physical user");
                    boolean write = command.startsWith("PUT_");
                    Bundle input = EngineProtocol.response();
                    input.putInt("user", 0); input.putString("package", guestPackage);
                    input.putString("operation", write ? "put" : "get");
                    input.putString("domain", command.substring(4)); input.putString("name", (String) args[index + 1]);
                    if (write) input.putString("value", extras.getString("value"));
                    Bundle response = EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.SETTINGS, input);
                    if (write) return new Bundle();
                    if (response.getBoolean("present")) {
                        Bundle value = new Bundle(); value.putString("value", response.getString("value")); return value;
                    }
                    // Physical generations do not change when a virtual writer commits in another VM.
                    extras.remove("_track_generation"); args = args.clone(); args[index + 2] = extras;
                }
            }
        }
        // In particular, asBinder() returns the real provider Binder. Framework liveness and
        // death handling must observe the original process, not a local Binder wrapper.
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static boolean hasCallingPackage(String method) {
        switch (method) {
            case "query": case "insert": case "bulkInsert": case "delete": case "update":
            case "openFile": case "openAssetFile": case "openTypedAssetFile": case "applyBatch":
            case "call": case "canonicalize": case "uncanonicalize": case "refresh":
            case "checkUriPermission":
                return true;
            default:
                return false;
        }
    }

    private static final class Api31 {
        static AttributionSource physicalSource(Context host, String guestPackage, Object value) throws Exception {
            if (!(value instanceof AttributionSource)) throw new SecurityException("Missing provider attribution");
            AttributionSource source = (AttributionSource) value;
            if (source.getUid() != Process.myUid()) {
                throw new SecurityException("Foreign provider attribution UID");
            }
            if (host.getPackageName().equals(source.getPackageName())) return source;
            if (!guestPackage.equals(source.getPackageName())) {
                throw new SecurityException("Provider attribution is not the bound guest");
            }
            // Copy only the generated state: AttributionSource's Parcel constructor requires Binder dispatch.
            android.os.Parcel parcel = android.os.Parcel.obtain();
            AttributionSource copy;
            Object originalState = RuntimeReflection.get(source, "mAttributionSourceState");
            try {
                ((android.os.Parcelable) originalState).writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                android.os.Parcelable.Creator<?> creator = (android.os.Parcelable.Creator<?>)
                        RuntimeReflection.field(originalState.getClass(), "CREATOR").get(null);
                Object state = creator.createFromParcel(parcel);
                copy = AttributionSource.class.getConstructor(originalState.getClass()).newInstance(state);
            } finally { parcel.recycle(); }
            Object copiedState = RuntimeReflection.get(copy, "mAttributionSourceState");
            if (copy == source || copiedState == RuntimeReflection.get(source, "mAttributionSourceState")) {
                throw new EngineException("Android did not isolate the provider attribution copy");
            }
            RuntimeReflection.set(copiedState, "packageName", host.getPackageName());
            // A guest tag is not declared in the physical host manifest. Change only this new
            // state's tag; the original object and its immutable downstream chain stay intact.
            RuntimeReflection.set(copiedState, "attributionTag", host.getAttributionTag());
            return copy;
        }
    }
}
