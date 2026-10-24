package io.github.xmy953538104.mybox.uiauth;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.widget.TextView;

public class AuthActivity extends Activity {
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private IBinder observer;
    private String tag;
    private boolean completed, scheduled;
    private final Binder control = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != FIRST_CALL_TRANSACTION) return false;
            Bundle input = data.readBundle(getClass().getClassLoader());
            main.post(() -> {
                try {
                    if ("launch".equals(input.getString("action"))) {
                        startActivity(input.getParcelable("intent")); publish("auth-command", input.getString("tag"), null);
                    } else complete();
                } catch (Throwable failure) {
                    Bundle result = new Bundle(); result.putString("failure", failure.toString()); publish("auth-error", input.getString("tag"), result);
                }
            });
            reply.writeNoException(); return true;
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); observer = getIntent().getExtras().getBinder("observer"); tag = getIntent().getStringExtra("tag");
        TextView title = new TextView(this); title.setText("Independent authenticator UI"); setContentView(title);
        Bundle value = new Bundle(); value.putBundle("frozen", getIntent().getBundleExtra("frozen"));
        value.putBoolean("ticketStripped", !getIntent().hasExtra("io.github.xmy953538104.mybox.ACCOUNT_UI_TICKET"));
        publish("auth-created", tag, value);
    }
    @Override public void onResume() {
        super.onResume(); publish("auth-resumed", tag, null);
        if (!scheduled && !getIntent().getBooleanExtra("hold", false)) { scheduled = true; main.post(this::complete); }
    }
    private void complete() {
        if (completed) return; completed = true;
        AccountManager manager = AccountManager.get(this); Account account = new Account(tag, FixtureAuthenticator.TYPE);
        if (!manager.addAccountExplicitly(account, null, null)) throw new IllegalStateException("Fixture account was not newly added");
        if (!manager.setAccountVisibility(account, getIntent().getStringExtra("consumer"), AccountManager.VISIBILITY_VISIBLE)) {
            throw new IllegalStateException("Fixture consumer visibility was not granted");
        }
        AccountAuthenticatorResponse response = getIntent().getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
        if (response == null) throw new IllegalStateException("Original authenticator response is missing");
        Bundle result = new Bundle(); result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
        result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type); response.onResult(result);
        publish("auth-completed", tag, null);
        setResult(RESULT_OK, new Intent().putExtra("tag", tag)); finish();
    }
    @Override public void onDestroy() { publish("auth-destroyed", tag, null); super.onDestroy(); }
    private void publish(String event, String valueTag, Bundle values) {
        Bundle value = values == null ? new Bundle() : new Bundle(values);
        value.putString("event", event); value.putString("tag", valueTag); value.putString("package", getPackageName());
        value.putString("class", getClass().getName()); value.putInt("pid", android.os.Process.myPid()); value.putInt("task", getTaskId());
        value.putBinder("control", control);
        Parcel input = Parcel.obtain(), reply = Parcel.obtain();
        try { input.writeBundle(value); observer.transact(IBinder.FIRST_CALL_TRANSACTION, input, reply, 0); reply.readException(); }
        catch (android.os.RemoteException failure) { throw new IllegalStateException(failure); }
        finally { input.recycle(); reply.recycle(); }
    }
}
