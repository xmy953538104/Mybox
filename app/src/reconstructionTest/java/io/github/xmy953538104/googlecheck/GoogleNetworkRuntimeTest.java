package io.github.xmy953538104.googlecheck;

import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;

/** Explicit diagnostic under the real Mybox UID; no cookies, account entry or TLS bypass. */
public final class GoogleNetworkRuntimeTest extends InstrumentationTestCase {
    public void testActualMyboxHttpsRoutesToGoogle() throws Exception {
        int failed = 0;
        for (String host : new String[]{"accounts.google.com", "android.clients.google.com", "play.google.com"}) {
            long start = SystemClock.elapsedRealtime();
            HttpsURLConnection connection = (HttpsURLConnection) new URL("https://" + host + "/").openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000); connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            try {
                int code = connection.getResponseCode(); assertTrue("Invalid HTTPS response", code >= 100 && code <= 599);
                System.out.println("MYLITE_HTTPS host=" + host + " code=" + code + " elapsedMs=" + (SystemClock.elapsedRealtime() - start));
            } catch (java.io.IOException error) {
                failed++;
                System.out.println("MYLITE_HTTPS host=" + host + " error=" + error.getClass().getName() + " elapsedMs=" + (SystemClock.elapsedRealtime() - start));
            } finally { connection.disconnect(); }
        }
        assertEquals("Google HTTPS failed under the actual Mybox UID; no credentials were used", 0, failed);
    }
}
