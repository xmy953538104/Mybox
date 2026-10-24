package io.github.xmy953538104.mybox.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public final class SecondActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Guest second Activity: " + getPackageName());
        setContentView(text);
        try {
            org.json.JSONObject report = new org.json.JSONObject();
            report.put("package", getPackageName());
            report.put("class", getClass().getName());
            report.put("pid", android.os.Process.myPid());
            report.put("probe", getIntent().getIntExtra("probe", -1));
            try (java.io.FileOutputStream out = openFileOutput("second-activity.json.tmp", MODE_PRIVATE)) {
                out.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!new java.io.File(getFilesDir(), "second-activity.json.tmp").renameTo(new java.io.File(getFilesDir(), "second-activity.json"))) throw new IllegalStateException("Cannot publish Activity report");
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
