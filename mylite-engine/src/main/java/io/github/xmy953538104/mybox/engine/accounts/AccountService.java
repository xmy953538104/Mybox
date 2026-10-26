package io.github.xmy953538104.mybox.engine.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import io.github.xmy953538104.mybox.engine.CallerIdentity;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.packages.PackageStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Virtual user-0 account service. This class never obtains the system AccountManager. */
public final class AccountService {
    private final AccountDirectory directory;
    private final AccountRepository repository;
    private final PackageStore packages;
    private final CallerResolver callers;
    private final AuthenticatorRouter router;
    private final AccountNotifier notifier;
    private final AccountUiAuthorizer ui;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IVirtualAccountService.Stub binder = new IVirtualAccountService.Stub() {
        @Override public Bundle execute(String operation, Bundle request, IVirtualAccountResponse response) throws RemoteException {
            if (request == null || request.getInt("version") != AccountWire.VERSION) throw new IllegalArgumentException("Invalid account protocol");
            CallerIdentity caller = callers.resolve(request.getString("opPackage"));
            if (caller == null) throw new SecurityException("Account caller is not registered");
            AccountRepository.requireUser(caller.userId);
            AccountRepository.requireUser(request.getInt("userId", 0));
            try {
                if (!caller.certificateSha256.equals(directory.certificates(caller.packageName))) {
                    throw new SecurityException("Account caller installation changed");
                }
                return dispatch(operation, request, response, caller);
            } catch (IOException failure) {
                throw new IllegalStateException("Virtual account storage or metadata is unavailable", failure);
            }
        }
    };

    public AccountService(Context context, PackageStore packages, CallerResolver callers,
            AuthenticatorRouter router, AccountNotifier notifier, AuthenticatorTrust trust, AccountUiAuthorizer ui) throws IOException {
        this.packages = packages; this.callers = callers; this.router = router; this.notifier = notifier;
        this.ui = ui;
        directory = new AccountDirectory(context, packages, trust);
        repository = new AccountRepository(new EncryptedAccountStorage(context), directory, notifier);
    }

    public IBinder asBinder() { return binder; }

