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
            if (Security.getProvider("AndroidNSSP") == null)
                throw new IllegalStateException("Android manifest trust provider is unavailable");
            RuntimeReflection.invoke(null, RuntimeReflection.method(configType, "setDefaultInstance", configType), config);
            installDefaults(config);
            EngineDiagnostics.event("network", "guest_policy_ready", operation, 1);
        } catch (Exception | LinkageError failure) {
            EngineDiagnostics.failure("network", "guest_policy", operation, failure);
            throw failure;
        }
    }

    static void applicationCreating() throws Exception {
        try {
            Class<?> type = Class.forName("android.security.net.config.ApplicationConfig");
            Object config = RuntimeReflection.invoke(null, RuntimeReflection.method(type, "getDefaultInstance"));
            if (config == null) throw new IllegalStateException("Framework Application network policy is missing");
            // LoadedApk has replaced the prepared config; trust updates must reach the same instance as TLS.
            int trustManager = installDefaults(config);
            EngineDiagnostics.event("network", "application_policy_ready", 0, trustManager);
        } catch (Exception | LinkageError failure) {
            EngineDiagnostics.failure("network", "application_policy", 0, failure); throw failure;
        }
    }

    private static int installDefaults(Object config) throws Exception {
        Class<?> policyType = Class.forName("libcore.net.NetworkSecurityPolicy");
        Object policy = Class.forName("android.security.net.config.ConfigNetworkSecurityPolicy")
                .getConstructor(config.getClass()).newInstance(config);
        RuntimeReflection.invoke(null, RuntimeReflection.method(policyType, "setInstance", policyType), policy);
        TrustManagerFactory trust = TrustManagerFactory.getInstance("PKIX", "AndroidNSSP");
        trust.init((java.security.KeyStore) null);
        javax.net.ssl.TrustManager[] managers = trust.getTrustManagers();
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, managers, null);
        SSLContext.setDefault(tls);
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        return System.identityHashCode(managers[0]);
    }

    private GuestNetworkSecurity() { }
}
