package io.github.xmy953538104.mybox.probe;

import android.app.Activity;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
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
        if (getIntent().getBooleanExtra("runSessionProbe", false)) {
            TextView progress = new TextView(this);
            progress.setText("PackageInstaller 会话测试进行中…");
            setContentView(progress);
            SessionInstallerProbe.run(this, report -> showAndSave("session-result.json", report));
            return;
        }
        JSONObject result = new JSONObject();
        String packageName = getPackageName();
        try {
            result.put("contextPackage", packageName);
            result.put("dataDir", getApplicationInfo().dataDir);
            try (SQLiteDatabase db = openOrCreateDatabase("probe.db", MODE_PRIVATE, null)) {
                db.execSQL("CREATE TABLE IF NOT EXISTS probe (value TEXT)");
                db.execSQL("INSERT INTO probe VALUES ('stored')");
                try (Cursor cursor = db.rawQuery("SELECT value FROM probe LIMIT 1", null)) {
                    result.put("sqlite", cursor.moveToFirst() && "stored".equals(cursor.getString(0)));
                }
            }
        } catch (Throwable failure) { recordFailure(result, "sqliteError", failure); }
        try {
            Bundle provider = getContentResolver().call(Uri.parse("content://" + packageName + ".provider"), "probe", null, null);
            result.put("provider", provider != null && packageName.equals(provider.getString("package"))
                    && provider.getInt("pid") != android.os.Process.myPid());
            result.put("providerProcessBoundary", MainProcessProvider.created && provider != null
                    && !provider.getBoolean("mainProviderCreated", true));
        } catch (Throwable failure) { recordFailure(result, "providerError", failure); }
        try {
            Class<?> version = getClassLoader().loadClass("org.apache.http.ProtocolVersion");
            Object http = version.getConstructor(String.class, int.class, int.class).newInstance("HTTP", 1, 1);
            result.put("platformHttpLibrary", "HTTP/1.1".equals(http.toString()));
        } catch (Throwable failure) { recordFailure(result, "platformHttpError", failure); }
        try {
            android.webkit.WebView webView = new android.webkit.WebView(this);
            result.put("webViewProvider", webView.getSettings().getUserAgentString() != null);
            webView.destroy();
        } catch (Throwable failure) { recordFailure(result, "webViewError", failure); }
        try {
            boolean virtualGms = false;
            try { getPackageManager().getPackageInfo("com.google.android.gms", 0); virtualGms = true; }
            catch (android.content.pm.PackageManager.NameNotFoundException absent) { }
            boolean googleAuthenticator = false;
            for (AuthenticatorDescription authenticator : AccountManager.get(this).getAuthenticatorTypes()) {
                googleAuthenticator |= "com.google".equals(authenticator.type);
            }
            result.put("noSystemGoogleAccountFallback", virtualGms || !googleAuthenticator);
            boolean denied = false;
            try { AccountManager.get(this).getPassword(new Account("mybox-negative-test", "com.google")); }
            catch (SecurityException expected) { denied = true; }
            result.put("unauthorizedPasswordDenied", denied);
            denied = false;
            try { AccountManager.get(this).peekAuthToken(new Account("mybox-negative-test", "com.google"), "test-scope"); }
            catch (SecurityException expected) { denied = true; }
            result.put("unauthorizedTokenReadDenied", denied);
            denied = false;
            try { AccountManager.get(this).setAuthToken(new Account("mybox-negative-test", "com.google"), "test-scope", "not-a-real-token"); }
            catch (SecurityException expected) { denied = true; }
            result.put("unauthorizedTokenWriteDenied", denied);
        } catch (Throwable failure) { recordFailure(result, "accountError", failure); }
        try {
            System.loadLibrary("mybox-probe");
            File files = getFilesDir();
            int errno = nativeWrite("/data/data/" + packageName + "/files/native-probe.txt");
            result.put("nativeErrno", errno);
            result.put("nativeIo", errno == 0 && !new File(files, "native-probe.txt").exists()
                    && !new File(files, "native-probe.txt.renamed").exists());
        } catch (Throwable failure) { recordFailure(result, "nativeError", failure); }
        showAndSave("probe-result.json", result);
    }

    private static void recordFailure(JSONObject result, String phase, Throwable failure) {
        try { result.put(phase, failure.getClass().getName() + ": " + failure.getMessage()); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private void showAndSave(String filename, JSONObject result) {
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), filename))) {
            output.write(result.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        TextView text = new TextView(this);
        text.setText(result.toString());
        text.setTextIsSelectable(true);
        setContentView(text);
    }
}
