package io.github.xmy953538104.mybox.probe;

import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class ProbeActivity extends Activity {
    private static native int nativeWrite(String filename);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        JSONObject result = new JSONObject();
        try {
            String packageName = getPackageName();
            result.put("contextPackage", packageName);
            result.put("dataDir", getApplicationInfo().dataDir);
            try (SQLiteDatabase db = openOrCreateDatabase("probe.db", MODE_PRIVATE, null)) {
                db.execSQL("CREATE TABLE IF NOT EXISTS probe (value TEXT)");
                db.execSQL("INSERT INTO probe VALUES ('stored')");
                try (Cursor cursor = db.rawQuery("SELECT value FROM probe LIMIT 1", null)) {
                    result.put("sqlite", cursor.moveToFirst() && "stored".equals(cursor.getString(0)));
                }
            }
            Bundle provider = getContentResolver().call(Uri.parse("content://" + packageName + ".provider"), "probe", null, null);
            result.put("provider", provider != null && packageName.equals(provider.getString("package"))
                    && provider.getInt("pid") != android.os.Process.myPid());
            System.loadLibrary("mybox-probe");
            int errno = nativeWrite("/data/data/" + packageName + "/files/native-probe.txt");
            result.put("nativeErrno", errno);
            result.put("nativeIo", errno == 0 && new File(getFilesDir(), "native-probe.txt").length() == 15);
        } catch (Throwable failure) {
            try { result.put("error", failure.getClass().getName() + ": " + failure.getMessage()); }
            catch (Exception ignored) { }
        }
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "probe-result.json"))) {
            output.write(result.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        TextView text = new TextView(this);
        text.setText(result.toString());
        text.setTextIsSelectable(true);
        setContentView(text);
    }
}
