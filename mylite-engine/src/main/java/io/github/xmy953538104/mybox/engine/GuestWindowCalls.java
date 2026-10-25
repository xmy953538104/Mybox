package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.Process;
import android.view.WindowManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Changes window attribution only; the physical session, tokens and window policy stay with Android. */
final class GuestWindowCalls implements InvocationHandler {
    private final Object original;
    private final String guest, host;
    private final Runnable requireLocalCaller;

    GuestWindowCalls(Object original, String guest, String host, Runnable requireLocalCaller) {
        this.original = original; this.guest = guest; this.host = host;
        this.requireLocalCaller = requireLocalCaller;
    }

    static void install(Engine engine, GuestRuntime runtime) throws Exception {
        Class<?> global = Class.forName("android.view.WindowManagerGlobal");
        synchronized (global) {
            Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(global, "getWindowSession"));
            if (original == null) throw new EngineException("Android window session is unavailable");
            String guest = runtime.packageInfo().packageName;
            String host = engine.hostContext().getPackageName();
            if (Proxy.isProxyClass(original.getClass()) && Proxy.getInvocationHandler(original) instanceof GuestWindowCalls) {
                GuestWindowCalls installed = (GuestWindowCalls) Proxy.getInvocationHandler(original);
                if (!guest.equals(installed.guest) || !host.equals(installed.host)) {
                    throw new SecurityException("Window session belongs to another guest");
                }
                return;
            }
            Class<?> contract = Class.forName("android.view.IWindowSession");
            GuestWindowCalls calls = new GuestWindowCalls(original, guest, host, () -> {
                if (Binder.getCallingUid() != Process.myUid() || Binder.getCallingPid() != Process.myPid()) {
                    throw new SecurityException("Window attribution requires a local guest call");
                }
            });
            Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
            RuntimeReflection.field(global, "sWindowSession").set(null, proxy);
            if (RuntimeReflection.field(global, "sWindowSession").get(null) != proxy) {
                throw new EngineException("Window session attribution was not installed");
            }
        }
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            if (method.getName().equals("toString")) return "MyliteWindowSession";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
        }
        int attributes = -1;
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) if (types[i] == WindowManager.LayoutParams.class) {
            if (attributes != -1) throw new UnsupportedOperationException("Ambiguous window attributes");
            attributes = i;
        }
        if (attributes >= 0) {
            String name = method.getName();
            if (!(name.equals("add") || name.equals("addToDisplay") || name.equals("addToDisplayAsUser")
                    || name.equals("addToDisplayWithoutInputChannel") || name.equals("addWithoutInputChannel")
                    || name.equals("relayout") || name.equals("relayoutAsync"))) {
                throw new UnsupportedOperationException("Window attribute entry point is not verified: " + name);
            }
            if (name.equals("relayoutAsync")) {
                // AOSP 14/15: oneway void with attrs at 1 and six unchanged integer parameters.
                if (attributes != 1 || types.length != 8 || method.getReturnType() != void.class) {
                    throw new UnsupportedOperationException("Unknown asynchronous window relayout signature");
                }
                for (int i = 2; i < types.length; i++) if (types[i] != int.class) {
                    throw new UnsupportedOperationException("Unknown asynchronous window relayout parameters");
                }
            }
            requireLocalCaller.run();
            if (args == null || args.length != types.length) throw new IllegalArgumentException("Incomplete window arguments");
            WindowManager.LayoutParams source = (WindowManager.LayoutParams) args[attributes];
            if (source != null) {
                if (source.packageName != null && !guest.equals(source.packageName) && !host.equals(source.packageName)) {
                    throw new SecurityException("Window package is not the bound guest");
                }
                WindowManager.LayoutParams physical = new WindowManager.LayoutParams();
                physical.copyFrom(source);
                physical.packageName = host;
                args = args.clone(); args[attributes] = physical;
            }
        }
        // asBinder returns the real session Binder; callbacks, out parameters and OS errors are untouched.
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
