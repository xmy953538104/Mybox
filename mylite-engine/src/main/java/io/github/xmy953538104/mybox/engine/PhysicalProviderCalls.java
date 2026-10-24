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
        Class<?> contract = Class.forName("android.content.IContentProvider");
        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                new PhysicalProviderCalls(host, guestPackage, provider));
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
            // AOSP's Builder(source).build() resets token/PID/renounced permissions and drops
            // the next source. withPackageName instead copies the complete platform state.
            AttributionSource copy = (AttributionSource) RuntimeReflection.invoke(source,
                    RuntimeReflection.method(AttributionSource.class, "withPackageName", String.class),
                    host.getPackageName());
            Object copiedState = RuntimeReflection.get(copy, "mAttributionSourceState");
            if (copy == source || copiedState == RuntimeReflection.get(source, "mAttributionSourceState")) {
                throw new EngineException("Android did not isolate the provider attribution copy");
            }
            // A guest tag is not declared in the physical host manifest. Change only this new
            // state's tag; the original object and its immutable downstream chain stay intact.
            RuntimeReflection.set(copiedState, "attributionTag", host.getAttributionTag());
            return copy;
        }
    }
}
