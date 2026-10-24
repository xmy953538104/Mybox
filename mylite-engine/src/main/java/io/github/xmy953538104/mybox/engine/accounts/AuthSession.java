package io.github.xmy953538104.mybox.engine.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import io.github.xmy953538104.mybox.engine.CallerIdentity;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A bounded authenticator conversation, tied to a frozen consumer and real authenticator signer. */
final class AuthSession implements AuthenticatorRouter.Connection {
    private static final long REQUEST_TIMEOUT = 60_000, INTERACTIVE_TIMEOUT = 300_000;
    private static final Semaphore LIMIT = new Semaphore(32);
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> { Thread thread = new Thread(task, "Mybox-Account"); thread.setDaemon(true); return thread; });
    private final String operation;
    private final Bundle input;
    private final IVirtualAccountResponse consumerResponse;
    private final CallerIdentity consumer;
    private final AuthenticatorRegistration authenticator;
    private final AccountRepository repository;
    private final CallerResolver callers;
    private final AuthenticatorRouter router;
    private final PackageStore packages;
    private final Handler handler;
    private final AccountUiAuthorizer.Scope ui;
    private final long deadline = SystemClock.elapsedRealtime() + INTERACTIVE_TIMEOUT;
    private final Runnable timeout = () -> fail(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Virtual authenticator timed out");
    private final IBinder.DeathRecipient consumerDeath = this::close;
    private AuthenticatorRouter.Binding binding;
    private boolean closed, linked, acquired;

    private final Binder response = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(AccountWire.AUTHENTICATOR_RESPONSE); return true; }
            if (code < 1 || code > 3) return false;
            if ((flags & IBinder.FLAG_ONEWAY) != 0 || Binder.getCallingPid() <= 0) {
                throw new SecurityException("Authenticator callbacks must preserve their real PID");
            }
            data.enforceInterface(AccountWire.AUTHENTICATOR_RESPONSE);
            CallerIdentity owner = callers.resolve(null);
            if (!authenticator.owns(owner)) throw new SecurityException("Callback does not belong to the session authenticator");
            try {
                repository.requireOwner(owner, authenticator.accountType);
                if (code == 1) {
                    Bundle result = data.readInt() == 0 ? null : Bundle.CREATOR.createFromParcel(data);
                    accept(owner, result);
                } else if (code == 2) reschedule(REQUEST_TIMEOUT);
                else {
                    int error = data.readInt(); data.readString();
                    fail(error > 0 ? error : AccountManager.ERROR_CODE_INVALID_RESPONSE, "Authenticator rejected the request");
                }
            } catch (IOException | IllegalArgumentException | SecurityException | android.os.BadParcelableException failure) {
                fail(AccountManager.ERROR_CODE_INVALID_RESPONSE, "Authenticator response could not be validated");
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    AuthSession(String operation, Bundle input, IVirtualAccountResponse consumerResponse, CallerIdentity consumer,
            AuthenticatorRegistration authenticator, AccountRepository repository, CallerResolver callers,
            AuthenticatorRouter router, PackageStore packages, Handler handler, AccountUiAuthorizer.Scope ui) {
        this.operation = operation; this.input = new Bundle(input); this.consumerResponse = consumerResponse;
        this.consumer = consumer; this.authenticator = authenticator; this.repository = repository;
        this.callers = callers; this.router = router; this.packages = packages; this.handler = handler;
        this.ui = ui;
        response.attachInterface(null, AccountWire.AUTHENTICATOR_RESPONSE);
    }

    void start() {
        synchronized (this) {
            if (!LIMIT.tryAcquire()) { fail(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Too many active account requests"); return; }
            acquired = true;
            try { consumerResponse.asBinder().linkToDeath(consumerDeath, 0); linked = true; }
            catch (RemoteException dead) { close(); return; }
            reschedule(REQUEST_TIMEOUT);
        }
        try {
            WORK.execute(() -> {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (AuthSession.this) { if (closed) return; }
                    AuthenticatorRouter.Binding connected = router.bind(new ComponentName(authenticator.packageName, authenticator.className), 0, this);
                    synchronized (AuthSession.this) {
                        if (closed) closeBinding(connected); else binding = connected;
                    }
                } catch (Exception failure) {
                    fail(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Virtual authenticator binding failed");
                } finally { Binder.restoreCallingIdentity(identity); }
            });
        } catch (RejectedExecutionException full) { fail(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Account request queue is full"); }
    }

    @Override public void onConnected(IBinder service) {
        synchronized (this) { if (closed) return; }
        Parcel request = Parcel.obtain(), answer = Parcel.obtain();
        long identity = Binder.clearCallingIdentity();
        try {
            if (!AccountWire.AUTHENTICATOR.equals(service.getInterfaceDescriptor())) throw new SecurityException("Unexpected authenticator Binder interface");
            request.writeInterfaceToken(AccountWire.AUTHENTICATOR); request.writeStrongBinder(response);
            int code = writeRequest(request);
            if (!service.transact(code, request, answer, 0)) throw new RemoteException("Authenticator request transaction was rejected");
            if (answer.dataSize() > 0) answer.readException();
        } catch (Exception failure) {
            fail(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Virtual authenticator call failed");
        } finally { Binder.restoreCallingIdentity(identity); answer.recycle(); request.recycle(); }
    }

    @Override public void onDisconnected() { fail(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Virtual authenticator disconnected"); }

    private int writeRequest(Parcel data) {
        Bundle options = input.getBundle("options"); options = options == null ? new Bundle() : new Bundle(options);
        options.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, consumer.packageName);
        options.putInt(AccountManager.KEY_CALLER_UID, consumer.appId);
        options.putInt(AccountManager.KEY_CALLER_PID, consumer.pid);
        Account account = input.getParcelable("account");
        switch (operation) {
            case "addAccount":
                data.writeString(authenticator.accountType); data.writeString(input.getString("tokenType"));
                data.writeStringArray(input.getStringArray("features")); AccountWire.nullable(data, options); return 1;
            case "confirmCredentials": AccountWire.nullable(data, account); AccountWire.nullable(data, options); return 2;
            case "getAuthToken": AccountWire.nullable(data, account); data.writeString(input.getString("tokenType")); AccountWire.nullable(data, options); return 3;
            case "getAuthTokenLabel": data.writeString(input.getString("tokenType")); return 4;
            case "updateCredentials": AccountWire.nullable(data, account); data.writeString(input.getString("tokenType")); AccountWire.nullable(data, options); return 5;
            case "editProperties": data.writeString(authenticator.accountType); return 6;
            case "hasFeatures": AccountWire.nullable(data, account); data.writeStringArray(input.getStringArray("features")); return 7;
            case "removeAccount": AccountWire.nullable(data, account); return 8;
            case "isCredentialsUpdateSuggested": AccountWire.nullable(data, account); data.writeString(input.getString("statusToken")); return 14;
            default: throw new UnsupportedOperationException("Unsupported authenticator conversation");
        }
    }

    private synchronized void accept(CallerIdentity owner, Bundle supplied) throws IOException, RemoteException {
        if (closed) return;
        if (supplied == null) { fail(AccountManager.ERROR_CODE_INVALID_RESPONSE, "Authenticator returned no result"); return; }
        supplied.setClassLoader(Intent.class.getClassLoader());
        if (supplied.getInt(AccountManager.KEY_ERROR_CODE, 0) > 0) {
            fail(supplied.getInt(AccountManager.KEY_ERROR_CODE), "Authenticator rejected the request"); return;
        }
        Intent intent = supplied.getParcelable(AccountManager.KEY_INTENT);
        if (intent != null) {
            validateIntent(intent);
            Bundle result = new Bundle(); result.putParcelable(AccountManager.KEY_INTENT, ui.publish(owner, intent));
            if (input.getBoolean("expectActivity")) reschedule(INTERACTIVE_TIMEOUT); else close(true);
            try { AccountService.deliver(consumerResponse, result); }
            catch (RemoteException failure) { close(); throw failure; }
            return;
        }
        Bundle result = new Bundle();
        if ("hasFeatures".equals(operation) || "confirmCredentials".equals(operation)
                || "isCredentialsUpdateSuggested".equals(operation) || "removeAccount".equals(operation)) {
            if (!supplied.containsKey(AccountManager.KEY_BOOLEAN_RESULT)) throw new IllegalArgumentException("Authenticator boolean result is missing");
            boolean value = supplied.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
            if ("removeAccount".equals(operation) && value) value = repository.remove(owner, AccountWire.key(input));
            result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, value);
        } else if ("getAuthTokenLabel".equals(operation)) {
            result.putString(AccountManager.KEY_AUTH_TOKEN_LABEL, supplied.getString(AccountManager.KEY_AUTH_TOKEN_LABEL));
        } else if (!"editProperties".equals(operation)) {
            String name = supplied.getString(AccountManager.KEY_ACCOUNT_NAME), type = supplied.getString(AccountManager.KEY_ACCOUNT_TYPE);
            if (name == null || name.isEmpty() || !authenticator.accountType.equals(type)) throw new IllegalArgumentException("Authenticator result has the wrong account type");
            AccountKey returned = new AccountKey(name, type);
            if (input.containsKey("account") && !returned.equals(AccountWire.key(input))) throw new IllegalArgumentException("Authenticator returned a different account");
            repository.requireVisible(consumer, returned);
            result.putString(AccountManager.KEY_ACCOUNT_NAME, name); result.putString(AccountManager.KEY_ACCOUNT_TYPE, type);
            if ("getAuthToken".equals(operation)) {
                String token = supplied.getString(AccountManager.KEY_AUTHTOKEN);
                if (token == null || token.isEmpty()) throw new IllegalArgumentException("Authenticator token is missing");
                String tokenType = AccountWire.required(input, "tokenType");
                if (authenticator.customTokens) {
                    long expires = supplied.getLong("android.accounts.expiry", 0);
                    if (expires > System.currentTimeMillis()) repository.issuedToken(owner, consumer, returned, tokenType, token, expires);
                } else {
                    // Re-check grant and real consumer signer after an asynchronous request.
                    repository.grantedToken(consumer, returned, tokenType);
                    repository.setToken(owner, returned, tokenType, token);
                }
                result.putString(AccountManager.KEY_AUTHTOKEN, token);
            }
        }
        close(); AccountService.deliver(consumerResponse, result);
    }

    private void validateIntent(Intent intent) throws IOException {
        if (intent.getClass() != Intent.class) throw new SecurityException("Authenticator UI requires a plain Intent");
        ComponentName target = intent.getComponent();
        int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        if (target == null || !authenticator.packageName.equals(target.getPackageName())
                || intent.getSelector() != null || intent.getClipData() != null || (intent.getFlags() & grants) != 0
                || (intent.getPackage() != null && !authenticator.packageName.equals(intent.getPackage()))) {
            throw new SecurityException("Authenticator UI must target its own explicit virtual Activity");
        }
        PackageInfo info = packages.getPackageInfo(authenticator.packageName, PackageManager.GET_ACTIVITIES);
        if (info != null && info.applicationInfo.enabled && info.activities != null) {
            for (ActivityInfo activity : info.activities) if (activity.enabled && target.getClassName().equals(activity.name)) return;
        }
        throw new SecurityException("Authenticator Activity is not in the committed package");
    }

    private synchronized void reschedule(long duration) {
        if (closed) return;
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, Math.max(1, Math.min(duration, deadline - SystemClock.elapsedRealtime())));
    }

    private synchronized void fail(int code, String message) {
        if (closed) return;
        close();
        try { AccountService.error(consumerResponse, code, message); }
        catch (RemoteException ignored) { /* The requesting process is already gone. */ }
    }

    private synchronized void close() {
        close(false);
    }

    private synchronized void close(boolean retainReturnedIntent) {
        ui.close(retainReturnedIntent);
        if (closed) return;
        closed = true; handler.removeCallbacks(timeout);
        if (linked) { consumerResponse.asBinder().unlinkToDeath(consumerDeath, 0); linked = false; }
        if (binding != null) { AuthenticatorRouter.Binding current = binding; binding = null; closeBinding(current); }
        if (acquired) { acquired = false; LIMIT.release(); }
    }

    private static void closeBinding(AuthenticatorRouter.Binding value) {
        try { value.close(); }
        catch (RuntimeException failure) { Log.w("MyboxAccounts", "Authenticator unbind failed (" + failure.getClass().getSimpleName() + ")"); }
    }
}