    private Bundle dispatch(String operation, Bundle input, IVirtualAccountResponse response, CallerIdentity caller)
            throws IOException, RemoteException {
        Bundle result = new Bundle();
        switch (operation) {
            case "describe": result.putInt("serverPid", android.os.Process.myPid()); result.putInt("serverUid", android.os.Process.myUid()); break;
            case "isAuthenticator":
                result.putBoolean("value", directory.ownsComponent(caller.packageName, AccountWire.required(input, "component"))); break;
            case "authorizeAuthenticatorPeer": {
                if (!directory.ownsAnyType(caller.packageName, caller.certificateSha256)) throw new SecurityException("Relay is not an authenticator");
                CallerIdentity peer = callers.resolvePeer(input.getInt("realUid", -1), input.getInt("realPid", -1));
                result.putBoolean("value", peer != null && peer.userId == caller.userId && peer.packageName.equals(caller.packageName)
                        && peer.certificateSha256.equals(caller.certificateSha256)); break;
            }
            case "getAuthenticatorTypes": result.putParcelableArray("value", directory.descriptions()); break;
            case "getAccountsByTypeForPackage":
                result.putParcelableArray("value", AccountWire.accounts(repository.accountsByTypeForPackage(
                        caller, input.getString("type"), AccountWire.required(input, "targetPackage")))); break;
            case "getAccounts": {
                String target = input.getString("targetPackage", caller.packageName);
                if (input.containsKey("targetUid") && input.getInt("targetUid") != caller.appId) {
                    throw new SecurityException("Account package UID does not match caller");
                }
                List<AccountKey> values = repository.accounts(caller, input.getString("type"));
                if (!caller.packageName.equals(target)) {
                    List<AccountKey> filtered = new ArrayList<>();
                    for (AccountKey key : values) {
                        repository.requireOwner(caller, key.type);
                        int visibility = repository.getVisibility(caller, key, target);
                        if (visibility == AccountRepository.VISIBLE || visibility == AccountRepository.USER_VISIBLE) filtered.add(key);
                    }
                    values = filtered;
                }
                result.putParcelableArray("value", AccountWire.accounts(values)); break;
            }
            case "getPassword": result.putString("value", repository.password(caller, AccountWire.key(input))); break;
            case "getUserData": result.putString("value", repository.userData(caller, AccountWire.key(input), AccountWire.required(input, "key"))); break;
            case "peekAuthToken": result.putString("value", repository.peekToken(caller, AccountWire.key(input), AccountWire.required(input, "tokenType"))); break;
            case "addAccountExplicitly": result.putBoolean("value", repository.add(caller, AccountWire.key(input), input.getString("password"),
                    AccountWire.strings(input.getBundle("extras")), AccountWire.visibility(input.getBundle("visibility")))); break;
            case "removeAccountExplicitly": result.putBoolean("value", repository.remove(caller, AccountWire.key(input))); break;
            case "setPassword": repository.setPassword(caller, AccountWire.key(input), input.getString("password")); break;
            case "setUserData": repository.setUserData(caller, AccountWire.key(input), AccountWire.required(input, "key"), input.getString("value")); break;
            case "setAuthToken": repository.setToken(caller, AccountWire.key(input), AccountWire.required(input, "tokenType"), input.getString("value")); break;
            case "invalidateAuthToken": repository.invalidateToken(caller, AccountWire.required(input, "type"), AccountWire.required(input, "token")); break;
            case "getPreviousName": result.putString("value", repository.previousName(caller, AccountWire.key(input))); break;
            case "accountAuthenticated": result.putBoolean("value", repository.authenticated(caller, AccountWire.key(input), System.currentTimeMillis())); break;
            case "setAccountVisibility": result.putBoolean("value", repository.setVisibility(caller, AccountWire.key(input),
                    AccountWire.required(input, "targetPackage"), input.getInt("visibility"))); break;
            case "getAccountVisibility": result.putInt("value", repository.getVisibility(caller, AccountWire.key(input), AccountWire.required(input, "targetPackage"))); break;
            case "getPackagesAndVisibilityForAccount": {
                Bundle values = new Bundle();
                for (Map.Entry<String, Integer> item : repository.visibilityMap(caller, AccountWire.key(input)).entrySet()) values.putInt(item.getKey(), item.getValue());
                result.putBundle("value", values); break;
            }
            case "getAccountsAndVisibilityForPackage": {
                List<AccountKey> values = repository.accounts(caller, input.getString("type"));
                String target = AccountWire.required(input, "targetPackage");
                int[] visibility = new int[values.size()];
                for (int i = 0; i < visibility.length; i++) visibility[i] = repository.getVisibility(caller, values.get(i), target);
                result.putParcelableArray("accounts", AccountWire.accounts(values)); result.putIntArray("visibility", visibility); break;
            }
            case "updateAppPermission": {
                AccountKey key = AccountWire.key(input);
                repository.requireOwner(caller, key.type);
                InstalledPackage target = null;
                for (InstalledPackage item : packages.list()) if (item.appId == input.getInt("targetUid", -1)) {
                    if (target != null) throw new SecurityException("Shared virtual UIDs are not supported");
                    target = item;
                }
                if (target == null) throw new SecurityException("Token consumer UID is not installed");
                repository.grantToken(caller, key, AccountWire.required(input, "tokenType"), target.packageName, input.getBoolean("value")); break;
            }
            case "registerAccountListener":
                if (!notifier.supportsListeners()) throw new UnsupportedOperationException("Virtual account notifications are not available");
                repository.registerListener(caller, input.getStringArray("types")); break;
            case "unregisterAccountListener": repository.unregisterListener(caller, input.getStringArray("types")); break;
            case "hasAccountAccess": {
                AccountKey key = AccountWire.key(input);
                int visibility = repository.getVisibility(caller, key, AccountWire.required(input, "targetPackage"));
                result.putBoolean("value", visibility == AccountRepository.VISIBLE || visibility == AccountRepository.USER_VISIBLE); break;
            }
            case "someUserHasAccount": result.putBoolean("value", repository.accounts(caller, AccountWire.key(input).type).contains(AccountWire.key(input))); break;
            case "renameAccount": {
                requireResponse(response);
                AccountKey key = repository.rename(caller, AccountWire.key(input), AccountWire.required(input, "newName"));
                Bundle value = new Bundle(); value.putString(AccountManager.KEY_ACCOUNT_NAME, key.name); value.putString(AccountManager.KEY_ACCOUNT_TYPE, key.type);
                deliver(response, value); break;
            }
            case "getAccountsByFeatures":
            case "getAccountByTypeAndFeatures":
                requireResponse(response); queryFeatures(operation, input, response, caller); break;
            case "getAuthToken":
            case "addAccount":
            case "confirmCredentials":
            case "updateCredentials":
            case "editProperties":
            case "getAuthTokenLabel":
            case "hasFeatures":
            case "removeAccount":
            case "isCredentialsUpdateSuggested":
                requireResponse(response); authenticate(operation, input, response, caller); break;
            default: throw new UnsupportedOperationException("Virtual account operation is not implemented: " + operation);
        }
        return result;
    }

