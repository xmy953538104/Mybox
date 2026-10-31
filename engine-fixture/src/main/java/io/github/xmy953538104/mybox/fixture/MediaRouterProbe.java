package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.media.MediaRoute2Info;
import android.media.MediaRouter;
import android.media.MediaRouter2;
import android.media.RouteDiscoveryPreference;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Real framework registration and initial route callback; no synthetic routes or system privileges. */
final class MediaRouterProbe {
    static void run(Activity activity,String nonce) {
        JSONObject result=new JSONObject(); Handler main=new Handler(activity.getMainLooper());
        AtomicBoolean done=new AtomicBoolean();
        MediaRouter2[] modern={null}; MediaRouter2.RouteCallback[] callback={null};
        Runnable finish=()->{
            if (!done.compareAndSet(false,true)) return;
            try {
                if(modern[0]!=null && callback[0]!=null) {
                    result.put("modernRouteCount",modern[0].getRoutes().size());
                    modern[0].unregisterRouteCallback(callback[0]); result.put("unregistered",true);
                }
            } catch(Exception failure) { error(result,failure); }
            publish(activity,result);
        };
        try {
            result.put("package",activity.getPackageName()).put("nonce",nonce).put("pid",android.os.Process.myPid());
            MediaRouter legacy=activity.getSystemService(MediaRouter.class);
            result.put("legacyRouteCount",legacy.getRouteCount()).put("legacyDefault",legacy.getDefaultRoute()!=null);
            IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"media_router");
            String descriptor="android.media.IMediaRouterService";
            result.put("descriptorMatches",descriptor.equals(binder.getInterfaceDescriptor()));
            Object local=binder.queryLocalInterface(descriptor);
            result.put("localFacade",local!=null);
            Class<?> contract=Class.forName(descriptor);
            Method registration=null;
            for(Method method:contract.getMethods()) if(method.getName().equals("registerRouter2") && method.getParameterCount()==2) registration=method;
            if(registration==null) throw new IllegalStateException("No reviewed MediaRouter2 registration");
            try { registration.invoke(local,null,"unrelated.media.owner"); result.put("foreignDenied",false); }
            catch(InvocationTargetException failure) { if(!(failure.getCause() instanceof SecurityException)) throw failure; result.put("foreignDenied",true); }
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {
                try { binder.transact(1,data,reply,0); result.put("rawDenied",false); }
                catch(UnsupportedOperationException rejected) { result.put("rawDenied",true); }
            } finally { data.recycle();reply.recycle(); }
            modern[0]=MediaRouter2.getInstance(activity);
            result.put("cachedInstanceSame",modern[0]==MediaRouter2.getInstance(activity));
            callback[0]=new MediaRouter2.RouteCallback() {
                @Override public void onRoutesAdded(List<MediaRoute2Info> routes) {
                    if(done.get() || routes.isEmpty()) return;
                    try { result.put("callbackRouteCount",routes.size()).put("callbackPid",android.os.Process.myPid()); }
                    catch(org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
                    main.post(finish);
                }
            };
            modern[0].registerRouteCallback(activity.getMainExecutor(),callback[0],new RouteDiscoveryPreference.Builder(
                    Arrays.asList(MediaRoute2Info.FEATURE_LIVE_AUDIO,MediaRoute2Info.FEATURE_LIVE_VIDEO),false).build());
            result.put("registered",true);
            main.postDelayed(finish,8000);
        } catch(Exception failure) { error(result,failure);finish.run(); }
    }
    private static void error(JSONObject result,Exception failure) {
        try { result.put("error",failure.getClass().getName()); }
        catch(org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
    }
    private static void publish(Activity activity,JSONObject result) {
        try {
            File pending=new File(activity.getFilesDir(),"media-router-result.tmp");
            Files.write(pending.toPath(),result.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(pending.toPath(),new File(activity.getFilesDir(),"media-router-result.json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(Exception failure) { throw new IllegalStateException("Cannot retain actual media observation",failure); }
    }
}
