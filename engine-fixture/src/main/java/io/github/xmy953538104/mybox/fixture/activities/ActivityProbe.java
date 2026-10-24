package io.github.xmy953538104.mybox.fixture.activities;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** Standard framework Activities plus deliberately invalid requests to the private engine IPC. */
public final class ActivityProbe {
    public static final int REQUEST = 428;
    public static final String PACKAGE = "io.github.xmy953538104.mybox.fixture";
    static final String RESERVED = "activity_test_reserved", HOST = "activity_test_host";

    public static void start(Activity activity) {
        new android.os.Handler(activity.getMainLooper()).post(() -> {
            try {
                Bundle reserved = activity.getIntent().getBundleExtra(RESERVED);
                String host = activity.getIntent().getStringExtra(HOST);
                denied(activity, host, reserved, token(activity), "another guest process");
                Bundle changed = new Bundle(reserved);
                changed.putLong("generation", reserved.getLong("generation") + 1);
                denied(activity, host, changed, token(activity), "generation");
                Bundle forged = new Bundle(reserved);
                forged.putBinder("activity_record", new Binder());
                denied(activity, host, forged, token(activity), "unknown");
                Bundle payload = new Bundle();
                payload.putBinder("echo", new Binder() {
                    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                        if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
                        reply.writeNoException();
                        reply.writeInt(42);
                        reply.writeInt(android.os.Process.myPid());
                        return true;
                    }
                });
                Intent next = new Intent(activity, SameSlotActivity.class)
                        .setDataAndType(Uri.parse("mylite-fixture://activity/payload"), "application/x-mylite-fixture")
                        .addCategory("io.github.xmy953538104.mybox.fixture.PAYLOAD")
                        .putExtras(payload).putExtra("value", 42)
                        .putExtra(HOST, host).putExtra(RESERVED, reserved)
                        .putExtra("root_pid", android.os.Process.myPid()).putExtra("root_task", activity.getTaskId());
                activity.startActivityForResult(next, REQUEST);
            } catch (Throwable failure) { failure(activity, failure); }
        });
    }

    public static boolean result(Activity activity, int request, int result, Intent data) {
        if (request != REQUEST) return false;
        try {
            if (result != Activity.RESULT_OK || data == null) throw new AssertionError("Activity result was not delivered");
            JSONObject report = new JSONObject(data.getStringExtra("report"));
            report.put("resultCode", result);
            report.put("resultRequest", request);
            report.put("resultRootPid", android.os.Process.myPid());
            report.put("wrongOwnerRejected", true);
            report.put("wrongGenerationRejected", true);
            report.put("forgedRecordRejected", true);
            write(activity, "activity-result.json", report);
        } catch (Throwable failure) { failure(activity, failure); }
        return true;
    }

    static JSONObject describe(Activity activity) throws Exception {
        Intent intent = activity.getIntent();
        if (intent.getIntExtra("value", -1) != 42 || !"application/x-mylite-fixture".equals(intent.getType())
                || !Uri.parse("mylite-fixture://activity/payload").equals(intent.getData())
                || !intent.hasCategory("io.github.xmy953538104.mybox.fixture.PAYLOAD")
                || intent.hasExtra("io.github.xmy953538104.mybox.engine.LAUNCH")) {
            throw new AssertionError("Original Activity payload was not restored exactly");
        }
        JSONObject report = new JSONObject();
        report.put("class", activity.getClass().getName());
        report.put("component", activity.getComponentName().flattenToString());
        report.put("package", activity.getPackageName());
        report.put("pid", android.os.Process.myPid());
        report.put("task", activity.getTaskId());
        report.put("callingPackage", activity.getCallingPackage());
        report.put("callingActivity", activity.getCallingActivity().getClassName());
        report.put("instance", java.util.UUID.randomUUID().toString());
        IBinder echo = intent.getExtras().getBinder("echo");
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            if (echo == null || !echo.transact(IBinder.FIRST_CALL_TRANSACTION, input, output, 0)) {
                throw new AssertionError("Original Binder extra was not delivered");
            }
            output.readException();
            if (output.readInt() != 42) throw new AssertionError("Binder response was changed");
            report.put("echoPid", output.readInt());
        } finally { input.recycle(); output.recycle(); }
        return report;
    }

    static IBinder token(Activity activity) throws Exception {
        java.lang.reflect.Field field = Activity.class.getDeclaredField("mToken");
        field.setAccessible(true);
        return (IBinder) field.get(activity);
    }

    static void denied(Activity activity, String host, Bundle reserved, IBinder physicalToken, String reason) throws Exception {
        Bundle connection = activity.getContentResolver().call(Uri.parse("content://" + host + ".engine.server"),
                "connect", null, new Bundle());
        IBinder server = connection.getBinder("server");
        Bundle request = new Bundle(reserved);
        request.putString("operation", "claim");
        request.putBinder("token", physicalToken);
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken("io.github.xmy953538104.mybox.engine.Server.v1");
            input.writeBundle(request);
            if (!server.transact(IBinder.FIRST_CALL_TRANSACTION + 12, input, output, 0)) {
                throw new AssertionError("Activity claim endpoint was absent");
            }
            output.readException();
            String error = output.readBundle(ActivityProbe.class.getClassLoader()).getString("error");
            if (error == null || !error.contains(reason)) throw new AssertionError("Invalid claim result: " + error);
        } finally { input.recycle(); output.recycle(); }
    }

    static void write(Activity activity, String name, JSONObject report) throws Exception {
        File pending = new File(activity.getFilesDir(), name + ".tmp");
        Files.write(pending.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(pending.toPath(), new File(activity.getFilesDir(), name).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void failure(Activity activity, Throwable failure) {
        try { write(activity, "activity-result.json", new JSONObject().put("failure", failure.toString())); }
        catch (Exception reportFailure) { throw new IllegalStateException(reportFailure); }
    }

    private ActivityProbe() { }
}
