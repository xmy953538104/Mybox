package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.test.InstrumentationTestCase;

public final class GoogleSignatureViewTest extends InstrumentationTestCase {
    public static void assertGoogleComparisons(Engine engine) throws Throwable {
        Object physical = RuntimeReflection.invoke(null, RuntimeReflection.method(Class.forName("android.app.ActivityThread"), "getPackageManager"));
        GuestSignatureQueries queries = new GuestSignatureQueries(engine, physical);
        String gms = "com.google.android.gms", store = "com.android.vending";
        assertEquals(PackageManager.SIGNATURE_MATCH, queries.query("checkSignatures", new Object[]{gms, store, 0}));
        assertEquals(PackageManager.SIGNATURE_MATCH, queries.query("checkSignatures", new Object[]{store, store, 0}));
        assertEquals(PackageManager.SIGNATURE_NO_MATCH, queries.query("checkSignatures", new Object[]{gms, engine.hostContext().getPackageName(), 0}));
        assertEquals(PackageManager.SIGNATURE_MATCH, queries.query("checkUidSignatures", new Object[]{engine.packages().get(gms).appId, engine.packages().get(store).appId}));
        assertEquals(PackageManager.SIGNATURE_UNKNOWN_PACKAGE, queries.query("checkUidSignatures", new Object[]{99999, 99999}));
        assertEquals(PackageManager.SIGNATURE_UNKNOWN_PACKAGE, queries.query("checkSignatures", new Object[]{"missing.fixture.app", "missing.fixture.app", 0}));
        assertFalse("Comparison must not replace the real microG signer", engine.packages().verifiedSigning(engine.packages().get(gms)).currentSigners
                .contains(GoogleSignatureView.hash("SHA-256", GoogleSignatureView.compatibilitySignature(engine, engine.packages().get(gms)))));
        System.out.println("MYLITE_GOOGLE_SIGNATURE_QUERIES verifiedStoreMicrog=true unknownUidRejected=true realMicrogSignerUnchanged=true");
    }
    public void testSigningViewUsesIndependentFrameworkObject() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        Context context = getInstrumentation().getTargetContext();
        PackageInfo real = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        Signature original = real.signingInfo.getApkContentsSigners()[0];
        SigningInfo view = GoogleSignatureView.signingInfo(original);
        assertNotSame(real.signingInfo, view);
        assertFalse(view.hasMultipleSigners());
        assertFalse(view.hasPastSigningCertificates());
        assertEquals(original, view.getApkContentsSigners()[0]);
        assertEquals(original, view.getSigningCertificateHistory()[0]);
        assertEquals(original, real.signingInfo.getApkContentsSigners()[0]);
    }
}
