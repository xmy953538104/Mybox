package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Only the physical EditorInfo attribution changes; window tokens and input connections stay real. */
final class GuestInputMethodCalls implements InvocationHandler {
    private final Object original;
    private final String host, guest;
    private IBinder local;
    GuestInputMethodCalls(Object original, String host, String guest) {
        this.original = original; this.host = host; this.guest = guest;
    }
    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        InputMethodManager manager = engine.hostContext().getSystemService(InputMethodManager.class);
        Class<?> contract = Class.forName("com.android.internal.view.IInputMethodManager");
        Object original;
        java.lang.reflect.Field global = null;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            Class<?> invoker = Class.forName("android.view.inputmethod.IInputMethodManagerGlobalInvoker");
            original = RuntimeReflection.invoke(null, RuntimeReflection.method(invoker, "getService"));
            global = RuntimeReflection.field(invoker, "sServiceCache");
        } else original = RuntimeReflection.get(manager, "mService");
        GuestInputMethodCalls calls = new GuestInputMethodCalls(original, engine.hostContext().getPackageName(), runtime.packageInfo().packageName);
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw guest input method transactions are not adapted");
            }
        };
        local.attachInterface(proxy, contract.getName()); calls.local = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put(Context.INPUT_METHOD_SERVICE, local);
        if (global != null) global.set(null, proxy);
        else {
            RuntimeReflection.set(manager, "mService", proxy);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.util.SparseArray<?> instances = (android.util.SparseArray<?>) RuntimeReflection.field(InputMethodManager.class, "sInstanceMap").get(null);
                synchronized (instances) { for (int i = 0; i < instances.size(); i++) RuntimeReflection.set(instances.valueAt(i), "mService", proxy); }
            }
        }
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteInputMethodManager";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        if (name.equals("startInputOrWindowGainedFocus") || name.equals("startInputOrWindowGainedFocusAsync")) {
            if (Binder.getCallingPid() != android.os.Process.myPid() || Binder.getCallingUid() != android.os.Process.myUid()) {
                throw new SecurityException("IME attribution requires a local guest call");
            }
            Class<?>[] types = method.getParameterTypes();
            if (args == null || types.length <= 6 || types[6] != EditorInfo.class) throw new UnsupportedOperationException("Unknown IME start signature");
            EditorInfo editor = (EditorInfo) args[6];
            if (editor != null) {
                if (!guest.equals(editor.packageName) && !host.equals(editor.packageName)) throw new SecurityException("Editor does not belong to the bound guest");
                Parcel parcel = Parcel.obtain(); EditorInfo physical;
                try { editor.writeToParcel(parcel, 0); parcel.setDataPosition(0); physical = EditorInfo.CREATOR.createFromParcel(parcel); }
                finally { parcel.recycle(); }
                physical.packageName = host;
                args = args.clone(); args[6] = physical;
            }
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
