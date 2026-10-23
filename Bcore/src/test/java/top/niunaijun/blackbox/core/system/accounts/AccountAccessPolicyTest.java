package top.niunaijun.blackbox.core.system.accounts;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AccountAccessPolicyTest {
    @Test public void unknownPidAndCrossUserCannotReadCredentials() {
        assertThrows(SecurityException.class, () -> AccountAccessPolicy.enforceCaller(-1, 0, true));
        assertThrows(SecurityException.class, () -> AccountAccessPolicy.enforceCaller(1, 0, true));
        assertThrows(SecurityException.class, () -> AccountAccessPolicy.enforceCaller(0, 0, false));
        AccountAccessPolicy.enforceCaller(0, 0, true);
    }

    @Test public void guestCannotClaimAuthenticatorPackageOrReadItsPasswordAndToken() {
        assertThrows(SecurityException.class, () -> AccountAccessPolicy.enforceClaimedPackage(
                "test.untrusted.guest", "com.google.android.gms"));
        assertFalse(AccountAccessPolicy.sameActualSigners(new String[]{"evil-key"}, new String[]{"microg-key"}));
        assertFalse(AccountAccessPolicy.sameActualSigners(new String[0], new String[0]));
        assertFalse(AccountAccessPolicy.sameActualSigners(null, new String[]{"microg-key"}));
        assertTrue(AccountAccessPolicy.sameActualSigners(new String[]{"microg-key"}, new String[]{"microg-key"}));
    }
}
