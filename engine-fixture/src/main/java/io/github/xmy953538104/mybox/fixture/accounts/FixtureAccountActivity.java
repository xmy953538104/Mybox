package io.github.xmy953538104.mybox.fixture.accounts;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.Activity;
import android.os.Bundle;

/** Deterministic offline counterpart of an authenticator's account creation UI. */
public final class FixtureAccountActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        AccountAuthenticatorResponse response = getIntent().getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
        if (response == null) throw new IllegalArgumentException("Fixture authenticator response is absent");
        Account account = new Account("fixture-ui", FixtureAuthenticatorService.TYPE);
        AccountManager.get(this).addAccountExplicitly(account, "fixture-only-password", null);
        Bundle result = new Bundle(); result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
        result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        response.onResult(result);
        finish();
    }
}
