package io.github.xmy953538104.mybox.fixture.accounts;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/** Self-owned offline test identities; this fixture never requests a Google token. */
public class FixtureAuthenticatorService extends Service {
    public static final String TYPE = "io.github.xmy953538104.mybox.fixture.account";
    @Override public IBinder onBind(Intent intent) {
        return AccountManager.ACTION_AUTHENTICATOR_INTENT.equals(intent.getAction()) ? new Authenticator(this).getIBinder() : null;
    }

    private static final class Authenticator extends AbstractAccountAuthenticator {
        private final Context context;
        Authenticator(Context context) { super(context); this.context = context; }
        @Override public Bundle editProperties(AccountAuthenticatorResponse response, String type) { return new Bundle(); }
        @Override public Bundle addAccount(AccountAuthenticatorResponse response, String type, String tokenType, String[] features, Bundle options) {
            if (options != null && options.getBoolean("fixture-interactive")) {
                Intent intent = new Intent(context, FixtureAccountActivity.class);
                intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
                Bundle result = new Bundle(); result.putParcelable(AccountManager.KEY_INTENT, intent); return result;
            }
            Account account = new Account("fixture-async", TYPE);
            AccountManager.get(context).addAccountExplicitly(account, "fixture-only-password", null);
            return identity(account);
        }
        @Override public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account, Bundle options) {
            Bundle result = new Bundle(); result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true); return result;
        }
        @Override public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account, String tokenType, Bundle options) {
            if ("fixture-error".equals(tokenType)) {
                Bundle error = new Bundle(); error.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_BAD_ARGUMENTS); return error;
            }
            Bundle result = identity(account);
            result.putString(AccountManager.KEY_AUTHTOKEN, "fixture-token:" + tokenType);
            result.putInt("fixtureAuthenticatorPid", android.os.Process.myPid());
            result.putLong("android.accounts.expiry", System.currentTimeMillis() + 60_000);
            return result;
        }
        @Override public String getAuthTokenLabel(String tokenType) { return "Offline fixture token"; }
        @Override public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account, String tokenType, Bundle options) { return identity(account); }
        @Override public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account, String[] features) {
            boolean accepted = true;
            for (String feature : features) if (!"fixture-supported".equals(feature)) accepted = false;
            Bundle result = new Bundle(); result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, accepted); return result;
        }
        private static Bundle identity(Account account) {
            Bundle result = new Bundle(); result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type); return result;
        }
    }
}
