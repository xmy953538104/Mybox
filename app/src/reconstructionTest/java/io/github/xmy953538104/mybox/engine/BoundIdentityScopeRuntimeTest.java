package io.github.xmy953538104.mybox.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
import android.test.InstrumentationTestCase;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BoundIdentityScopeRuntimeTest extends InstrumentationTestCase {
    public void testNestedLocalForwardingKeepsParcelCredentialsAndAlwaysRestoresScope() throws Exception {
        IBinder outerLease=new Binder(),innerLease=new Binder();
        Binder inner=new Binder() {
            { attachInterface(null,"android.os.IMessenger"); }
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) {
                assertSame(innerLease,BoundServiceIdentity.currentLeaseV1());
                data.enforceInterface("android.os.IMessenger"); assertEquals(73,data.readInt());
                assertEquals(Process.myUid(),Binder.getCallingUid()); assertEquals(Process.myPid(),Binder.getCallingPid());
                throw new SecurityException("Actual original Stub rejection");
            }
        };
        IBinder wrappedInner=BoundServiceIdentity.wrapLocal(inner,innerLease);
        Binder outer=new Binder() {
            { attachInterface(null,"android.os.IMessenger"); }
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
                assertSame(outerLease,BoundServiceIdentity.currentLeaseV1());
                try { wrappedInner.transact(code,data,reply,flags); fail("Original rejection disappeared"); }
                catch(SecurityException expected) { assertEquals("Actual original Stub rejection",expected.getMessage()); }
                assertSame(outerLease,BoundServiceIdentity.currentLeaseV1()); return true;
            }
        };
        IBinder wrapped=BoundServiceIdentity.wrapLocal(outer,outerLease);
        Parcel data=Parcel.obtain();
        try {
            data.writeInterfaceToken("android.os.IMessenger"); data.writeInt(73);
            assertNull(BoundServiceIdentity.currentLeaseV1());
            assertTrue(wrapped.transact(IBinder.FIRST_CALL_TRANSACTION,data,null,IBinder.FLAG_ONEWAY));
            assertNull(BoundServiceIdentity.currentLeaseV1());
            try { wrapped.transact(IBinder.FIRST_CALL_TRANSACTION,data,null,0); fail("Synchronous message was accepted"); }
            catch(SecurityException expected) { assertNull(BoundServiceIdentity.currentLeaseV1()); }
            try { wrappedInner.transact(IBinder.FIRST_CALL_TRANSACTION,data,null,IBinder.FLAG_ONEWAY); fail("Original exception disappeared"); }
            catch(SecurityException expected) { assertNull(BoundServiceIdentity.currentLeaseV1()); }
            try { BoundServiceIdentity.wrapLocal(new Binder(),outerLease); fail("Unknown descriptor was accepted"); }
            catch(SecurityException expected) { }
            try { BoundServiceIdentity.wrapLocal(outer,null); fail("Missing lease was accepted"); }
            catch(SecurityException expected) { }
            assertFalse(BoundServiceIdentity.isRegistration(new ComponentName("untrusted","org.microg.gms.gcm.PushRegisterService"),new Intent("com.google.android.c2dm.intent.REGISTER")));
            assertFalse(BoundServiceIdentity.isRegistration(new ComponentName("com.google.android.gms","org.microg.gms.gcm.PushRegisterService"),new Intent("com.google.android.c2dm.intent.UNREGISTER")));
        } finally { data.recycle(); }
    }
    public void testActualRemoteOnewayPidZeroKeepsTwoConcurrentEndpointScopes() throws Exception {
        exerciseRemoteScopes(getInstrumentation().getTargetContext(),false);
    }
    public static void exerciseRemoteScopes(Context host,boolean requireActualHelper) throws Exception {
        CountDownLatch connected=new CountDownLatch(1);
        IBinder[] control=new IBinder[1];
        ServiceConnection connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder) { control[0]=binder; connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        assertTrue(host.bindService(new Intent().setClassName(host.getPackageName(),BoundIdentityScopeProbeService.class.getName()),connection,Context.BIND_AUTO_CREATE));
        try {
            assertTrue(connected.await(10,TimeUnit.SECONDS)); assertNotNull(control[0]);
            Parcel request=Parcel.obtain(),reply=Parcel.obtain(); Bundle targets;
            try {
                request.writeInterfaceToken(BoundIdentityScopeProbeService.CONTROL);
                assertTrue(control[0].transact(IBinder.FIRST_CALL_TRANSACTION,request,reply,0)); reply.readException();
                targets=reply.readBundle(BoundIdentityScopeRuntimeTest.class.getClassLoader());
            } finally { request.recycle(); reply.recycle(); }
            try { BoundServiceIdentity.wrapLocal(targets.getBinder("first"),new Binder()); fail("A remote original Binder gained a local scope"); }
            catch(SecurityException expected) { }
            ConcurrentHashMap<Integer,Bundle> results=new ConcurrentHashMap<>(); CountDownLatch received=new CountDownLatch(20),go=new CountDownLatch(1);
            Messenger callback=new Messenger(new Handler(Looper.getMainLooper()) {
                @Override public void handleMessage(Message response) {
                    results.put(response.what,new Bundle(response.getData())); received.countDown();
                }
            });
            java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            Thread[] senders=new Thread[2];
            for(int i=0;i<2;i++) {
                int endpoint=i+1; Messenger remote=new Messenger(targets.getBinder(i==0?"first":"second"));
                senders[i]=new Thread(()->{
                    try {
                        if(!go.await(5,TimeUnit.SECONDS)) throw new AssertionError("Scope sender barrier timed out");
                        for(int n=0;n<10;n++) { Message message=Message.obtain(); message.what=endpoint*100+n; message.replyTo=callback;
                            message.arg1=requireActualHelper?1:0;
                            try { remote.send(message); } finally { message.recycle(); }
                        }
                    } catch(Throwable error) { failure.compareAndSet(null,error); }
                },"bound-scope-sender-"+endpoint);
                senders[i].start();
            }
            go.countDown(); assertTrue("Actual one-way callbacks were missing",received.await(15,TimeUnit.SECONDS));
            for(Thread sender:senders) { sender.join(5000); assertFalse(sender.isAlive()); }
            if(failure.get()!=null) throw new AssertionError("Scope sender failed",failure.get());
            assertEquals(20,results.size());
            for(java.util.Map.Entry<Integer,Bundle> item:results.entrySet()) {
                Bundle value=item.getValue(); assertEquals(item.getKey()/100,value.getInt("scope"));
                assertEquals(Process.myUid(),value.getInt("rawUid")); assertEquals(0,value.getInt("rawPid"));
                assertEquals(value.getInt("rawUid"),value.getInt("afterUid")); assertEquals(value.getInt("rawPid"),value.getInt("afterPid"));
                assertTrue(value.getInt("ownerPid")>0); assertTrue(value.getInt("ownerPid")!=Process.myPid());
                if(requireActualHelper) {
                    assertTrue("Pinned source helper did not load: "+value.getString("helperLoadFailure"),value.getBoolean("trustedHelperLoaded"));
                    assertFalse("Actual helper failed: "+value.getString("helperFailure"),value.containsKey("helperFailure"));
                    assertTrue("Guest helper saw another ClassLoader's scope",value.getBoolean("sameHostScope"));
                    assertTrue("An unregistered process borrowed the trusted microG role",value.getBoolean("unregisteredRoleRejected"));
                }
            }
            assertNull(BoundServiceIdentity.currentLeaseV1());
            System.out.println("MYLITE_BOUND_IDENTITY actualOnewayPidZero=true concurrentScopes=true rawUidPreserved=true messages=20");
            if(requireActualHelper) System.out.println("MYLITE_BOUND_IDENTITY actualPinnedHelper=true separateClassLoader=true sameScope=true unregisteredRoleRejected=true");
        } finally { host.unbindService(connection); }
    }
}
