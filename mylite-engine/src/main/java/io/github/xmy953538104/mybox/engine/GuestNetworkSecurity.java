package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import java.security.Security;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Each executor owns one guest; its manifest policy must precede guest Application construction. */
final class GuestNetworkSecurity {
    static void prepare(Object thread, Object loadedApk) throws Exception {
        long operation = EngineDiagnostics.begin("network", "guest_policy", 0);
        try {
            Class<?> contextImpl = Class.forName("android.app.ContextImpl");
            Context context = (Context) RuntimeReflection.invoke(null, RuntimeReflection.method(contextImpl,
                    "createAppContext", Class.forName("android.app.ActivityThread"),
                    Class.forName("android.app.LoadedApk")), thread, loadedApk);
            Class<?> source = Class.forName("android.security.net.config.ConfigSource");
            Class<?> configType = Class.forName("android.security.net.config.ApplicationConfig");
            Object manifest = Class.forName("android.security.net.config.ManifestConfigSource")
                    .getConstructor(Context.class).newInstance(context);
            Object config = configType.getConstructor(source).newInstance(manifest);
            // Force XML validation before publishing either process-wide policy.
            RuntimeReflection.invoke(config, RuntimeReflection.method(configType, "hasPerDomainConfigs"));
            Class<?> policyType = Class.forName("libcore.net.NetworkSecurityPolicy");
            Object policy = Class.forName("android.security.net.config.ConfigNetworkSecurityPolicy")
                    .getConstructor(configType).newInstance(config);
            if (Security.getProvider("AndroidNSSP") == null)
                throw new IllegalStateException("Android manifest trust provider is unavailable");
            RuntimeReflection.invoke(null, RuntimeReflection.method(configType, "setDefaultInstance", configType), config);
            RuntimeReflection.invoke(null, RuntimeReflection.method(policyType, "setInstance", policyType), policy);
            TrustManagerFactory trust = TrustManagerFactory.getInstance("PKIX", "AndroidNSSP");
            trust.init((java.security.KeyStore) null);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, trust.getTrustManagers(), null);
            // Host startup may already have cached both defaults in this executor.
            SSLContext.setDefault(tls);
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            EngineDiagnostics.event("network", "guest_policy_ready", operation, 1);
        } catch (Exception | LinkageError failure) {
            EngineDiagnostics.failure("network", "guest_policy", operation, failure);
            throw failure;
        }
    }

    private GuestNetworkSecurity() { }
}
