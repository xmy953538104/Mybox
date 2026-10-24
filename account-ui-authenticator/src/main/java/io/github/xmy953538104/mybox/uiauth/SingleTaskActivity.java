package io.github.xmy953538104.mybox.uiauth;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.content.Intent;
import android.os.Bundle;
import java.util.HashSet;
import java.util.Set;

/** This fixture explicitly supports a new account response for each real onNewIntent. */
public final class SingleTaskActivity extends UiFixtureActivity {
    private Intent current;
    private final Set<String> completed = new HashSet<>();
    @Override public void onCreate(Bundle saved) { super.onCreate(saved); receive(getIntent(), "single-created"); }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); receive(intent, "single-intent"); }
    @Override public void onResume() { super.onResume(); publish("single-resumed", current.getStringExtra("tag"), null); }
    private void receive(Intent intent, String event) {
        current = intent;
        Bundle result = new Bundle(); result.putBundle("frozen", intent.getBundleExtra("frozen"));
        result.putBoolean("ticketStripped", !intent.hasExtra("io.github.xmy953538104.mybox.ACCOUNT_UI_TICKET"));
        publish(event, intent.getStringExtra("tag"), result);
    }
    @Override void complete() {
        String tag = current.getStringExtra("tag");
        if (!completed.add(tag)) throw new IllegalStateException("Account response was completed twice");
        AccountManager manager = AccountManager.get(this); Account account = new Account(tag, FixtureAuthenticator.TYPE);
        if (!manager.addAccountExplicitly(account, null, null)) throw new IllegalStateException("Account was not newly added");
        if (!manager.setAccountVisibility(account, current.getStringExtra("consumer"), AccountManager.VISIBILITY_VISIBLE)) {
            throw new IllegalStateException("Consumer visibility was not granted");
        }
        AccountAuthenticatorResponse response = current.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
        if (response == null) throw new IllegalStateException("Original per-intent response is missing");
        Bundle result = new Bundle(); result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
        result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type); response.onResult(result);
        publish("single-completed", tag, null); // Stays alive for the next real framework delivery.
    }
}
