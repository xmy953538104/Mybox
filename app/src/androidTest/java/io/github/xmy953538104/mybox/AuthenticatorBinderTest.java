package io.github.xmy953538104.mybox;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.test.AndroidTestCase;
import top.niunaijun.blackbox.utils.SynchronousBinder;

public final class AuthenticatorBinderTest extends AndroidTestCase {
    public void testOnewayClientGetsSynchronousTransportWithEmptyReply() throws Exception {
        int[] seenFlags = {-1};
        Binder target = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                seenFlags[0] = flags;
                return true;
            }
        };
        Parcel data = Parcel.obtain();
        try {
            assertTrue(SynchronousBinder.wrap(target).transact(1, data, null, IBinder.FLAG_ONEWAY));
            assertEquals(0, seenFlags[0] & IBinder.FLAG_ONEWAY);
        } finally { data.recycle(); }
    }
}
