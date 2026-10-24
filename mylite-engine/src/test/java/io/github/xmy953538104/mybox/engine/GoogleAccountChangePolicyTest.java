package io.github.xmy953538104.mybox.engine;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GoogleAccountChangePolicyTest {
    private static final String GOOGLE = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83";

    @Test public void exceptionHasOneExactSenderTargetActionAndReceiverPermission() {
        assertTrue(GoogleAccountChangePolicy.requestMatches("com.google.android.gms", "com.android.vending",
                GoogleAccountChangePolicy.ACTION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(GoogleAccountChangePolicy.requestMatches("other.app", "com.android.vending",
                GoogleAccountChangePolicy.ACTION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(GoogleAccountChangePolicy.requestMatches("com.google.android.gms", "other.app",
                GoogleAccountChangePolicy.ACTION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(GoogleAccountChangePolicy.requestMatches("com.google.android.gms", "com.android.vending",
                "other.action", GoogleAccountChangePolicy.PERMISSION));
        assertFalse(GoogleAccountChangePolicy.requestMatches("com.google.android.gms", "com.android.vending",
                GoogleAccountChangePolicy.ACTION, null));
    }

    @Test public void realSingleGoogleSignerAndBothPermissionDirectionsAreRequired() {
        assertTrue(allowed(GOOGLE, true, GoogleAccountChangePolicy.RECEIVER,
                GoogleAccountChangePolicy.PERMISSION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(allowed("displayed-google-compatibility-signature", true, GoogleAccountChangePolicy.RECEIVER,
                GoogleAccountChangePolicy.PERMISSION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(allowed(GOOGLE, false, GoogleAccountChangePolicy.RECEIVER,
                GoogleAccountChangePolicy.PERMISSION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(allowed(GOOGLE, true, "other.Receiver", GoogleAccountChangePolicy.PERMISSION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(allowed(GOOGLE, true, GoogleAccountChangePolicy.RECEIVER, null, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(allowed(GOOGLE, true, GoogleAccountChangePolicy.RECEIVER, GoogleAccountChangePolicy.PERMISSION, null));
        assertFalse(GoogleAccountChangePolicy.allowsReceiver("com.android.vending", Arrays.asList(GOOGLE, "extra"),
                GoogleAccountChangePolicy.RECEIVER, true, GoogleAccountChangePolicy.PERMISSION, GoogleAccountChangePolicy.PERMISSION));
    }

    private static boolean allowed(String signer, boolean exported, String receiver, String senderPermission, String receiverPermission) {
        return GoogleAccountChangePolicy.allowsReceiver("com.android.vending", Collections.singletonList(signer), receiver,
                exported, senderPermission, receiverPermission);
    }
}
