package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.os.Handler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Offline Chromium input, using only an explicitly synthetic clipboard value. */
public final class WebInputProbe {
    public static void run(Activity activity) {
        WebView web = new WebView(activity);
        web.getSettings().setJavaScriptEnabled(true);
        activity.setContentView(web);
        Handler handler = new Handler(activity.getMainLooper());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                publish(activity, "web-input-ready", "ready");
                handler.post(new Runnable() {
                    int attempts;
                    @Override public void run() {
                        if (++attempts > 600 || activity.isFinishing()) return;
                        web.evaluateJavascript("document.getElementById('email').value==='mylite_synthetic_input'", value -> {
                            if ("true".equals(value)) {
                                publish(activity, "web-input-pasted", "true");
                            } else handler.postDelayed(this, 100);
                        });
                    }
                });
            }
        });
        web.loadDataWithBaseURL("https://fixture.invalid/", "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<body style='padding:24px'><label for='email'>Mylite synthetic email</label><br>"
                + "<input id='email' type='email' aria-label='Mylite synthetic email' style='font-size:24px;width:90%;height:64px'></body>", "text/html", "UTF-8", null);
    }
    private static void publish(Activity activity, String name, String value) {
        try { Files.write(new File(activity.getFilesDir(), name).toPath(), value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception failure) { throw new IllegalStateException("Cannot publish synthetic WebView input result", failure); }
    }
    private WebInputProbe() { }
}
