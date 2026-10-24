package io.github.xmy953538104.mybox.fixture.activities;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;

/** A distinct guest component sharing the launcher's physical process/stub. */
public final class SameSlotActivity extends Activity {
    private JSONObject report;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            report = ActivityProbe.describe(this);
            ActivityProbe.write(this, "activity-local.json", report);
            new android.os.Handler(getMainLooper()).post(() -> startActivityForResult(
                    new Intent(getIntent()).setClass(this, RecordProbeActivity.class), ActivityProbe.REQUEST));
        } catch (Throwable failure) { ActivityProbe.failure(this, failure); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        try {
            if (request != ActivityProbe.REQUEST || result != RESULT_OK || data == null) throw new AssertionError("Child result is absent");
            JSONObject complete = new JSONObject().put("local", report)
                    .put("remote", new JSONObject(data.getStringExtra("report")));
            setResult(RESULT_OK, new Intent().putExtra("report", complete.toString()));
            finish();
        } catch (Throwable failure) { ActivityProbe.failure(this, failure); }
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        try { ActivityProbe.write(this, "activity-local-destroyed.json", report); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
