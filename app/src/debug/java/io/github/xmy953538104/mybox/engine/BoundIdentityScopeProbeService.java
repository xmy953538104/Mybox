package io.github.xmy953538104.mybox.engine;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** Debug-only: real kernel one-way delivery and two scopes, without a trusted-service exemption. */
public final class BoundIdentityScopeProbeService extends Service {
    static final String CONTROL = "mybox.synthetic.BoundIdentityScopes";
    private final IBinder first = new Binder(), second = new Binder();
    private IBinder firstEndpoint, secondEndpoint;
    private Method helperResolve,helperScope;
    private String helperLoadFailure;
    @Override public void onCreate() {
        super.onCreate();
        try {
            io.github.xmy953538104.mybox.engine.packages.InstalledPackage installed=Engine.get().packages().get("com.google.android.gms");
            if(installed!=null) {
                TrustedMicrog.require(this,installed);
                PathClassLoader loader=new PathClassLoader(installed.baseApk.getAbsolutePath(),getClassLoader().getParent());
                Class<?> helper=Class.forName("org.microg.gms.common.MyboxIdentity",true,loader);
                if(helper.getClassLoader()!=loader) throw new SecurityException("Scope helper is not from the pinned service");
                helperResolve=helper.getMethod("resolve",android.content.Context.class);
                helperScope=helper.getDeclaredMethod("currentBoundLease",android.content.Context.class); helperScope.setAccessible(true);
            }
        } catch(Exception | LinkageError failure) { helperResolve=null; helperScope=null; helperLoadFailure=failure.getClass().getSimpleName(); }
        Messenger original = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public boolean sendMessageAtTime(Message message,long time) {
                Bundle observed = new Bundle(); IBinder scope = BoundServiceIdentity.currentLeaseV1();
                observed.putInt("scope",scope == first ? 1 : scope == second ? 2 : -1);
                observed.putInt("rawUid",Binder.getCallingUid()); observed.putInt("rawPid",Binder.getCallingPid());
                observed.putInt("ownerPid",Process.myPid());
                if(message.arg1==1) {
                    observed.putBoolean("trustedHelperLoaded",helperResolve!=null && helperScope!=null);
                    if(helperLoadFailure!=null) observed.putString("helperLoadFailure",helperLoadFailure);
                    try {
                        observed.putBoolean("sameHostScope",helperScope!=null && helperScope.invoke(null,BoundIdentityScopeProbeService.this)==scope);
                        if(helperResolve!=null) {
                            try { helperResolve.invoke(null,BoundIdentityScopeProbeService.this); }
                            catch(InvocationTargetException rejected) { observed.putBoolean("unregisteredRoleRejected",rejected.getCause() instanceof SecurityException); }
                        }
                    } catch(ReflectiveOperationException | RuntimeException failure) { observed.putString("helperFailure",failure.getClass().getSimpleName()); }
                }
                observed.putInt("afterUid",Binder.getCallingUid()); observed.putInt("afterPid",Binder.getCallingPid());
                Message response = Message.obtain(); response.what=message.what; response.setData(observed);
                try { message.replyTo.send(response); }
                catch(RemoteException failure) { throw new IllegalStateException(failure); }
                finally { response.recycle(); message.recycle(); }
                return true;
            }
        });
        try {
            firstEndpoint = BoundServiceIdentity.wrapLocal(original.getBinder(),first);
            secondEndpoint = BoundServiceIdentity.wrapLocal(original.getBinder(),second);
        } catch(RemoteException failure) { throw new IllegalStateException(failure); }
    }
    @Override public IBinder onBind(Intent intent) {
        return new Binder() {
            { attachInterface(null,CONTROL); }
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) {
                if(code!=FIRST_CALL_TRANSACTION) return false;
                EngineProtocol.requireCaller(); data.enforceInterface(CONTROL);
                if((flags&FLAG_ONEWAY)!=0 || reply==null || data.dataAvail()!=0) throw new SecurityException("Invalid scope probe request");
                Bundle result=new Bundle(); result.putBinder("first",firstEndpoint); result.putBinder("second",secondEndpoint);
                reply.writeNoException(); reply.writeBundle(result); return true;
            }
        };
    }
}
