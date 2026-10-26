package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;

/** Volume queries retain real OS volumes, permissions, flags and failures. */
final class GuestStorageCalls implements InvocationHandler {
    private final Object original;
    private final String guest, host;
    private final int guestUid, hostUid, hostUser, sdk;
    private final boolean userArgument;
    private final Runnable requireLocalCaller;
    private IBinder facade;

    GuestStorageCalls(Object original, String guest, String host, int guestUid, int hostUid,
            int sdk, int preview, Runnable requireLocalCaller) {
        if (sdk < 26 || sdk > 35) throw new UnsupportedOperationException("Storage Android version is not verified");
        this.original = original; this.guest = guest; this.host = host;
        this.guestUid = guestUid; this.hostUid = hostUid; hostUser = hostUid / 100000;
        this.sdk = sdk;
        userArgument = sdk >= 33 || sdk == 32 && preview > 0;
        this.requireLocalCaller = requireLocalCaller;
    }
    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        Context context = engine.hostContext();
        int uid = Process.myUid();
        if (context.getApplicationInfo().uid != uid) throw new SecurityException("Storage host UID differs from this process");
        StorageManager manager = context.getSystemService(StorageManager.class);
        Object original = RuntimeReflection.get(manager, "mStorageManager");
        Class<?> contract = Class.forName("android.os.storage.IStorageManager");
        requireVolumeSignature(contract.getMethod("getVolumeList", int.class, String.class, int.class));
        GuestStorageCalls calls = new GuestStorageCalls(original, runtime.packageInfo().packageName,
                context.getOpPackageName(), runtime.packageInfo().applicationInfo.uid, uid,
                Build.VERSION.SDK_INT, Build.VERSION.PREVIEW_SDK_INT, () -> {
                    if (Binder.getCallingUid() != Process.myUid() || Binder.getCallingPid() != Process.myPid()) {
                        throw new SecurityException("Storage requests require a local guest call");
                    }
                });
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        IBinder physical = ((IInterface) original).asBinder();
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                // Raw transactions retain actual kernel caller/OS checks; no identity is inferred here.
                return physical.transact(code, data, reply, flags);
            }
        };
        local.attachInterface(proxy, contract.getName()); calls.facade = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put("mount", local); RuntimeReflection.set(manager, "mStorageManager", proxy);
        if (cache.get("mount") != local || RuntimeReflection.get(manager, "mStorageManager") != proxy) {
            throw new EngineException("Storage boundary was not installed");
        }
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            if (method.getName().equals("toString")) return "MyliteStorageManager";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
        }
        requireLocalCaller.run();
        if (method.getName().equals("asBinder")) return facade;
        Object[] forwarded = args;
        if (method.getName().equals("getVolumeList")) {
            requireVolumeSignature(method);
            if (args == null || args.length != 3 || !(args[0] instanceof Integer) || !(args[2] instanceof Integer)) {
                throw new IllegalArgumentException("Invalid storage volume query");
            }
            if (!guest.equals(args[1]) && !host.equals(args[1])) throw new SecurityException("Storage package is not this guest or host");
            int identity = (Integer) args[0];
            if (userArgument ? identity != 0 && identity != hostUser : identity != guestUid && identity != hostUid) {
                throw new SecurityException("Storage query belongs to another UID or user");
            }
            forwarded = args.clone(); forwarded[0] = userArgument ? hostUser : hostUid; forwarded[1] = host;
        } else if (method.getName().equals("getCacheQuotaBytes") || method.getName().equals("getCacheSizeBytes")) {
            requireSignature(method,long.class,String.class,int.class);
            if (args == null || args.length != 2 || !(args[1] instanceof Integer)) throw new IllegalArgumentException("Invalid cache query");
            if ((Integer)args[1] != guestUid && (Integer)args[1] != hostUid) throw new SecurityException("Cache query belongs to another UID");
            forwarded = args.clone(); forwarded[1] = hostUid;
        } else if (method.getName().equals("getAllocatableBytes") || method.getName().equals("allocateBytes")) {
            boolean allocate = method.getName().equals("allocateBytes");
            Class<?>[] types = allocate ? new Class<?>[]{String.class,long.class,int.class,String.class}
                    : new Class<?>[]{String.class,int.class,String.class};
            if (sdk == 26) types = Arrays.copyOf(types,types.length-1);
            requireSignature(method,allocate ? void.class : long.class,types);
            if (args == null || args.length != types.length) throw new IllegalArgumentException("Invalid allocation query");
            if (sdk >= 27) {
                int pkg = types.length-1;
                if (!guest.equals(args[pkg]) && !host.equals(args[pkg])) throw new SecurityException("Allocation package is not this guest or host");
                forwarded = args.clone(); forwarded[pkg] = host;
            }
        }
        try { return method.invoke(original, forwarded); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static void requireVolumeSignature(Method method) {
        requireSignature(method,StorageVolume[].class,int.class,String.class,int.class);
    }
    private static void requireSignature(Method method,Class<?> result,Class<?>... types) {
        if (method.getReturnType() != result || !Arrays.equals(method.getParameterTypes(),types))
            throw new UnsupportedOperationException("Storage signature is not verified: " + method.getName());
    }
}
