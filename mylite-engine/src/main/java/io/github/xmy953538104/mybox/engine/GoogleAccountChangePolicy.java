package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;

/** Local container interoperability for one event; this does not grant a Google signature permission. */
final class GoogleAccountChangePolicy {
    static final String ACTION = "com.google.android.gms.auth.GOOGLE_ACCOUNT_CHANGE";
    static final String PERMISSION = "com.google.android.gms.auth.permission.GOOGLE_ACCOUNT_CHANGE";
    static final String RECEIVER = "com.google.android.finsky.accounts.impl.AccountsChangedReceiver";

    static void requireSender(Engine engine, InstalledPackage sender, Intent intent, String required, boolean ordered) throws Exception {
        if (!requestMatches(sender.packageName, intent.getPackage(), intent.getAction(), required)
                || ordered || intent.getFlags() != 0 || intent.getSelector() != null || intent.getClipData() != null
                || intent.getData() != null || intent.getType() != null || intent.getCategories() != null
                || intent.getComponent() != null && !RECEIVER.equals(intent.getComponent().getClassName())) {
            throw new SecurityException("Unsupported cross-package Google broadcast");
        }
        TrustedMicrog.require(engine.hostContext(), sender);
    }

    static boolean requestMatches(String sender, String target, String action, String requiredPermission) {
        return "com.google.android.gms".equals(sender) && "com.android.vending".equals(target)
                && ACTION.equals(action) && PERMISSION.equals(requiredPermission);
    }

    static boolean allowsReceiver(PackageStore packages, InstalledPackage recipient, String receiverName,
                                  boolean exported, String senderPermission, String receiverPermission) throws java.io.IOException {
        return InstallerPolicy.isGoogleStore(packages, recipient) && receiverMatches(recipient.packageName, receiverName,
                exported, senderPermission, receiverPermission);
    }

    static boolean receiverMatches(String packageName, String receiverName, boolean exported,
                                   String senderPermission, String receiverPermission) {
        return "com.android.vending".equals(packageName) && RECEIVER.equals(receiverName)
                && exported && PERMISSION.equals(senderPermission) && PERMISSION.equals(receiverPermission);
    }

    private GoogleAccountChangePolicy() { }
}
