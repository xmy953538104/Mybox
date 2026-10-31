package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/** Package attribution changes at the local API boundary; routes, callbacks and OS permissions stay real. */
final class GuestMediaRouterCalls implements InvocationHandler {
    private static final String DESCRIPTOR = "android.media.IMediaRouterService";
    private static GuestMediaRouterCalls installed;
    private final Object original;
    private final String guest, host;
    private final int hostUser;
    private final Runnable requireBoundProcess;
    private IBinder facade;

    GuestMediaRouterCalls(Object original, String guest, String host, int hostUser, Runnable requireBoundProcess) {
        this.original = original; this.guest = guest; this.host = host;
        this.hostUser = hostUser; this.requireBoundProcess = requireBoundProcess;
    }

    @SuppressWarnings("unchecked") static synchronized void install(Engine engine, GuestRuntime runtime) throws Exception {
        if (Build.VERSION.SDK_INT < 26 || Build.VERSION.SDK_INT > 35)
            throw new UnsupportedOperationException("MediaRouter Android version is not verified");
        Context context = engine.hostContext(); String guest = runtime.packageInfo().packageName, host = context.getPackageName();
        int uid = Process.myUid(), pid = Process.myPid();
        if (context.getApplicationInfo().uid != uid || !host.equals(context.getOpPackageName()))
            throw new SecurityException("MediaRouter host identity is not the physical app");
        if (installed != null) {
            if (!guest.equals(installed.guest) || !host.equals(installed.host))
                throw new SecurityException("MediaRouter guest identity is already sealed");
            return;
        }
        Class<?> manager = Class.forName("android.os.ServiceManager"), contract = Class.forName(DESCRIPTOR);
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(manager,"sCache").get(null);
        IBinder physical = (IBinder) RuntimeReflection.invoke(null, RuntimeReflection.method(manager,"getService",String.class),"media_router");
        if (physical == null || !DESCRIPTOR.equals(physical.getInterfaceDescriptor()))
            throw new EngineException("Physical MediaRouter service is unavailable");
        Object original = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName(DESCRIPTOR+"$Stub"),"asInterface",IBinder.class),physical);
        GuestMediaRouterCalls calls = new GuestMediaRouterCalls(original,guest,host,uid/100000,() -> {
            if (Process.myUid()!=uid || Process.myPid()!=pid) throw new SecurityException("MediaRouter process identity changed");
        });
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(DESCRIPTOR); return true; }
                throw new UnsupportedOperationException("Raw MediaRouter transactions cannot request attribution rewriting");
            }
        };
        local.attachInterface(proxy,DESCRIPTOR); calls.facade=local;
        synchronized (cache) { cache.put("media_router",local); }
        synchronized (Class.forName("android.media.MediaRouter$Static")) {
            replaceCachedRouter(RuntimeReflection.field(Class.forName("android.media.MediaRouter"),"sStatic").get(null),contract,proxy);
        }
        if (Build.VERSION.SDK_INT>=30) {
            Class<?> modern=Class.forName("android.media.MediaRouter2");
            synchronized (RuntimeReflection.field(modern,"sRouterLock").get(null)) {
                replaceCachedRouter(RuntimeReflection.field(modern,"sInstance").get(null),contract,proxy);
            }
            for (String field : new String[]{"sSystemMediaRouter2Map","sAppToProxyRouterMap"}) {
                Field entries;
                try { entries=RuntimeReflection.field(modern,field); }
                catch (NoSuchFieldException otherReviewedVersion) { continue; }
                synchronized (RuntimeReflection.field(modern,"sSystemRouterLock").get(null)) {
                    Object values=entries.get(null);
                    if (!(values instanceof Map)) throw new EngineException("Unknown cached MediaRouter2 map layout");
                    for (Object router:((Map<?,?>)values).values()) replaceCachedRouter(router,contract,proxy);
                }
            }
            Class<?> managers=Class.forName("android.media.MediaRouter2Manager");
            synchronized (RuntimeReflection.field(managers,"sLock").get(null)) {
                replaceCachedRouter(RuntimeReflection.field(managers,"sInstance").get(null),contract,proxy);
            }
        }
        if (RuntimeReflection.invoke(null,RuntimeReflection.method(manager,"getService",String.class),"media_router")!=local)
            throw new EngineException("MediaRouter service mapping did not stick");
        installed=calls;
    }

    private static void replaceCachedRouter(Object router, Class<?> contract, Object proxy) throws Exception {
        if (router==null) return;
        Object service=RuntimeReflection.get(router,"mMediaRouterService");
        // Android 14 system routers use the shared manager instead of owning a service reference.
        if (service==null && router.getClass().getName().equals("android.media.MediaRouter2")) return;
        if (!contract.isInstance(service))
            throw new EngineException("Unknown cached MediaRouter service layout");
        RuntimeReflection.set(router,"mMediaRouterService",proxy);
        if (RuntimeReflection.get(router,"mMediaRouterService")!=proxy) throw new EngineException("Cached MediaRouter mapping did not stick");
    }

    @Override public Object invoke(Object proxy, Method method, Object[] supplied) throws Throwable {
        if (method.getDeclaringClass()==Object.class) {
            if (method.getName().equals("toString")) return "MyliteMediaRouter";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy==supplied[0];
        }
        // A local Java call can run inside a valid incoming callback; its Binder calling UID is not its process UID.
        requireBoundProcess.run();
        if (method.getName().equals("asBinder")) return facade;
        long event=EngineDiagnostics.begin("media_api",method.getName(),0);
        try {
            Object[] args=supplied; Class<?>[] types=method.getParameterTypes(); String name=method.getName();
            boolean client=name.equals("registerClientAsUser");
            if (client || name.equals("registerRouter2") || name.equals("registerManager")) {
                int count=client?3:2;
                if (method.getReturnType()!=void.class || types.length!=count || !IInterface.class.isAssignableFrom(types[0])
                        || types[1]!=String.class || client && types[2]!=int.class)
                    throw new UnsupportedOperationException("Unknown MediaRouter registration signature");
                if (args==null || args.length!=count) throw new IllegalArgumentException("Invalid MediaRouter registration");
                args=attributed(args,1);
                if (client) {
                    if (!(args[2] instanceof Integer)) throw new IllegalArgumentException("Invalid MediaRouter user");
                    int user=(Integer)args[2];
                    if (user!=0 && user!=hostUser) throw new SecurityException("MediaRouter registration belongs to another user");
                    args[2]=hostUser;
                }
            } else if (name.equals("getSystemRoutes")) {
                if (!List.class.isAssignableFrom(method.getReturnType())) throw new UnsupportedOperationException("Unknown MediaRouter routes result");
                if (types.length==2 && types[0]==String.class && types[1]==boolean.class) {
                    if (args==null || args.length!=2 || !(args[1] instanceof Boolean)) throw new IllegalArgumentException("Invalid MediaRouter routes query");
                    args=attributed(args,0);
                } else if (types.length!=0) throw new UnsupportedOperationException("Unknown MediaRouter routes signature");
            }
            Object result;
            try { result=method.invoke(original,args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            EngineDiagnostics.event("media_api","returned",event,result instanceof List?((List<?>)result).size():0);
            return result;
        } catch (Throwable failure) { EngineDiagnostics.failure("media_api",method.getName(),event,failure);throw failure; }
    }

    private Object[] attributed(Object[] input,int index) {
        if (!guest.equals(input[index]) && !host.equals(input[index])) throw new SecurityException("MediaRouter attribution is not this guest or host");
        Object[] result=input.clone();result[index]=host;return result;
    }
}
