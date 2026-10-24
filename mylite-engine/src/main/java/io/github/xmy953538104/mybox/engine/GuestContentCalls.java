package io.github.xmy953538104.mybox.engine;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/** Separates virtual observer operations from the physical content service. */
final class GuestContentCalls implements InvocationHandler {
    private final Engine engine;
    private final String packageName;
    private final Object original;
    private final IBinder endpoint;
    private IBinder local;

    private GuestContentCalls(Engine engine, GuestRuntime runtime, Object original, IBinder endpoint) {
        this.engine = engine; packageName = runtime.packageInfo().packageName; this.original = original; this.endpoint = endpoint;
    }

    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        Bundle request = EngineProtocol.response(); request.putString("service", "content_observers");
        IBinder endpoint = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.GET_SERVICE, request).getBinder("service");
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(ContentResolver.class, "getContentService"));
        if (original == null || endpoint == null) throw new EngineException("Content service is unavailable");
        Class<?> contract = Class.forName("android.content.IContentService");
        GuestContentCalls calls = new GuestContentCalls(engine, runtime, original, endpoint);
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw virtual content service transactions are not supported");
            }
        };
        local.attachInterface(proxy, "android.content.IContentService"); calls.local = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put("content", local);
        RuntimeReflection.field(ContentResolver.class, "sContentService").set(null, proxy);
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteContentService";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        if (name.equals("registerContentObserver")) {
            if (args == null || args.length != 5 || !(args[0] instanceof Uri)) throw new UnsupportedOperationException("Unknown observer registration signature");
            Uri uri = (Uri) args[0];
            if (virtual(uri)) {
                Bundle input = request("register", ((Number) args[3]).intValue());
                input.putParcelable("uri", uri); input.putBoolean("descendants", (Boolean) args[1]);
                input.putBinder("observer", observer(args[2])); call(input); return null;
            }
        } else if (name.equals("unregisterContentObserver")) {
            if (args == null || args.length != 1) throw new UnsupportedOperationException("Unknown observer removal signature");
            Bundle input = request("unregister", 0); input.putBinder("observer", observer(args[0])); call(input);
            // The same transport may also have physical subscriptions.
        } else if (name.equals("notifyChange")) {
            if (args == null || args.length < 6 || args.length > 7) throw new UnsupportedOperationException("Unknown content notification signature");
            ArrayList<Uri> uris = args[0] instanceof Uri ? new ArrayList<>(java.util.Collections.singletonList((Uri) args[0]))
                    : args[0] instanceof Uri[] ? new ArrayList<>(Arrays.asList((Uri[]) args[0])) : null;
            if (uris == null || uris.isEmpty() || uris.size() > 64) throw new IllegalArgumentException("Invalid content notification batch");
            int count = 0;
            for (Uri uri : uris) if (virtual(uri)) count++;
            if (count != 0) {
                if (count != uris.size()) throw new UnsupportedOperationException("Mixed physical and virtual content notification batches are not implemented");
                Bundle input = request("notify", ((Number) args[4]).intValue());
                input.putParcelableArrayList("uris", uris); input.putBinder("observer", observer(args[1]));
                input.putBoolean("self", (Boolean) args[2]);
                input.putInt("flags", args[3] instanceof Boolean ? ((Boolean) args[3] ? 1 : 0) : ((Number) args[3]).intValue());
                if (args.length == 7 && !packageName.equals(args[6])) throw new SecurityException("Content notification package differs from its guest");
                call(input); return null;
            }
            if (args.length == 7 && packageName.equals(args[6])) { args = args.clone(); args[6] = engine.hostContext().getPackageName(); }
        } else if (args != null) {
            for (Object argument : args) {
                if (argument instanceof Uri && virtual((Uri) argument)
                        || argument instanceof String && !((String) argument).isEmpty()
                        && ((String) argument).indexOf('@') < 0 && VirtualProviders.find(engine, (String) argument) != null) {
                    throw new UnsupportedOperationException("Virtual content sync and this content-service operation are not implemented: " + name);
                }
            }
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private boolean virtual(Uri uri) throws Exception {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalArgumentException("Invalid content URI");
        if (VirtualProviders.find(engine, uri.getAuthority()) != null) return true;
        if (uri.getAuthority().startsWith("com.google.android.gsf") || uri.getAuthority().startsWith("com.google.android.gms")
                || uri.getAuthority().startsWith("com.android.vending") || uri.getAuthority().equals("com.google.settings")) {
            throw new SecurityException("Google content cannot fall back to a physical Provider");
        }
        return false;
    }
    private Bundle request(String operation, int user) {
        if (user != 0) throw new SecurityException("Virtual content supports only user zero");
        Bundle result = EngineProtocol.response(); result.putString("package", packageName);
        result.putString("operation", operation); result.putInt("user", user); return result;
    }
    private static IBinder observer(Object value) {
        if (value == null) return null;
        if (!(value instanceof IInterface)) throw new IllegalArgumentException("Invalid content observer");
        return ((IInterface) value).asBinder();
    }
    private void call(Bundle input) { EngineProtocol.request(endpoint, ServerContentObservers.DESCRIPTOR, FIRST, input); }
    private static final int FIRST = IBinder.FIRST_CALL_TRANSACTION;
}
