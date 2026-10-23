package io.github.xmy953538104.mybox;

import android.test.InstrumentationTestCase;
import android.util.Log;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Checks the host UID's DNS/TLS route only; never submits account or registration data. */
public final class NetworkReachabilityTest extends InstrumentationTestCase {
    public void testGoogleEndpointsTlsReachable() throws Exception {
        StringBuilder failures = new StringBuilder();
        for (String address : new String[]{"https://accounts.google.com/", "https://android.clients.google.com/checkin",
                "https://play.google.com/"}) {
            URL url = new URL(address);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            try {
                int status = connection.getResponseCode();
                Log.i("MyboxNetworkProbe", url.getHost() + " HTTP " + status);
                if (status < 100 || status > 599) failures.append(url.getHost()).append(": no HTTP response; ");
            } catch (Exception error) {
                failures.append(url.getHost()).append(": ").append(error.getClass().getSimpleName()).append("; ");
            } finally { connection.disconnect(); }
        }
        assertEquals("Google DNS/TLS connectivity: " + failures, "", failures.toString());
    }
}
