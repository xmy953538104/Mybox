package io.github.xmy953538104.mybox.networkfixture;

public final class PolicyFactory extends android.app.AppComponentFactory {
    static javax.net.ssl.SSLContext warmedContext;
    static javax.net.ssl.SSLSocketFactory warmedHttps;
    static boolean warmedCleartext;
    static java.security.Provider originalTlsProvider;
    static volatile javax.net.ssl.TrustManager installedTrustManager;
    @Override public ClassLoader instantiateClassLoader(ClassLoader loader, android.content.pm.ApplicationInfo info) {
        try {
            warmedContext = javax.net.ssl.SSLContext.getDefault();
            warmedContext.getSocketFactory();
            warmedHttps = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory();
            warmedCleartext = android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted();
            if (originalTlsProvider == null) {
                originalTlsProvider = javax.net.ssl.SSLContext.getInstance("TLS").getProvider();
                if (java.security.Security.insertProviderAt(new TrackingProvider(), 1) != 1)
                    throw new IllegalStateException("Test TLS observer provider was not installed");
            }
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        return super.instantiateClassLoader(loader, info);
    }

    /** Observes the supplied manager, then delegates all TLS to the unchanged platform provider. */
    public static final class TrackingProvider extends java.security.Provider {
        public TrackingProvider() {
            super("MyliteFixtureTLS", 1.0, "Test-only platform TLS observer");
            put("SSLContext.TLS", TrackingContext.class.getName());
        }
    }

    public static final class TrackingContext extends javax.net.ssl.SSLContextSpi {
        private final javax.net.ssl.SSLContext delegate;
        public TrackingContext() throws java.security.NoSuchAlgorithmException {
            delegate = javax.net.ssl.SSLContext.getInstance("TLS", originalTlsProvider);
        }
        @Override protected void engineInit(javax.net.ssl.KeyManager[] keys, javax.net.ssl.TrustManager[] trust,
                java.security.SecureRandom random) throws java.security.KeyManagementException {
            delegate.init(keys, trust, random);
            installedTrustManager = trust == null || trust.length == 0 ? null : trust[0];
        }
        @Override protected javax.net.ssl.SSLSocketFactory engineGetSocketFactory() { return delegate.getSocketFactory(); }
        @Override protected javax.net.ssl.SSLServerSocketFactory engineGetServerSocketFactory() { return delegate.getServerSocketFactory(); }
        @Override protected javax.net.ssl.SSLEngine engineCreateSSLEngine() { return delegate.createSSLEngine(); }
        @Override protected javax.net.ssl.SSLEngine engineCreateSSLEngine(String host, int port) { return delegate.createSSLEngine(host, port); }
        @Override protected javax.net.ssl.SSLSessionContext engineGetServerSessionContext() { return delegate.getServerSessionContext(); }
        @Override protected javax.net.ssl.SSLSessionContext engineGetClientSessionContext() { return delegate.getClientSessionContext(); }
        @Override protected javax.net.ssl.SSLParameters engineGetDefaultSSLParameters() { return delegate.getDefaultSSLParameters(); }
        @Override protected javax.net.ssl.SSLParameters engineGetSupportedSSLParameters() { return delegate.getSupportedSSLParameters(); }
    }
}
