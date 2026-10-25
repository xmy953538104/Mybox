package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Ordinary guest with no dependency on any container engine. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String mode = "components";
        File request = new File(getFilesDir(), "fixture-mode");
        try {
            if (request.isFile()) {
                mode = new String(java.nio.file.Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8);
                if (!request.delete()) throw new java.io.IOException("Cannot consume fixture mode");
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        JSONObject report = new JSONObject();
        try {
            report.put("package", getPackageName());
            report.put("pid", android.os.Process.myPid());
            report.put("dataDir", getApplicationInfo().dataDir);
            report.put("applicationCreated", new File(getFilesDir(), "application-created.txt").isFile());
            report.put("providerCreated", new File(getFilesDir(), "provider-created.txt").isFile());
            File file = new File(getFilesDir(), "ordinary-file.txt");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(23); out.getFD().sync(); }
            report.put("contextFile", file.isFile());
            try (SQLiteDatabase database = openOrCreateDatabase("fixture.db", MODE_PRIVATE, null)) {
                database.execSQL("CREATE TABLE IF NOT EXISTS state(value INTEGER)");
                database.execSQL("INSERT INTO state VALUES(42)");
                report.put("sqlite", android.database.DatabaseUtils.longForQuery(database,
                        "SELECT count(*) FROM state WHERE value=42", null) > 0);
            }
            try {
                File nativeFile = new File("/data/data/" + getPackageName() + "/files/native-route.txt");
                try (FileOutputStream out = new FileOutputStream(nativeFile)) { out.write(41); out.getFD().sync(); }
                report.put("hardcodedPath", nativeFile.isFile());
            } catch (Exception failure) { report.put("hardcodedPath", false); }
        } catch (Throwable failure) {
            try { report.put("failure", failure.getClass().getName()); } catch (Exception ignored) { }
        }
        try (FileOutputStream out = openFileOutput("engine-fixture.json.tmp", MODE_PRIVATE)) {
            out.write(report.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        if (!new File(getFilesDir(), "engine-fixture.json.tmp").renameTo(new File(getFilesDir(), "engine-fixture.json"))) throw new IllegalStateException("Cannot publish fixture report");
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView text = new TextView(this);
        text.setText(report.toString());
        body.addView(text);
        Button next = new Button(this);
        next.setText("Open second guest Activity");
        next.setOnClickListener(view -> startActivity(new Intent(this, SecondActivity.class)));
        body.addView(next);
        setContentView(body);
        if (mode.startsWith("activity-health:")) {
            publishActivityHealth(mode.substring("activity-health:".length()));
            return;
        }
        if (mode.startsWith("native-path:")) {
            PathOperationsProbe.run(this, mode.substring("native-path:".length()));
            return;
        }
        if (mode.startsWith("exit-history-seed:")) {
            if (android.os.Build.VERSION.SDK_INT < 30) throw new UnsupportedOperationException("OS exit history requires API 30");
            ExitHistoryProbe.run(this, mode.substring("exit-history-seed:".length()), true);
            return;
        }
        if (mode.startsWith("exit-history:")) {
            if (android.os.Build.VERSION.SDK_INT < 30) throw new UnsupportedOperationException("OS exit history requires API 30");
            ExitHistoryProbe.run(this, mode.substring("exit-history:".length()), false);
            return;
        }
        if (mode.startsWith("telephony-registry:")) {
            TelephonyRegistryProbe.run(this, mode.substring("telephony-registry:".length()));
            return;
        }
        if (mode.startsWith("package-cache:")) {
            PackageCacheProbe.run(this, mode.substring("package-cache:".length()));
            return;
        }
        if (mode.startsWith("jobs-stop-finish:")) {
            io.github.xmy953538104.mybox.fixture.jobs.JobProbe.runStopFinishRace(this, mode.substring("jobs-stop-finish:".length()));
            return;
        }
        switch (mode) {
            case "notification-pending": io.github.xmy953538104.mybox.fixture.pending.NotificationPendingProbe.run(this); break;
            case "activity-pending": io.github.xmy953538104.mybox.fixture.pending.ActivityPendingProbe.run(this); break;
            case "locales-set": case "locales-reset":
                startActivity(new Intent(this, LocaleProbe.class).putExtra("reset", mode.equals("locales-reset"))); break;
            case "web-input": WebInputProbe.run(this); break;
            case "windows": WindowProbe.run(this); break;
            case "capacity": CapacityProbe.run(this); break;
            case "connectivity": ConnectivityProbe.run(this); break;
            case "jobs": io.github.xmy953538104.mybox.fixture.jobs.JobProbe.run(this); break;
            case "jobs-recover": io.github.xmy953538104.mybox.fixture.jobs.JobProbe.runRecovery(this); break;
            case "jobs-expedited": io.github.xmy953538104.mybox.fixture.jobs.JobProbe.runExpedited(this); break;
            case "jobs-persisted": io.github.xmy953538104.mybox.fixture.jobs.JobProbe.runPersisted(this); break;
            case "component-state": ComponentStateProbe.run(this, false); break;
            case "component-state-resume": ComponentStateProbe.run(this, true); break;
            case "content-observers":
                io.github.xmy953538104.mybox.fixture.content.ObserverProbe.run(this);
                break;
            case "clipboard":
                io.github.xmy953538104.mybox.fixture.clipboard.ClipboardProbe.run(this);
                break;
            case "alarms":
                io.github.xmy953538104.mybox.fixture.alarm.AlarmProbe.run(this, false);
                break;
            case "alarm-recover":
                io.github.xmy953538104.mybox.fixture.alarm.AlarmProbe.run(this, true);
                break;
            case "pending":
                io.github.xmy953538104.mybox.fixture.pending.PendingProbe.run(this, () -> { });
                break;
            case "accounts":
                io.github.xmy953538104.mybox.fixture.accounts.AccountProbe.run(this);
                break;
            case "installer":
                io.github.xmy953538104.mybox.fixture.installer.InstallerProbe.run(this);
                break;
            case "broadcast":
                io.github.xmy953538104.mybox.fixture.broadcast.BroadcastProbe.run(this);
                break;
            case "broadcast-permissions":
                io.github.xmy953538104.mybox.fixture.broadcast.permissions.PermissionProbe.run(this);
                break;
            case "components":
                ComponentProbe.run(this);
                new android.os.Handler(getMainLooper()).post(() -> startActivity(new Intent(this, SecondActivity.class).putExtra("probe", 42)));
                break;
            case "providers": break;
            case "foreground":
                io.github.xmy953538104.mybox.fixture.foreground.ForegroundProbe.run(this);
                break;
            case "service-pending":
                io.github.xmy953538104.mybox.fixture.pending.ServicePendingProbe.run(this);
                break;
            case "activities":
                io.github.xmy953538104.mybox.fixture.activities.ActivityProbe.start(this);
                break;
            default: throw new IllegalStateException("Unknown fixture mode: " + mode);
        }
    }

    private void publishActivityHealth(String nonce) {
        if (!java.util.UUID.fromString(nonce).toString().equals(nonce)) throw new IllegalArgumentException("Invalid activity health nonce");
        if (android.os.Looper.myLooper() != getMainLooper()) throw new IllegalStateException("Activity health must run on the main thread");
        String name = "activity-health-" + nonce + ".json";
        try {
            JSONObject result = new JSONObject();
            result.put("nonce", nonce); result.put("pid", android.os.Process.myPid()); result.put("main_thread", true);
            try (FileOutputStream out = openFileOutput(name + ".tmp", MODE_PRIVATE)) {
                out.write(result.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            if (!new File(getFilesDir(), name + ".tmp").renameTo(new File(getFilesDir(), name))) throw new java.io.IOException("Cannot publish activity health");
        } catch (Exception failure) { throw new IllegalStateException("Cannot report activity health", failure); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        io.github.xmy953538104.mybox.fixture.activities.ActivityProbe.result(this, request, result, data);
    }
}
