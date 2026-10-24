package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.test.InstrumentationTestCase;

public final class GoogleSignatureViewTest extends InstrumentationTestCase {
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
