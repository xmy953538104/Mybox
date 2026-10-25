package io.github.xmy953538104.mybox.engine;

import java.util.Collections;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import io.github.xmy953538104.mybox.engine.packages.InstallerFixturePackages;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;
import static org.junit.Assert.*;

public final class GoogleAccountChangePolicyTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

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

    @Test public void exactReceiverAndBothPermissionDirectionsAreRequired() {
        assertTrue(allowed("com.android.vending", true, GoogleAccountChangePolicy.RECEIVER,
                GoogleAccountChangePolicy.PERMISSION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(allowed("other.package", true, GoogleAccountChangePolicy.RECEIVER,
                GoogleAccountChangePolicy.PERMISSION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(allowed("com.android.vending", false, GoogleAccountChangePolicy.RECEIVER,
                GoogleAccountChangePolicy.PERMISSION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(allowed("com.android.vending", true, "other.Receiver", GoogleAccountChangePolicy.PERMISSION, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(allowed("com.android.vending", true, GoogleAccountChangePolicy.RECEIVER, null, GoogleAccountChangePolicy.PERMISSION));
        assertFalse(allowed("com.android.vending", true, GoogleAccountChangePolicy.RECEIVER, GoogleAccountChangePolicy.PERMISSION, null));
    }

    @Test public void receiverShapeDoesNotGrantAnUntrustedApkTheGoogleRole() throws Exception {
        PackageStore store = InstallerFixturePackages.store(temporary.newFolder("store"));
        java.io.File apk = InstallerFixturePackages.apk(temporary.newFolder("inputs"), "com.android.vending", 1, null, "signer-a");
        assertFalse(GoogleAccountChangePolicy.allowsReceiver(store, store.install(Collections.singletonList(apk)),
                GoogleAccountChangePolicy.RECEIVER, true, GoogleAccountChangePolicy.PERMISSION, GoogleAccountChangePolicy.PERMISSION));
    }

    private static boolean allowed(String name, boolean exported, String receiver, String senderPermission, String receiverPermission) {
        return GoogleAccountChangePolicy.receiverMatches(name, receiver,
                exported, senderPermission, receiverPermission);
    }
}
