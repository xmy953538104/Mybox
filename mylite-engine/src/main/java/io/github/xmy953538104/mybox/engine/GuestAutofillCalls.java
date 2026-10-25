package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Real Android autofill sessions keep their actual token, callback, fields and result. */
final class GuestAutofillCalls implements InvocationHandler {
    interface TokenCheck { void require(IBinder token, ComponentName component) throws Exception; }
    private final Object original;
    private final String guest;
    private final ComponentName physical;
    private final TokenCheck check;
    GuestAutofillCalls(Object original, String guest, ComponentName physical, TokenCheck check) {
        this.original = original; this.guest = guest; this.physical = physical; this.check = check;
    }
    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        // shortcut: Autofill shapes verified for Android 14; verify other AIDL versions before enabling them.
        if (android.os.Build.VERSION.SDK_INT != 34) return;
        Object manager = engine.hostContext().getSystemService("autofill");
        if (manager == null) return;
        Object original = RuntimeReflection.get(manager, "mService");
        if (original == null) return;
        Class<?> contract = Class.forName("android.view.autofill.IAutoFillManager");
        GuestAutofillCalls calls = new GuestAutofillCalls(original, runtime.packageInfo().packageName,
                new ComponentName(engine.hostContext(), GuestActivity.class.getName() + "$P" + runtime.slot()),
                runtime.activities()::requireCurrentComponent);
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        IBinder backend = ((IInterface) original).asBinder();
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
                return backend.transact(code, data, reply, flags);
            }
        };
        local.attachInterface(proxy, contract.getName());
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put("autofill", local);
        RuntimeReflection.set(manager, "mService", proxy);
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("addClient") || name.equals("startSession") || name.equals("isServiceEnabled")) {
            if (Binder.getCallingUid() != android.os.Process.myUid() || Binder.getCallingPid() != android.os.Process.myPid()) {
                throw new SecurityException("Autofill attribution requires a local guest call");
            }
            boolean start = name.equals("startSession"), enabled = name.equals("isServiceEnabled");
            int count = start ? 11 : enabled ? 3 : 4, component = start ? 8 : 1, user = start ? 5 : enabled ? 0 : 2;
            Class<?>[] types = method.getParameterTypes();
            if (args == null || args.length != count || types.length != count || types[user] != int.class
                    || types[component] != (enabled ? String.class : ComponentName.class)
                    || start && types[0] != IBinder.class) throw new UnsupportedOperationException("Unknown Android 14 autofill identity shape");
            if (!Integer.valueOf(0).equals(args[user]) || android.os.Process.myUid() / 100000 != 0) {
                throw new SecurityException("Autofill currently requires physical and virtual user zero");
            }
            Object claimed = args[component];
            if (enabled ? !guest.equals(claimed) : !(claimed instanceof ComponentName) || !guest.equals(((ComponentName) claimed).getPackageName())) {
                throw new SecurityException("Autofill component is not the bound guest");
            }
            if (start) check.require((IBinder) args[0], (ComponentName) claimed);
            args = args.clone(); args[component] = enabled ? physical.getPackageName() : physical;
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
