package io.github.xmy953538104.mybox.engine;

import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GuestMediaRouterCallsTest {
    public static final class Original {
        final List<String> routes=Collections.singletonList("real-system-route");
        Object[] args; int calls; SecurityException failure;
        private void record(Object... supplied) { calls++; args=supplied; if(failure!=null) throw failure; }
        public void registerClientAsUser(IInterface callback,String pkg,int user) { record(callback,pkg,user); }
        public void registerRouter2(IInterface callback,String pkg) { record(callback,pkg); }
        public void registerManager(IInterface callback,String pkg) { record(callback,pkg); }
        public void registerRouter2(String pkg) { throw new AssertionError("Unknown signature reached system"); }
        public void registerClientGroupId(IInterface callback,String group) { record(callback,group); }
        public List<String> getSystemRoutes() { record(); return routes; }
        public List<String> getSystemRoutes(String pkg,boolean proxy) { record(pkg,proxy); return routes; }
        public List<String> getSystemRoutes(String pkg) { throw new AssertionError("Unknown signature reached system"); }
    }
    private final IInterface callback=new IInterface() { @Override public IBinder asBinder() { return null; } };
    private static GuestMediaRouterCalls adapter(Original os) { return new GuestMediaRouterCalls(os,"guest","host",1,()->{}); }
    private static Method method(String name,Class<?>... types) throws Exception { return Original.class.getMethod(name,types); }

    @Test public void registrationsMapOnlyAttributionAndKeepCallbackUserAndInput() throws Throwable {
        Original os=new Original(); GuestMediaRouterCalls calls=adapter(os);
        for(String name:new String[]{"registerRouter2","registerManager"}) for(String pkg:new String[]{"guest","host"}) {
            Object[] input={callback,pkg}; calls.invoke(null,method(name,IInterface.class,String.class),input);
            assertArrayEquals(new Object[]{callback,"host"},os.args); assertArrayEquals(new Object[]{callback,pkg},input);
        }
        for(int user:new int[]{0,1}) {
            Object[] input={callback,"guest",user};
            calls.invoke(null,method("registerClientAsUser",IInterface.class,String.class,int.class),input);
            assertArrayEquals(new Object[]{callback,"host",1},os.args);
            assertArrayEquals(new Object[]{callback,"guest",user},input);
        }
        calls.invoke(null,method("registerClientGroupId",IInterface.class,String.class),new Object[]{callback,"guest"});
        assertArrayEquals(new Object[]{callback,"guest"},os.args);
    }
    @Test public void foreignPackagesUsersMalformedLayoutsAndWrongProcessNeverReachSystem() throws Exception {
        Original os=new Original(); GuestMediaRouterCalls calls=adapter(os);
        Method register=method("registerRouter2",IInterface.class,String.class);
        for(String pkg:new String[]{null,"foreign"})
            assertThrows(SecurityException.class,()->calls.invoke(null,register,new Object[]{callback,pkg}));
        assertThrows(SecurityException.class,()->calls.invoke(null,
                method("registerClientAsUser",IInterface.class,String.class,int.class),new Object[]{callback,"guest",2}));
        assertThrows(IllegalArgumentException.class,()->calls.invoke(null,register,new Object[]{callback}));
        assertThrows(UnsupportedOperationException.class,()->calls.invoke(null,method("registerRouter2",String.class),new Object[]{"guest"}));
        assertThrows(UnsupportedOperationException.class,()->calls.invoke(null,method("getSystemRoutes",String.class),new Object[]{"guest"}));
        GuestMediaRouterCalls wrong=new GuestMediaRouterCalls(os,"guest","host",1,()->{throw new SecurityException("Unbound process");});
        assertThrows(SecurityException.class,()->wrong.invoke(null,register,new Object[]{callback,"guest"}));
        assertEquals(0,os.calls);
    }
    @Test public void routesFlagsAndActualPermissionDenialsArePreserved() throws Throwable {
        Original os=new Original(); GuestMediaRouterCalls calls=adapter(os);
        assertSame(os.routes,calls.invoke(null,method("getSystemRoutes"),null));
        Method query=method("getSystemRoutes",String.class,boolean.class);
        for(boolean proxy:new boolean[]{false,true}) {
            Object[] input={"guest",proxy}; assertSame(os.routes,calls.invoke(null,query,input));
            assertArrayEquals(new Object[]{"host",proxy},os.args); assertEquals("guest",input[0]);
        }
        assertThrows(SecurityException.class,()->calls.invoke(null,query,new Object[]{"foreign",false}));
        os.failure=new SecurityException("Real system permission denial");
        assertSame(os.failure,assertThrows(SecurityException.class,()->calls.invoke(null,
                method("registerManager",IInterface.class,String.class),new Object[]{callback,"guest"})));
        assertEquals(4,os.calls);
    }
}
