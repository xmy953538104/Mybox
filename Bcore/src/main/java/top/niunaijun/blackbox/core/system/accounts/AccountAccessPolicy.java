package top.niunaijun.blackbox.core.system.accounts;

import java.util.Arrays;
import java.util.HashSet;

final class AccountAccessPolicy {
    private AccountAccessPolicy() {}

    static void enforceCaller(int registeredUser, int requestedUser, boolean hostKernelUid) {
        if (!hostKernelUid || registeredUser < 0 || registeredUser != requestedUser) {
            throw new SecurityException("Account request is not from a registered guest in this user");
        }
    }

    static void enforceClaimedPackage(String actual, String claimed) {
        if (claimed != null && !actual.equals(claimed)) {
            throw new SecurityException("Account request package does not belong to its Binder caller");
        }
    }

    static boolean sameActualSigners(String[] caller, String[] authenticator) {
        return caller != null && authenticator != null && caller.length > 0 && authenticator.length > 0
                && new HashSet<>(Arrays.asList(caller)).equals(new HashSet<>(Arrays.asList(authenticator)));
    }
}
