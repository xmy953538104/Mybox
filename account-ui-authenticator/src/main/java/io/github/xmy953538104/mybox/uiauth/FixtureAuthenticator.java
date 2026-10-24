package io.github.xmy953538104.mybox.uiauth;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

public final class FixtureAuthenticator extends Service {
    static final String TYPE = "io.github.xmy953538104.mybox.ui.fixture";
    @Override public IBinder onBind(Intent intent) { return new Authenticator(this).getIBinder(); }
    private static final class Authenticator extends AbstractAccountAuthenticator {
        private final Context context;
        Authenticator(Context context) { super(context); this.context = context; }
        @Override public Bundle addAccount(AccountAuthenticatorResponse response, String type, String token, String[] features, Bundle options) {
            if (!TYPE.equals(type) || options == null) throw new IllegalArgumentException("Invalid fixture request");
            String mode = options.getString("mode");
            Intent intent = new Intent(context, "singleTask".equals(mode) ? SingleTaskActivity.class
                    : "singleInstance".equals(mode) ? SingleInstanceActivity.class : AuthActivity.class);
            intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
            intent.putExtra("tag", options.getString("tag")); intent.putExtra("consumer", options.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME));
            Bundle extras = new Bundle(); extras.putBinder("observer", options.getBinder("observer"));
            Bundle frozen = new Bundle(); frozen.putString("value", "authenticator:" + options.getString("tag"));
            intent.putExtras(extras).putExtra("frozen", frozen).putExtra("hold", options.getBoolean("hold"))
                    .putExtra("hidden", "hidden".equals(mode));
            Bundle result = new Bundle(); result.putParcelable(AccountManager.KEY_INTENT, intent); return result;
        }
        @Override public Bundle editProperties(AccountAuthenticatorResponse r, String type) { return unsupported(); }
        @Override public Bundle confirmCredentials(AccountAuthenticatorResponse r, Account a, Bundle options) { return unsupported(); }
        @Override public Bundle getAuthToken(AccountAuthenticatorResponse r, Account a, String type, Bundle options) { return unsupported(); }
        @Override public String getAuthTokenLabel(String type) { return "fixture"; }
        @Override public Bundle updateCredentials(AccountAuthenticatorResponse r, Account a, String type, Bundle options) { return unsupported(); }
        @Override public Bundle hasFeatures(AccountAuthenticatorResponse r, Account a, String[] features) { Bundle b = new Bundle(); b.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true); return b; }
        private static Bundle unsupported() { Bundle b = new Bundle(); b.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION); return b; }
    }
}
