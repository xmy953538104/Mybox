package io.github.xmy953538104.mybox.fixture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class ComponentProbe {
    static void run(Context context) {
        new Thread(() -> {
            JSONObject report = new JSONObject();
            CountDownLatch connected = new CountDownLatch(1);
            int[] owner = {0};
            String[] ownerPackage = {null};
            Throwable[] callbackFailure = {null};
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(RemoteService.DESCRIPTOR);
                        if (!service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) throw new IllegalStateException("Transaction rejected");
                        reply.readException();
                        owner[0] = reply.readInt();
                        ownerPackage[0] = reply.readString();
                    } catch (Throwable failure) { callbackFailure[0] = failure; }
                    finally { data.recycle(); reply.recycle(); connected.countDown(); }
                }
                @Override public void onServiceDisconnected(ComponentName name) { }
            };
            Intent intent = new Intent(context, RemoteService.class);
            boolean bound = false;
            boolean started = false;
            try {
                File events = new File(context.getFilesDir(), "service-events.txt");
                if (events.exists() && !events.delete()) throw new java.io.IOException("Cannot reset service events");
                started = context.startService(intent) != null;
                if (!started) throw new IllegalStateException("startService failed");
                // startService only enqueues; establish the sequence before requesting a binding.
                long startDeadline = SystemClock.elapsedRealtime() + 10000;
                String beforeBind = "";
                do {
                    if (events.isFile()) beforeBind = new String(Files.readAllBytes(events.toPath()), StandardCharsets.UTF_8);
                    if ("create\nstart\n".equals(beforeBind)) break;
                    Thread.sleep(25);
                } while (SystemClock.elapsedRealtime() < startDeadline);
                if (!"create\nstart\n".equals(beforeBind)) throw new IllegalStateException("Service start was not observed before binding");
                report.put("startBeforeBindObserved", true);
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!bound || !connected.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Service bind timed out");
                if (callbackFailure[0] != null) throw new IllegalStateException(callbackFailure[0]);
                Bundle provider = context.getContentResolver().call(Uri.parse("content://" + context.getPackageName() + ".remote"), "identity", null, null);
                report.put("mainPid", Process.myPid());
                report.put("servicePid", owner[0]);
                report.put("servicePackage", ownerPackage[0]);
                report.put("providerPid", provider.getInt("pid"));
                report.put("providerPackage", provider.getString("package"));
                context.unbindService(connection);
                bound = false;
                report.put("stopped", context.stopService(intent));
                started = false;
                long deadline = SystemClock.elapsedRealtime() + 10000;
                String lifecycle = "";
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (events.isFile()) lifecycle = new String(Files.readAllBytes(events.toPath()), StandardCharsets.UTF_8);
                    if (lifecycle.contains("destroy\n")) break;
                    Thread.sleep(100);
                }
                report.put("lifecycle", lifecycle);
            } catch (Throwable failure) {
                try { report.put("failure", android.util.Log.getStackTraceString(failure)); } catch (Exception ignored) { }
            } finally {
                try { if (bound) context.unbindService(connection); } catch (Exception ignored) { }
                try { if (started) context.stopService(intent); } catch (Exception ignored) { }
            }
            try (FileOutputStream out = context.openFileOutput("components-result.json.tmp", 0)) {
                out.write(report.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            if (!new File(context.getFilesDir(), "components-result.json.tmp").renameTo(new File(context.getFilesDir(), "components-result.json"))) throw new IllegalStateException("Cannot publish component report");
        }, "fixture-components").start();
    }
}
