/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.common;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Executed only with the explicit JVM doubles in test_identity_self.py. */
public final class IdentitySelfTest {
    private static void rejected(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Untrusted identity was accepted");
        } catch (SecurityException expected) {
            // The trust boundary must fail closed.
        }
    }

    public static void main(String[] args) {
        MyboxIdentity.Identity play = new MyboxIdentity.Identity(10001, 321, 0, "com.android.vending", new String[]{"com.android.vending"});
        assert "com.android.vending".equals(play.checkSuggested("com.android.vending", 10001, 321));
        rejected(() -> play.checkSuggested("com.google.android.gms", 10001, 321));
        rejected(() -> play.checkSuggested("com.android.vending", 10002, 321));
        rejected(() -> play.checkSuggested("com.android.vending", 10001, 322));
        rejected(() -> new MyboxIdentity.Identity(-1, 321, 0, "com.android.vending", new String[]{"com.android.vending"}));
        rejected(() -> new MyboxIdentity.Identity(10001, 0, 0, "com.android.vending", new String[]{"com.android.vending"}));
        rejected(() -> new MyboxIdentity.Identity(10001, 321, 1, "com.android.vending", new String[]{"com.android.vending"}));
        rejected(() -> new MyboxIdentity.Identity(10001, 321, 0, "com.android.vending", new String[]{"another.package"}));
        rejected(() -> new MyboxIdentity.Identity(10001, 321, 0, null, new String[]{"com.android.vending"}));
        assert new MyboxIdentity.Identity(1000, 999, 0, "android", new String[]{"android"}).uid == 1000;
        assert play.isSelfOrSystem("com.android.vending");
        assert !play.isSelfOrSystem("com.google.android.gms");
        assert new MyboxIdentity.Identity(1000, 999, 0, "android", new String[]{"android"}).isSelfOrSystem("com.google.android.gms");
        assert !new MyboxIdentity.Identity(1000, 999, 0, "untrusted.package", new String[]{"untrusted.package"}).isSelfOrSystem("com.google.android.gms");
        selfDecisionAndTransport();
        System.out.println("Identity self-check passed: legitimate caller, forged package/UID/PID, malformed response and cross-user rejection.");
    }

    private static Context remote(String packageName, int uid) {
        Context context = new Context();
        Bundle response = new Bundle();
        response.putInt("uid", uid);
        response.putInt("userId", 0);
        response.putString("packageName", packageName);
        response.putStringArray("packages", new String[]{packageName});
        context.resolver.response = response;
        Binder.uid = Process.myUid(); Binder.pid = Process.myPid() + 1; Binder.clears = 0;
        return context;
    }

    private static void selfDecisionAndTransport() {
        Context local = remote("com.google.android.gms", 10001);
        local.resolver.failure = new SecurityException("Local calls must not need the identity provider");
        Binder.pid = Process.myPid();
        assert MyboxIdentity.isCallingSelf(local);
        assert local.manager.calls == 0 && local.resolver.calls == 0 && Binder.clears == 0;

        Context samePackage = remote("com.google.android.gms", 10001);
        assert MyboxIdentity.isCallingSelf(samePackage);
        assert samePackage.manager.calls == 1 && samePackage.resolver.calls == 1;
        assert samePackage.resolver.originalUid == Process.myUid();
        assert samePackage.resolver.originalPid == Process.myPid() + 1;
        assert Binder.uid == Process.myUid() && Binder.pid == Process.myPid() + 1 && Binder.clears == 1;

        Context otherGuest = remote("com.android.vending", 10002);
        assert !MyboxIdentity.isCallingSelf(otherGuest);
        assert otherGuest.resolver.calls == 1; // A shared physical UID is not self.

        Context anonymous = remote("com.google.android.gms", 10001);
        Binder.pid = 0;
        rejected(() -> MyboxIdentity.isCallingSelf(anonymous));
        assert anonymous.resolver.calls == 1 && anonymous.resolver.originalPid == 0;

        Context wrongUid = remote("com.google.android.gms", 10001);
        wrongUid.resolver.failure = new SecurityException("Physical UID rejected");
        Binder.uid = Process.myUid() + 1; Binder.pid = Process.myPid();
        rejected(() -> MyboxIdentity.isCallingSelf(wrongUid));
        assert wrongUid.resolver.calls == 1 && wrongUid.resolver.originalUid == Process.myUid() + 1;
        assert Binder.uid == Process.myUid() + 1 && Binder.pid == Process.myPid();

        Context wrongOwner = remote("com.google.android.gms", 10001);
        wrongOwner.manager.provider.packageName = "untrusted.provider";
        rejected(() -> MyboxIdentity.isCallingSelf(wrongOwner));
        assert wrongOwner.manager.calls == 1 && wrongOwner.resolver.calls == 0;

        Context unavailable = remote("com.google.android.gms", 10001);
        unavailable.resolver.failure = new SecurityException("Unregistered process");
        rejected(() -> MyboxIdentity.isCallingSelf(unavailable));
        assert unavailable.resolver.calls == 1 && Binder.pid == Process.myPid() + 1;

        Context empty = remote("com.google.android.gms", 10001);
        empty.resolver.response = null;
        rejected(() -> MyboxIdentity.isCallingSelf(empty));

        Context wrongUser = remote("com.google.android.gms", 10001);
        wrongUser.resolver.response.putInt("userId", 1);
        rejected(() -> MyboxIdentity.isCallingSelf(wrongUser));

        Context outsideContainer = remote("ignored", 10001);
        outsideContainer.manager.provider = null;
        assert MyboxIdentity.isCallingSelf(outsideContainer); // Preserve upstream UID behavior outside the container.
        Binder.uid = Process.myUid() + 1;
        assert !MyboxIdentity.isCallingSelf(outsideContainer);
        assert outsideContainer.resolver.calls == 0;
        System.out.println("Simulated isCallingSelf: local no-IPC, remote package identity, PID zero, wrong UID/provider, transport and reply rejection passed.");
    }
}
