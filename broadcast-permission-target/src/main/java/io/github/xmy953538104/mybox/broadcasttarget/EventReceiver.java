package io.github.xmy953538104.mybox.broadcasttarget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

/** An ordinary APK receiver: no dependency on the container or a test-only grant. */
public class EventReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Bundle observed = new Bundle();
        observed.putString("package", context.getPackageName());
        observed.putString("receiver", getClass().getName());
        observed.putInt("pid", android.os.Process.myPid());
        observed.putString("case", intent.getStringExtra("case"));
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            observed.putInt("sender_uid", getSentFromUid());
            observed.putString("sender_package", getSentFromPackage());
        }
        IBinder observer = intent.getExtras() == null ? null : intent.getExtras().getBinder("observer");
        boolean ordered = isOrderedBroadcast();
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                observed.putBoolean("async", true);
                if (ordered) {
                    pending.setResultCode(pending.getResultCode() + 1);
                    pending.setResultData("received");
                    pending.setResultExtras(observed);
                }
                if (observer != null) {
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try {
                        data.writeBundle(observed);
                        if (!observer.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) throw new IllegalStateException("Observer refused callback");
                        reply.readException();
                    } finally { reply.recycle(); data.recycle(); }
                }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            finally { pending.finish(); }
        }, "permission-receiver").start();
    }

    public static final class Open extends EventReceiver { }
    public static final class MissingSender extends EventReceiver { }
    public static final class Private extends EventReceiver { }
    public static final class Disabled extends EventReceiver { }
}
