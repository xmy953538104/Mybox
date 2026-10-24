package io.github.xmy953538104.mybox.engine;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Guest scheduling ends at the authenticated server, never the physical guest package name. */
final class GuestJobCalls implements InvocationHandler {
    private final String packageName;
    private final IBinder endpoint;
    private IBinder local;
    private GuestJobCalls(String packageName, IBinder endpoint) { this.packageName = packageName; this.endpoint = endpoint; }

    @SuppressWarnings("unchecked") static void install(Engine engine, GuestRuntime runtime) throws Exception {
        Bundle request = EngineProtocol.response(); request.putString("service", "jobs");
        IBinder endpoint = EngineProtocol.request(engine.server(), EngineProtocol.SERVER, EngineProtocol.GET_SERVICE, request).getBinder("service");
        if (endpoint == null) throw new EngineException("Virtual job scheduler is unavailable");
        Class<?> contract = Class.forName("android.app.job.IJobScheduler");
        GuestJobCalls calls = new GuestJobCalls(runtime.packageInfo().packageName, endpoint);
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw platform scheduling transactions are not supported");
            }
        };
        local.attachInterface(proxy, contract.getName()); calls.local = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put("jobscheduler", local);
        JobScheduler current = engine.hostContext().getSystemService(JobScheduler.class);
        RuntimeReflection.set(current, "mBinder", proxy);
        if (cache.get("jobscheduler") != local || RuntimeReflection.get(current, "mBinder") != proxy) {
            throw new EngineException("JobScheduler boundary was not installed");
        }
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteJobScheduler";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        Bundle input = EngineProtocol.response(); input.putString("package", packageName); input.putString("operation", name);
        int count = args == null ? 0 : args.length;
        int offset = Build.VERSION.SDK_INT >= 34 ? 1 : 0;
        switch (name) {
            case "schedule": case "enqueue":
                if (count != offset + (name.equals("schedule") ? 1 : 2) || !(args[offset] instanceof JobInfo)) break;
                if (offset == 1) input.putString("namespace", (String) args[0]);
                input.putParcelable("job", (JobInfo) args[offset]);
                if (name.equals("enqueue")) input.putParcelable("work", (JobWorkItem) args[offset + 1]);
                return call(input).getInt("result");
            case "cancel": case "getPendingJob": case "getPendingJobReason":
                if (count != offset + 1 || !(args[offset] instanceof Integer)) break;
                if (offset == 1) input.putString("namespace", (String) args[0]);
                input.putInt("job_id", (Integer) args[offset]);
                Bundle one = call(input);
                return name.equals("cancel") ? null : name.equals("getPendingJob") ? one.getParcelable("job") : one.getInt("result");
            case "cancelAll":
                if (count == 0) { call(input); return null; } break;
            case "cancelAllInNamespace":
                if (count == 1) { input.putString("namespace", (String) args[0]); call(input); return null; } break;
            case "getAllPendingJobs": case "getAllPendingJobsInNamespace":
                if (count != (name.equals("getAllPendingJobsInNamespace") ? 1 : 0)) break;
                if (count == 1) input.putString("namespace", (String) args[0]);
                Bundle result = call(input);
                ArrayList<JobInfo> jobs = result.getParcelableArrayList("jobs");
                if (Build.VERSION.SDK_INT < 34 || count == 1) return slice(jobs);
                ArrayList<String> namespaces = result.getStringArrayList("namespaces");
                Map<String, ArrayList<JobInfo>> groups = new LinkedHashMap<>();
                for (int i = 0; i < jobs.size(); i++) groups.computeIfAbsent(namespaces.get(i), ignored -> new ArrayList<>()).add(jobs.get(i));
                Map<String, Object> slices = new LinkedHashMap<>();
                for (Map.Entry<String, ArrayList<JobInfo>> entry : groups.entrySet()) slices.put(entry.getKey(), slice(entry.getValue()));
                return slices;
        }
        throw new UnsupportedOperationException("Virtual JobScheduler method/signature is not implemented: " + name);
    }
    private Bundle call(Bundle input) { return EngineProtocol.request(endpoint, ServerJobs.DESCRIPTOR, IBinder.FIRST_CALL_TRANSACTION, input); }
    private static Object slice(ArrayList<JobInfo> jobs) throws Exception {
        return Class.forName("android.content.pm.ParceledListSlice").getConstructor(java.util.List.class).newInstance(jobs);
    }
}
