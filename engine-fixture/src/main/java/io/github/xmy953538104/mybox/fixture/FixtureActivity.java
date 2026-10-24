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
        ComponentProbe.run(this);
        new android.os.Handler(getMainLooper()).post(() -> startActivity(new Intent(this, SecondActivity.class).putExtra("probe", 42)));
    }
}