    private void authenticate(String operation, Bundle input, IVirtualAccountResponse response, CallerIdentity caller) throws IOException, RemoteException {
        AccountKey key = input.containsKey("account") ? AccountWire.key(input) : null;
        String type = key == null ? AccountWire.required(input, "type") : key.type;
        AuthenticatorRegistration registration = key == null ? directory.authenticator(type) : repository.requireVisible(caller, key);
        if (registration == null) { error(response, AccountManager.ERROR_CODE_BAD_ARGUMENTS, "No trusted virtual authenticator is installed"); return; }
        if ("editProperties".equals(operation)) repository.requireOwner(caller, type);
        if ("getAuthToken".equals(operation)) {
            String tokenType = AccountWire.required(input, "tokenType");
            String cached = registration.customTokens ? repository.cachedIssuedToken(caller, key, tokenType, System.currentTimeMillis())
                    : repository.grantedToken(caller, key, tokenType);
            if (cached != null) {
                Bundle value = new Bundle(); value.putString(AccountManager.KEY_ACCOUNT_NAME, key.name);
                value.putString(AccountManager.KEY_ACCOUNT_TYPE, key.type); value.putString(AccountManager.KEY_AUTHTOKEN, cached);
                deliver(response, value); return;
            }
        }
        AccountUiAuthorizer.Scope uiScope = ui.begin(caller, registration, operation, input.getBoolean("expectActivity"));
        new AuthSession(operation, input, response, caller, registration, repository, callers, router, packages, handler, uiScope).start();
    }

    private void queryFeatures(String operation, Bundle input, IVirtualAccountResponse response, CallerIdentity caller) throws IOException, RemoteException {
        List<AccountKey> candidates = repository.accounts(caller, input.getString("type"));
        String[] features = input.getStringArray("features");
        if (features == null || features.length == 0) { deliverFeatureResult(operation, candidates, response); return; }
        class Query {
            final List<AccountKey> accepted = new ArrayList<>(); int index;
            void next() throws IOException, RemoteException {
                if (!response.asBinder().isBinderAlive()) return;
                if (index == candidates.size()) { deliverFeatureResult(operation, accepted, response); return; }
                AccountKey current = candidates.get(index++);
                Bundle request = new Bundle(input); request.putParcelable("account", new Account(current.name, current.type));
                authenticate("hasFeatures", request, new IVirtualAccountResponse.Stub() {
                    @Override public void onResult(Bundle result) throws RemoteException {
                        if (result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT)) accepted.add(current);
                        try { next(); } catch (IOException failure) { error(response, AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Feature query became unavailable"); }
                    }
                    @Override public void onError(int code, String message) throws RemoteException { error(response, code, "Authenticator feature query failed"); }
                }, caller);
            }
        }
        new Query().next();
    }

    private static void deliverFeatureResult(String operation, List<AccountKey> values, IVirtualAccountResponse response) throws RemoteException {
        Bundle result = new Bundle();
        if ("getAccountByTypeAndFeatures".equals(operation)) {
            if (values.size() > 1) { error(response, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, "Account selection UI is required"); return; }
            if (values.size() == 1) {
                result.putString(AccountManager.KEY_ACCOUNT_NAME, values.get(0).name); result.putString(AccountManager.KEY_ACCOUNT_TYPE, values.get(0).type);
            }
        } else result.putParcelableArray(AccountManager.KEY_ACCOUNTS, AccountWire.accounts(values));
        deliver(response, result);
    }

    private static void requireResponse(IVirtualAccountResponse response) { if (response == null) throw new IllegalArgumentException("Account response is required"); }
    static void deliver(IVirtualAccountResponse response, Bundle value) throws RemoteException {
        long identity = Binder.clearCallingIdentity();
        try { response.onResult(value); } finally { Binder.restoreCallingIdentity(identity); }
    }
    static void error(IVirtualAccountResponse response, int code, String message) throws RemoteException {
        long identity = Binder.clearCallingIdentity();
        try { response.onError(code, message); } finally { Binder.restoreCallingIdentity(identity); }
    }
}
