package io.github.xmy953538104.mybox.fixture.activities;

import android.app.Activity;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import java.io.File;
import org.json.JSONObject;

/** Cross-process child, kept live until the instrumentation observes its real server ACKs. */
public final class RecordProbeActivity extends Activity {
    private JSONObject report;
    private boolean waiting;
    private int newIntents;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            report = ActivityProbe.describe(this);
            Bundle reserved = getIntent().getBundleExtra(ActivityProbe.RESERVED);
            String host = getIntent().getStringExtra(ActivityProbe.HOST);
            ActivityProbe.denied(this, host, reserved, new Binder(), "not attached");
            ActivityProbe.denied(this, host, reserved, ActivityProbe.token(this), "rebound");
            report.put("fakePhysicalTokenRejected", true);
            report.put("claimedTokenReuseRejected", true);
            setContentView(new android.widget.TextView(this));
        } catch (Throwable failure) { ActivityProbe.failure(this, failure); }
    }
    @Override protected void onResume() {
        super.onResume();
        if (waiting || report == null) return;
        waiting = true;
        try { ActivityProbe.write(this, "activity-remote.json", report); }
        catch (Exception failure) { ActivityProbe.failure(this, failure); return; }
        Handler handler = new Handler(getMainLooper());
        long deadline = SystemClock.elapsedRealtime() + 15000;
        handler.post(new Runnable() {
            @Override public void run() {
                if (new File(getFilesDir(), "activity-finish").isFile()) {
                    setResult(RESULT_OK, new Intent().putExtra("report", report.toString()));
                    finish();
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    ActivityProbe.failure(RecordProbeActivity.this, new AssertionError("Instrumentation did not release the Activity"));
                } else handler.postDelayed(this, 50);
            }
        });
    }
    @Override protected void onNewIntent(Intent incoming) {
        super.onNewIntent(incoming);
        try {
            if (incoming.hasExtra("io.github.xmy953538104.mybox.engine.LAUNCH")
                    || incoming.getIntExtra("value", -1) != 42
                    || !"application/x-mylite-fixture".equals(incoming.getType())
                    || !getIntent().getData().equals(incoming.getData())
                    || !getIntent().getComponent().equals(incoming.getComponent())
                    || !getIntent().getExtras().getBinder("echo").equals(incoming.getExtras().getBinder("echo"))) {
                throw new AssertionError("Framework new Intent still contains the physical envelope");
            }
            JSONObject delivered = new JSONObject().put("instance", report.getString("instance"))
                    .put("count", ++newIntents).put("task", getTaskId());
            ActivityProbe.write(this, "activity-new-intent.json", delivered);
        } catch (Throwable failure) { ActivityProbe.failure(this, failure); }
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        try { ActivityProbe.write(this, "activity-remote-destroyed.json", report); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
