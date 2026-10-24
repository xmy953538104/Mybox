package io.github.xmy953538104.mybox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** A normal Android component; it has no engine dependency. */
public final class RemoteService extends Service {
    static final String DESCRIPTOR = "mybox.fixture.remote";
    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
            data.enforceInterface(DESCRIPTOR);
            reply.writeNoException();
            reply.writeInt(Process.myPid());
            reply.writeString(getPackageName());
            return true;
        }
    };
    private void mark(String event) {
        try (FileOutputStream out = new FileOutputStream(new java.io.File(getFilesDir(), "service-events.txt"), true)) {
            out.write((event + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @Override public void onCreate() { super.onCreate(); mark("create"); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { mark("start"); return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { mark("bind"); return binder; }
    @Override public boolean onUnbind(Intent intent) { mark("unbind"); return false; }
    @Override public void onDestroy() { mark("destroy"); super.onDestroy(); }
}
