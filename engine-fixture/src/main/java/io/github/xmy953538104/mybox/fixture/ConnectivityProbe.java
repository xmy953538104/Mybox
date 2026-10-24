package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Real queries and OS callback delivery, with no manufactured network state. */
public final class ConnectivityProbe {
    public static void run(Activity activity) {
        ConnectivityManager manager = activity.getSystemService(ConnectivityManager.class);
        Handler handler = new Handler(activity.getMainLooper()); JSONObject result = new JSONObject();
        ConnectivityManager.NetworkCallback[] callbacks = new ConnectivityManager.NetworkCallback[2];
        boolean[] seen = new boolean[2], registered = new boolean[2];
        Runnable finish = () -> {
            try {
                for (int i = 0; i < 2; i++) if (registered[i]) { manager.unregisterNetworkCallback(callbacks[i]); registered[i] = false; }
                result.put("defaultCallback", seen[0]); result.put("listenCallback", seen[1]);
                File pending = new File(activity.getFilesDir(), "connectivity-result.tmp");
                Files.write(pending.toPath(), result.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(pending.toPath(), new File(activity.getFilesDir(), "connectivity-result.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        };
        try {
            Network active = manager.getActiveNetwork();
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
            result.put("package", activity.getPackageName());
            result.put("activeHandle", active == null ? 0 : active.getNetworkHandle());
            result.put("capabilitiesPresent", capabilities != null);
            result.put("networkCount", manager.getAllNetworks().length);
            Field field = ConnectivityManager.class.getDeclaredField("mService"); field.setAccessible(true);
            Object service = field.get(manager);
            Method query = Class.forName("android.net.IConnectivityManager").getMethod("getNetworkCapabilities", Network.class, String.class, String.class);
            try { query.invoke(service, active, "forged.network.package", null); result.put("foreignPackageDenied", false); }
            catch (InvocationTargetException failure) { if (!(failure.getCause() instanceof SecurityException)) throw failure; result.put("foreignPackageDenied", true); }
            for (int i = 0; i < 2; i++) {
                final int index = i;
                callbacks[i] = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) {
                        seen[index] = true;
                        if (seen[0] && seen[1]) handler.post(finish);
                    }
                };
            }
            manager.registerDefaultNetworkCallback(callbacks[0], handler); registered[0] = true;
            manager.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callbacks[1], handler); registered[1] = true;
            handler.postDelayed(() -> { if (registered[0] || registered[1]) finish.run(); }, 10000);
        } catch (Exception failure) {
            try { result.put("failure", failure.toString()); } catch (Exception ignored) { }
            finish.run();
        }
    }
    private ConnectivityProbe() { }
}
