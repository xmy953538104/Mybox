/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.common;

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
        System.out.println("Identity self-check passed: legitimate caller, forged package/UID/PID, malformed response and cross-user rejection.");
    }
}
