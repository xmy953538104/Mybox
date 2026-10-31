package io.github.xmy953538104.mybox.networkfixture;

import android.app.Application;
import android.net.http.X509TrustManagerExtensions;
import android.security.NetworkSecurityPolicy;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.json.JSONObject;

/** Ordinary APK: no engine dependency; real manifest policy and local TLS peers. */
public final class PolicyApplication extends Application {
    private final SSLSocketFactory constructorTls, constructorHttps;
    private final int constructorTrust, constructorTlsTrust, constructorTarget;
    private final boolean cachedContextReplaced, cachedHttpsReplaced, frameworkTrustMatchesTls;
    public PolicyApplication() {
        try {
            constructorTarget = PolicyFactory.runtimeTarget();
            SSLContext context = SSLContext.getDefault();
            constructorTls = context.getSocketFactory();
            constructorHttps = HttpsURLConnection.getDefaultSSLSocketFactory();
            javax.net.ssl.TrustManager installed = PolicyFactory.installedTrustManager;
            constructorTlsTrust = System.identityHashCode(installed);
            cachedContextReplaced = PolicyFactory.warmedContext != null && context != PolicyFactory.warmedContext;
            cachedHttpsReplaced = PolicyFactory.warmedHttps != null && constructorHttps != PolicyFactory.warmedHttps;
            TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX"); factory.init((KeyStore) null);
            javax.net.ssl.TrustManager framework = factory.getTrustManagers()[0];
            constructorTrust = System.identityHashCode(framework);
            frameworkTrustMatchesTls = installed == framework;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @Override public void onCreate() {
        super.onCreate();
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                result.put("package", getPackageName());
                result.put("pid", android.os.Process.myPid());
                result.put("factoryVmTarget", PolicyFactory.vmTarget);
                result.put("constructorVmTarget", constructorTarget);
                result.put("manifestTarget", getApplicationInfo().targetSdkVersion);
                result.put("constructorTrust", constructorTrust);
                result.put("constructorTlsTrust", constructorTlsTrust);
                result.put("frameworkTrustMatchesTls", frameworkTrustMatchesTls);
                result.put("hostPolicyWarmed", PolicyFactory.warmedContext != null && !PolicyFactory.warmedCleartext);
                result.put("cachedContextReplaced", cachedContextReplaced);
                result.put("cachedHttpsReplaced", cachedHttpsReplaced);
                NetworkSecurityPolicy policy = NetworkSecurityPolicy.getInstance();
                result.put("cleartextAll", policy.isCleartextTrafficPermitted());
                result.put("cleartextAllowed", policy.isCleartextTrafficPermitted("sub.allowed.mylite.test"));
                result.put("cleartextOther", policy.isCleartextTrafficPermitted("other.mylite.test"));
                TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
                factory.init((KeyStore) null);
                result.put("trustProvider", factory.getProvider().getName());
                X509TrustManager manager = (X509TrustManager) factory.getTrustManagers()[0];
                X509TrustManagerExtensions trust = new X509TrustManagerExtensions(manager);
                X509Certificate leaf = certificate("leaf.pem");
                trust.checkServerTrusted(new X509Certificate[]{leaf}, "RSA", "trusted.mylite.test");
                result.put("customAnchorAccepted", true);
                try {
                    trust.checkServerTrusted(new X509Certificate[]{leaf}, "RSA", "pinned.mylite.test");
                    result.put("pinRejected", false);
                } catch (CertificateException expected) { result.put("pinRejected", true); }
                try {
                    trust.checkServerTrusted(new X509Certificate[]{certificate("untrusted.pem")}, "RSA", "trusted.mylite.test");
                    result.put("untrustedRejected", false);
                } catch (CertificateException expected) { result.put("untrustedRejected", true); }
                localTls(result, leaf);
            } catch (Throwable error) {
                try { result.put("error", error.getClass().getName() + ": " + error.getMessage()); }
                catch (org.json.JSONException ignored) { }
            }
            try {
                File pending = new File(getFilesDir(), "network-result.tmp");
                java.nio.file.Files.write(pending.toPath(), result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.nio.file.Files.move(pending.toPath(), new File(getFilesDir(), "network-result.json").toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception error) { throw new IllegalStateException(error); }
        }, "network-policy-probe").start();
    }

    private X509Certificate certificate(String asset) throws Exception {
        try (InputStream input = getAssets().open(asset)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private void localTls(JSONObject result, X509Certificate leaf) throws Exception {
        byte[] key;
        try (InputStream input = getAssets().open("test-key.pk8")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            key = bytes.toByteArray();
        }
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType()); keys.load(null);
        keys.setKeyEntry("test", KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key)),
                new char[0], new java.security.cert.Certificate[]{leaf});
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, new char[0]);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(managers.getKeyManagers(), null, null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Thread responder = new Thread(() -> {
                try {
                    for (int i = 0; i < 2; i++) try (SSLSocket socket = (SSLSocket) server.accept()) {
                        socket.setSoTimeout(5000); socket.startHandshake(); socket.getOutputStream().write(42);
                    }
                } catch (Throwable error) { failure.set(error); }
            }, "network-policy-peer");
            responder.setDaemon(true); responder.start();
            SSLSocketFactory[] factories = {constructorTls, constructorHttps};
            for (int i = 0; i < factories.length; i++) {
                try (SSLSocket socket = (SSLSocket) factories[i].createSocket("127.0.0.1", server.getLocalPort())) {
                    javax.net.ssl.SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(parameters);
                    socket.setSoTimeout(5000); socket.startHandshake();
                    result.put(i == 0 ? "defaultTlsHandshake" : "httpsDefaultTlsHandshake", socket.getInputStream().read() == 42);
                }
            }
            responder.join(6000);
            if (responder.isAlive()) throw new IllegalStateException("Local TLS peer did not stop");
            if (failure.get() != null) throw new IllegalStateException("Local TLS peer failed", failure.get());
        }
    }
}
