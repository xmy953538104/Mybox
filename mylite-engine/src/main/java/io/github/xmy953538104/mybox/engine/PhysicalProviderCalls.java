package io.github.xmy953538104.mybox.engine;

import android.content.AttributionSource;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Uses the physical host identity only at the boundary to a physical provider. */
final class PhysicalProviderCalls implements InvocationHandler {
    private final Context host;
    private final String guestPackage;
    private final Object original;

    private PhysicalProviderCalls(Context host, String guestPackage, Object original) {
        this.host = host;
        this.guestPackage = guestPackage;
        this.original = original;
    }

    static Object wrap(Context host, String guestPackage, Object provider) throws ClassNotFoundException {
        if (provider == null) return null;
        if (Proxy.isProxyClass(provider.getClass()) && Proxy.getInvocationHandler(provider) instanceof PhysicalProviderCalls) {
            PhysicalProviderCalls current = (PhysicalProviderCalls) Proxy.getInvocationHandler(provider);
            if (!guestPackage.equals(current.guestPackage) || !host.getPackageName().equals(current.host.getPackageName())) {
                throw new SecurityException("Physical provider belongs to another bound identity");
            }
            return provider;
        }
        Class<?> contract = Class.forName("android.content.IContentProvider");
        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                new PhysicalProviderCalls(host, guestPackage, provider));
    }

    static void installCachedSettings(Context host, String guestPackage) throws Exception {
        for (String scope : new String[]{"System", "Secure", "Global"}) {
            Object cache = RuntimeReflection.field(Class.forName("android.provider.Settings$" + scope), "sNameValueCache").get(null);
            Object holder = RuntimeReflection.get(cache, "mProviderHolder");
            if (!"settings".equals(((android.net.Uri) RuntimeReflection.get(holder, "mUri")).getAuthority())) {
                throw new SecurityException("Settings cache is not the physical settings authority");
            }
            synchronized (holder) {
                Object provider = RuntimeReflection.get(holder, "mContentProvider");
                if (provider != null) RuntimeReflection.set(holder, "mContentProvider", wrap(host, guestPackage, provider));
            }
        }
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
