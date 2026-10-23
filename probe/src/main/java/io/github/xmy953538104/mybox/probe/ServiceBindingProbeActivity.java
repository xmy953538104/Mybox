package io.github.xmy953538104.mybox.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Exercises the platform Context API and a real Binder in a second guest process. */
public final class ServiceBindingProbeActivity extends Activity {
    private final JSONObject result = new JSONObject();
    private final Handler handler = new Handler();
    private ServiceConnection connection;
    private boolean bound;
    private boolean finished;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        handler.postDelayed(() -> complete("Timed out waiting for service callback"), 30000);
        bind(false);
    }

    private void bind(boolean longFlags) {
        final ComponentName expected = new ComponentName(this, ServiceBindingProbeService.class);
        final String nonce = UUID.randomUUID().toString();
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                if (finished) return;
                Parcel request = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    request.writeInterfaceToken(ServiceBindingProbeService.DESCRIPTOR);
                    request.writeString(nonce);
                    if (!service.transact(IBinder.FIRST_CALL_TRANSACTION, request, reply, 0)) {
                        throw new IllegalStateException("Guest transaction was not handled");
                    }
                    reply.readException();
                    boolean echo = nonce.equals(reply.readString());
                    int remotePid = reply.readInt();
                    boolean guestPackage = getPackageName().equals(reply.readString());
                    boolean passed = echo && remotePid != Process.myPid() && remotePid > 0
                            && guestPackage && expected.equals(name);
                    result.put(longFlags ? "longFlagsCrossProcess" : "intFlagsCrossProcess", passed);
                    unbindService(connection);
                    bound = false;
                    if (!passed) complete("Guest Binder identity or echo mismatch");
                    else if (!longFlags && Build.VERSION.SDK_INT >= 34) handler.post(() -> bind(true));
                    else complete(null);
                } catch (Exception error) {
                    complete(error.getClass().getSimpleName());
                } finally {
                    reply.recycle();
                    request.recycle();
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                complete("Service disconnected before completion");
            }

            @Override public void onNullBinding(ComponentName name) { complete("Unexpected null binding"); }
        };
        try {
            Intent intent = new Intent().setComponent(expected);
            if (longFlags && Build.VERSION.SDK_INT >= 34) {
                bound = bindService(intent, connection, Context.BindServiceFlags.of(BIND_AUTO_CREATE));
            } else {
                bound = bindService(intent, connection, BIND_AUTO_CREATE);
            }
            if (!bound) complete("bindService returned false");
        } catch (Exception error) {
            complete(error.getClass().getSimpleName());
        }
    }

    private void complete(String error) {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        try {
            if (bound) {
                unbindService(connection);
                bound = false;
            }
            result.put("passed", error == null);
            if (error != null) result.put("error", error);
            File temporary = new File(getFilesDir(), "service-binding-result.tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(result.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!temporary.renameTo(new File(getFilesDir(), "service-binding-result.json"))) {
                throw new IllegalStateException("Cannot publish service probe result");
            }
        } catch (Exception failure) {
            android.util.Log.e("MyboxServiceProbe", "Cannot finish service probe: " + failure.getClass().getSimpleName());
        }
        finish();
    }
}
