package io.github.xmy953538104.mybox.engine;

import android.os.storage.StorageVolume;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GuestStorageCallsTest {
    public static final class Original {
        final StorageVolume[] volumes = new StorageVolume[0];
        Object[] arguments;
        RuntimeException failure;
        int calls;
        public StorageVolume[] getVolumeList(int identity, String pkg, int flags) {
            calls++; arguments = new Object[]{identity, pkg, flags};
            if (failure != null) throw failure;
            return volumes;
        }
        public StorageVolume[] getVolumeList(String pkg) { throw new AssertionError("Unknown signature reached OS"); }
        private long record(Object... args) { calls++; arguments=args; if(failure!=null) throw failure; return 123456789L; }
        public long getCacheSizeBytes(String uuid,int uid) { return record(uuid,uid); }
        public long getCacheQuotaBytes(String uuid,int uid) { return record(uuid,uid); }
        public long getAllocatableBytes(String uuid,int flags) { return record(uuid,flags); }
        public long getAllocatableBytes(String uuid,int flags,String pkg) { return record(uuid,flags,pkg); }
        public void allocateBytes(String uuid,long bytes,int flags) { record(uuid,bytes,flags); }
        public void allocateBytes(String uuid,long bytes,int flags,String pkg) { record(uuid,bytes,flags,pkg); }
    }
    private static Method query() throws Exception { return Original.class.getMethod("getVolumeList", int.class, String.class, int.class); }
    private static GuestStorageCalls adapter(Original os, int sdk, int preview) {
        return new GuestStorageCalls(os, "guest", "host", 10001, 110192, sdk, preview, () -> {});
    }
    @Test public void androidVersionsAndPreviewKeepRealResultAndAllFlags() throws Throwable {
        for (int sdk = 26; sdk <= 35; sdk++) for (int preview : new int[]{0,1}) {
            Original os = new Original(); GuestStorageCalls calls = adapter(os, sdk, preview);
            boolean user = sdk >= 33 || sdk == 32 && preview > 0;
            Object[] input = {user ? 0 : 10001, "guest", 0x700};
            Object[] unchanged = input.clone();
            assertSame(os.volumes, calls.invoke(null, query(), input));
            assertArrayEquals(new Object[]{user ? 1 : 110192, "host", 0x700}, os.arguments);
            assertArrayEquals(unchanged, input);
            assertSame(os.volumes, calls.invoke(null, query(), os.arguments.clone()));
            assertEquals(2, os.calls);
        }
    }
    @Test public void foreignIdentityUnknownLayoutAndIncomingCallNeverReachOs() throws Exception {
        for (int sdk : new int[]{26,32,33,35}) {
            Original os = new Original(); GuestStorageCalls calls = adapter(os, sdk, 0);
            int own = sdk >= 33 ? 0 : 10001;
            for (Object[] input : new Object[][]{{own,"foreign",0}, {own,null,0}, {999,"guest",0}}) {
                assertThrows(SecurityException.class, () -> calls.invoke(null, query(), input));
            }
            assertThrows(IllegalArgumentException.class, () -> calls.invoke(null, query(), new Object[]{own,"guest"}));
            assertThrows(UnsupportedOperationException.class, () -> calls.invoke(null,
                    Original.class.getMethod("getVolumeList",String.class), new Object[]{"guest"}));
            GuestStorageCalls incoming = new GuestStorageCalls(os,"guest","host",10001,110192,sdk,0,
                    () -> { throw new SecurityException("Actual remote caller"); });
            assertThrows(SecurityException.class, () -> incoming.invoke(null, query(), new Object[]{own,"guest",0}));
            assertEquals(0, os.calls);
        }
    }
    @Test public void realBackendFailureIsPreservedWithoutFallbackVolumes() throws Exception {
        Original os = new Original(); os.failure = new SecurityException("Actual OS permission denial");
        GuestStorageCalls calls = adapter(os,34,0);
        assertSame(os.failure, assertThrows(SecurityException.class, () -> calls.invoke(null,query(),new Object[]{0,"guest",9})));
        assertEquals(1,os.calls);
        assertThrows(UnsupportedOperationException.class, () -> adapter(os,36,0));
    }
    @Test public void cacheAndAllocationKeepRealQuotaBytesFlagsAndFailure() throws Throwable {
        for (int sdk : new int[]{26,27,28,35}) {
            Original os=new Original(); GuestStorageCalls calls=adapter(os,sdk,0);
            for(String name:new String[]{"getCacheSizeBytes","getCacheQuotaBytes"}) {
                Method method=Original.class.getMethod(name,String.class,int.class);
                assertEquals(123456789L,calls.invoke(null,method,new Object[]{null,10001}));
                assertArrayEquals(new Object[]{null,110192},os.arguments);
                assertThrows(SecurityException.class,()->calls.invoke(null,method,new Object[]{null,999}));
            }
            Class<?>[] layout=sdk==26 ? new Class<?>[]{String.class,int.class} : new Class<?>[]{String.class,int.class,String.class};
            Method query=Original.class.getMethod("getAllocatableBytes",layout);
            Object[] input=sdk==26 ? new Object[]{null,0x123} : new Object[]{null,0x123,"guest"};
            assertEquals(123456789L,calls.invoke(null,query,input));
            assertArrayEquals(sdk==26 ? input : new Object[]{null,0x123,"host"},os.arguments);
            if(sdk>=27) assertEquals("guest",input[2]);
            layout=sdk==26 ? new Class<?>[]{String.class,long.class,int.class} : new Class<?>[]{String.class,long.class,int.class,String.class};
            Method allocate=Original.class.getMethod("allocateBytes",layout);
            input=sdk==26 ? new Object[]{null,123L,0x456} : new Object[]{null,123L,0x456,"guest"};
            calls.invoke(null,allocate,input);
            assertArrayEquals(sdk==26 ? input : new Object[]{null,123L,0x456,"host"},os.arguments);
            if(sdk>=27) assertThrows(SecurityException.class,()->calls.invoke(null,query,new Object[]{null,0,"foreign"}));
            os.failure=new SecurityException("Actual allocation denied");
            Object[] request=sdk==26 ? new Object[]{null,0} : new Object[]{null,0,"guest"};
            assertSame(os.failure,assertThrows(SecurityException.class,()->calls.invoke(null,query,request)));
        }
    }
}
