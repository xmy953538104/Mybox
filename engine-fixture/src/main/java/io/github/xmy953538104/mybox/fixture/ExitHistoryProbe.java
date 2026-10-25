package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.util.Base64;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses only real public exit APIs; a unique host-owned request selects the killed PID to inspect. */
@android.annotation.TargetApi(30)
final class ExitHistoryProbe {
    static void run(Activity activity, String nonce, boolean seed) {
        if (!UUID.fromString(nonce).toString().equals(nonce)) throw new IllegalArgumentException("Invalid exit probe nonce");
        new Thread(() -> {
            JSONObject report = new JSONObject();
            try {
                ActivityManager manager = activity.getSystemService(ActivityManager.class);
                report.put("nonce", nonce); report.put("pid", Process.myPid());
                if (seed) {
                    manager.setProcessStateSummary(summary(nonce)); report.put("seededActualSummary", true);
                } else {
                    File request = new File(activity.getFilesDir(), "exit-history-" + nonce + ".request.json");
                    JSONObject input = new JSONObject(new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8));
                    if (!request.delete()) throw new IllegalStateException("Cannot consume exit request");
                    List<ApplicationExitInfo> own = manager.getHistoricalProcessExitReasons(activity.getPackageName(), 0, 0);
                    List<ApplicationExitInfo> implicit = manager.getHistoricalProcessExitReasons(null, 0, 0);
                    ApplicationExitInfo selected = ownedTarget(activity, own, input);
                    ownedTarget(activity, implicit, input);
                    int target = input.getInt("targetPid");
                    require(Arrays.equals(summary(input.getString("seedNonce")), selected.getProcessStateSummary()), "Actual OS process summary changed");
                    List<ApplicationExitInfo> exact = manager.getHistoricalProcessExitReasons(activity.getPackageName(), target, 0);
                    require(exact.size() == 1 && exact.get(0).getPid() == target, "PID filter differs from actual record");
                    List<ApplicationExitInfo> newest = manager.getHistoricalProcessExitReasons(activity.getPackageName(), 0, 1);
                    require(newest.size() == 1 && newest.get(0).getTimestamp() == own.get(0).getTimestamp(), "Limit was applied before guest filtering");
                    denied(() -> manager.getHistoricalProcessExitReasons(input.getString("hostPackage"), 0, 0));
                    denied(() -> manager.getHistoricalProcessExitReasons(input.getString("peerPackage"), 0, 0));
                    boolean hasTrace;
                    try (InputStream trace = selected.getTraceInputStream()) { hasTrace = trace != null; }
                    report.put("actualTraceAvailable", hasTrace);
                    rejectChangedTraceArguments(selected, input.getString("hostPackage"), input.getInt("peerPid"));
                    report.put("records", rows(own)); report.put("target", rows(exact).getJSONObject(0));
                    report.put("ownNullPidAndLimit", true); report.put("peerAndHostDenied", true); report.put("traceArgumentsDenied", true);
                }
            } catch (Exception | AssertionError failure) {
                try { report.put("failure", failure.toString()); } catch (Exception ignored) { }
            } finally {
                try {
                    File output = new File(activity.getFilesDir(), "exit-history-" + nonce + ".json");
                    File temporary = new File(activity.getFilesDir(), output.getName() + ".tmp");
                    Files.write(temporary.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
                    Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception failure) { throw new IllegalStateException("Cannot publish exit probe", failure); }
                finally { activity.runOnUiThread(activity::finish); }
            }
        }, "MyliteExitProbe").start();
    }

    static byte[] summary(String nonce) { return ("\u0000Mybox-exit:" + nonce + "\u0000").getBytes(StandardCharsets.UTF_8); }
    private static ApplicationExitInfo ownedTarget(Activity activity, List<ApplicationExitInfo> list, JSONObject input) throws Exception {
        ApplicationExitInfo selected = null; JSONArray forbidden = input.getJSONArray("forbiddenPids");
        for (ApplicationExitInfo value : list) {
            require(value.getPackageUid() == Process.myUid(), "Exit UID differs from the actual guest ApplicationInfo view");
            require(value.getProcessName().equals(activity.getPackageName()) || value.getProcessName().startsWith(activity.getPackageName() + ":"), "Physical or peer process name escaped");
            for (int i = 0; i < forbidden.length(); i++) require(value.getPid() != forbidden.getInt(i), "Host or peer exit escaped");
            if (value.getPid() == input.getInt("targetPid")) selected = value;
        }
        require(selected != null, "Known killed guest has no actual owned OS record in this scope"); return selected;
    }
    private static JSONArray rows(List<ApplicationExitInfo> list) throws Exception {
        JSONArray result = new JSONArray();
        for (ApplicationExitInfo value : list) {
            JSONObject row = new JSONObject(); row.put("pid", value.getPid()); row.put("reason", value.getReason());
            row.put("status", value.getStatus()); row.put("timestamp", value.getTimestamp()); row.put("uid", value.getPackageUid());
            row.put("process", value.getProcessName()); row.put("summary", value.getProcessStateSummary() == null ? JSONObject.NULL
                    : Base64.encodeToString(value.getProcessStateSummary(), Base64.NO_WRAP)); result.put(row);
        }
        return result;
    }

    private static void rejectChangedTraceArguments(ApplicationExitInfo value, String host, int peer) throws Exception {
        Field field = ApplicationExitInfo.class.getDeclaredField("mAppTraceRetriever"); field.setAccessible(true);
        Object retriever = field.get(value); require(retriever != null, "OS has no application trace capability to test");
        IBinder binder = ((IInterface) retriever).asBinder();
        Field code = Class.forName("android.app.IAppTraceRetriever$Stub").getDeclaredField("TRANSACTION_getTraceFileDescriptor"); code.setAccessible(true);
        Parcel request = Parcel.obtain(), reply = Parcel.obtain(); boolean rejected = false;
        try {
            request.writeInterfaceToken("android.app.IAppTraceRetriever"); request.writeString(host);
            request.writeInt(Process.myUid()); request.writeInt(peer);
            require(binder.transact(code.getInt(null), request, reply, 0), "Trace capability has no real transaction");
            try { reply.readException(); } catch (SecurityException expected) { rejected = true; }
            require(rejected, "Trace capability accepted arbitrary host/peer arguments");
        } finally { request.recycle(); reply.recycle(); }
    }
    private interface Operation { void run() throws Exception; }
    private static void denied(Operation operation) throws Exception {
        boolean denied = false; try { operation.run(); } catch (SecurityException expected) { denied = true; }
        require(denied, "A foreign exit history query was accepted");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
