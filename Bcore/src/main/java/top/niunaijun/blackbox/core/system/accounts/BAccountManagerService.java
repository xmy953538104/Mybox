package top.niunaijun.blackbox.core.system.accounts;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.accounts.IAccountAuthenticator;
import android.accounts.IAccountAuthenticatorResponse;
import android.accounts.IAccountManagerResponse;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.core.util.AtomicFile;
import androidx.core.util.Preconditions;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import top.niunaijun.blackbox.utils.SynchronousBinder;

import black.com.android.internal.BRRstyleable;
import black.com.android.internal.RstyleableStatic;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.am.BActivityManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.core.system.pm.MicroGSignaturePolicy;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.utils.ArrayUtils;
import top.niunaijun.blackbox.utils.CloseUtils;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.AccountManagerCompat;

/**
 * Created by BlackBox on 2022/3/3.
 */
@SuppressLint("InlinedApi")
public class BAccountManagerService extends IBAccountManagerService.Stub implements ISystemService {
    private static final String TAG = "AccountManagerService";

    private static BAccountManagerService sService = new BAccountManagerService();

    private static final Account[] EMPTY_ACCOUNT_ARRAY = new Account[]{};

    // Messages that can be sent on mHandler
    private static final int MESSAGE_TIMED_OUT = 3;
    private static final int MESSAGE_COPY_SHARED_ACCOUNT = 4;

    private final BPackageManagerService mPms;
    private final Map<Integer, BUserAccounts> mUserAccountsMap = new HashMap<>();
    private final Map<Integer, Map<String, Map<String, Integer>>> mAccountListeners = new HashMap<>();

    private final LinkedHashMap<String, Session> mSessions = new LinkedHashMap<String, Session>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Context mContext;
    private final ExecutorService mAuthenticatorCalls = Executors.newFixedThreadPool(2);

    public static BAccountManagerService get() {
        return sService;
    }

    public BAccountManagerService() {
        mContext = BlackBoxCore.getContext();
        mPms = BPackageManagerService.get();
    }

    @Override
    public void systemReady() {
        loadAccounts();
    }

    private void loadAccounts() {
        Parcel parcel = Parcel.obtain();
        InputStream is = null;
        try {
            File userInfoConf = BEnvironment.getAccountsConf();
            if (!userInfoConf.exists()) {
                return;
            }
            is = new FileInputStream(BEnvironment.getAccountsConf());
            byte[] bytes = FileUtils.toByteArray(is);
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);

            HashMap<Integer, BUserAccounts> accountsMap = parcel.readHashMap(BUserAccounts.class.getClassLoader());
            if (accountsMap == null)
                return;
            synchronized (mUserAccountsMap) {
                mUserAccountsMap.clear();
                for (Integer key : accountsMap.keySet()) {
                    BUserAccounts accounts = accountsMap.get(key);
                    accounts.userId = key;
                    mUserAccountsMap.put(key, accounts);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            parcel.recycle();
            CloseUtils.close(is);
        }
    }

    void saveAllAccounts() {
        synchronized (mUserAccountsMap) {
            Parcel parcel = Parcel.obtain();
            AtomicFile atomicFile = new AtomicFile(BEnvironment.getAccountsConf());
            FileOutputStream fileOutputStream = null;
            try {
                parcel.writeMap(mUserAccountsMap);
                try {
                    fileOutputStream = atomicFile.startWrite();
                    FileUtils.writeParcelToOutput(parcel, fileOutputStream);
                    atomicFile.finishWrite(fileOutputStream);
                } catch (IOException e) {
                    e.printStackTrace();
                    atomicFile.failWrite(fileOutputStream);
                } finally {
                    CloseUtils.close(fileOutputStream);
                }
            } finally {
                parcel.recycle();
            }
        }
    }

    @Override
    public String getPassword(Account account, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        if (account == null) throw new IllegalArgumentException("account is null");
        BUserAccounts accounts = getUserAccounts(userId);
        return readPasswordInternal(accounts, account);
    }

    @Override
    public String getUserData(Account account, String key, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        return readUserDataInternal(accounts, account, key);
    }

    @Override
    public AuthenticatorDescription[] getAuthenticatorTypes(int userId) throws RemoteException {
        requireCaller(userId);
        List<AuthenticatorDescription> descriptions = new ArrayList<>();
        for (AuthenticatorInfo info : getAuthenticators(userId).values()) descriptions.add(info.desc);
        return descriptions.toArray(new AuthenticatorDescription[0]);
    }

    @Override
    public Account[] getAccountsForPackage(String packageName, int uid, int userId) throws RemoteException {
        ProcessRecord caller = requireCaller(userId);
        return getAccountsFromCache(getUserAccounts(userId), null, caller.getPackageName(), false);
    }

    @Override
    public Account[] getAccountsByTypeForPackage(String type, String packageName, int userId) throws RemoteException {
        ProcessRecord caller = requireCaller(userId);
        return getAccountsFromCache(getUserAccounts(userId), type, caller.getPackageName(), false);
    }

    @Override
    public Account[] getAccountsAsUser(String accountType, int userId) throws RemoteException {
        ProcessRecord caller = requireCaller(userId);
        return getAccountsFromCache(getUserAccounts(userId), accountType, caller.getPackageName(), false);
    }

    @Override
    public void getAccountByTypeAndFeatures(IAccountManagerResponse response, String accountType, String[] features, int userId) throws RemoteException {
        requireCaller(userId);
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");

        String opPackageName = getCallingPackageName();

        BUserAccounts userAccounts = getUserAccounts(userId);
        if (ArrayUtils.isEmpty(features)) {
            Account[] accountsWithManagedNotVisible = getAccountsFromCache(
                    userAccounts, accountType, opPackageName,
                    true /* include managed not visible */);
            handleGetAccountsResult(
                    response, accountsWithManagedNotVisible, opPackageName, userId);
            return;
        }

        IAccountManagerResponse retrieveAccountsResponse =
                new IAccountManagerResponse.Stub() {
                    @Override
                    public void onResult(Bundle value) throws RemoteException {
                        Parcelable[] parcelables = value.getParcelableArray(
                                AccountManager.KEY_ACCOUNTS);
                        Account[] accounts = new Account[parcelables.length];
                        for (int i = 0; i < parcelables.length; i++) {
                            accounts[i] = (Account) parcelables[i];
                        }
                        handleGetAccountsResult(
                                response, accounts, opPackageName, userId);
                    }

                    @Override
                    public void onError(int errorCode, String errorMessage)
                            throws RemoteException {
                        // Will not be called in this case.
                    }
                };
        new GetAccountsByTypeAndFeatureSession(
                userAccounts,
                retrieveAccountsResponse,
                accountType,
                features,
                userId,
                opPackageName,
                true /* include managed not visible */).bind();
    }

    @Override
    public void getAccountsByFeatures(IAccountManagerResponse response, String type, String[] features, int userId) throws RemoteException {
        requireCaller(userId);
        if (response == null) throw new IllegalArgumentException("response is null");
        if (type == null) throw new IllegalArgumentException("accountType is null");

        String opPackageName = getCallingPackageName();
        // check visibleAccountTypes
        BUserAccounts userAccounts = getUserAccounts(userId);
        if (features == null || features.length == 0) {
            Account[] accounts = getAccountsFromCache(userAccounts, type,
                    opPackageName, false);
            Bundle result = new Bundle();
            result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts);
            onResult(response, result);
            return;
        }
        new GetAccountsByTypeAndFeatureSession(
                userAccounts,
                response,
                type,
                features,
                userId,
                opPackageName,
                false /* include managed not visible */).bind();
    }

    @Override
    public boolean addAccountExplicitly(Account account, String password, Bundle extras, int userId) throws RemoteException {
        return addAccountExplicitlyWithVisibility(account, password, extras, null, userId);
    }

    @Override
    public void removeAccountAsUser(IAccountManagerResponse response, Account account, boolean expectActivityLaunch, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        Preconditions.checkArgument(account != null, "account cannot be null");
        Preconditions.checkArgument(response != null, "response cannot be null");
        // Only allow the system process to modify accounts of other users
        /*
         * Only the system, authenticator or profile owner should be allowed to remove accounts for
         * that authenticator.  This will let users remove accounts (via Settings in the system) but
         * not arbitrary applications (like competing authenticators).
         */
        BUserAccounts accounts = getUserAccounts(userId);
        new RemoveAccountSession(accounts, response, account, expectActivityLaunch).bind();
    }

    @Override
    public boolean removeAccountExplicitly(Account account, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        final int callingUid = Binder.getCallingUid();
        if (account == null) {
            /*
             * Null accounts should result in returning false, as per
             * AccountManage.addAccountExplicitly(...) java doc.
             */
            Log.e(TAG, "account is null");
            return false;
        }
        BUserAccounts accounts = getUserAccounts(userId);
        return removeAccountInternal(accounts, account);
    }

    @Override
    public void copyAccountToUser(IAccountManagerResponse response, Account account, int userFrom, int userTo) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userFrom);
        requireCaller(userTo);
        final BUserAccounts fromAccounts = getUserAccounts(userFrom);
        final BUserAccounts toAccounts = getUserAccounts(userTo);
        if (fromAccounts == null || toAccounts == null) {
            if (response != null) {
                Bundle result = new Bundle();
                result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
                try {
                    response.onResult(result);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to report error back to the client." + e);
                }
            }
            return;
        }

        new Session(fromAccounts, response, account.type, false,
                false /* stripAuthTokenFromResult */, account.name,
                false /* authDetailsRequired */) {

            @Override
            public void run() throws RemoteException {
                mAuthenticator.getAccountCredentialsForCloning(this, account);
            }

            @Override
            public void onResult(Bundle result) {
                checkResultCaller();
                if (result != null
                        && result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) {
                    // Create a Session for the target user and pass in the bundle
                    completeCloningAccount(response, result, account, toAccounts, userFrom);
                } else {
                    super.onResult(result);
                }
            }
        }.bind();
    }

    @Override
    public void invalidateAuthToken(String accountType, String authToken, int userId) throws RemoteException {
        requireCaller(userId);
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) {
            boolean changed = false;
            for (BAccount account : accounts.accounts) {
                if (account.account.type.equals(accountType)) {
                    account.authTokens.values().remove(authToken);
                    changed = true;
                }
            }
            if (changed) {
                saveAllAccounts();
            }
        }

    }

    @Override
    public String peekAuthToken(Account account, String authTokenType, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(authTokenType, "authTokenType cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        if (accounts == null)
            return null;
        synchronized (accounts.lock) {
            return accounts.getAuthToken(account).get(authTokenType);
        }
    }

    @Override
    public void setAuthToken(Account account, String authTokenType, String authToken, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(authTokenType, "authTokenType cannot be null");

        BUserAccounts accounts = getUserAccounts(userId);
        if (accounts == null)
            return;
        synchronized (accounts.lock) {
            accounts.getAuthToken(account).put(authTokenType, authToken);
            saveAllAccounts();
        }
    }

    @Override
    public void setPassword(Account account, String password, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        Objects.requireNonNull(account, "account cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        if (accounts == null)
            return;
        synchronized (accounts.lock) {
            BAccount bAccount = accounts.getAccount(account);
            if (bAccount == null) return;
            bAccount.password = password;
            bAccount.authTokens.clear();
            saveAllAccounts();
        }
        notifyAccountPackages(visibleAccountListeners(account, accounts), userId);
    }

    @Override
    public void clearPassword(Account account, int userId) throws RemoteException {
        setPassword(account, null, userId);
    }

    @Override
    public void setUserData(Account account, String key, String value, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        if (key == null) throw new IllegalArgumentException("key is null");
        if (account == null) throw new IllegalArgumentException("account is null");

        BUserAccounts accounts = getUserAccounts(userId);
        if (accounts == null)
            return;
        synchronized (accounts.lock) {
            accounts.getAccountUserData(account).put(key, value);
            saveAllAccounts();
        }
    }

    @Override
    public void updateAppPermission(Account account, String authTokenType, int uid, boolean value) throws RemoteException {
        throw new UnsupportedOperationException("Account grants are managed by the authenticator");
    }

    @Override
    public void getAuthToken(IAccountManagerResponse response, Account account, String authTokenType, boolean notifyOnAuthFailure, boolean expectActivityLaunch, Bundle loginOptions, int userId) throws RemoteException {
        final ProcessRecord caller = requireCaller(userId);
        Preconditions.checkArgument(response != null, "response cannot be null");
        try {
            if (account == null) {
                Slog.w(TAG, "getAuthToken called with null account");
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "account is null");
                return;
            }
            if (authTokenType == null) {
                Slog.w(TAG, "getAuthToken called with null authTokenType");
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "authTokenType is null");
                return;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to report error back to the client." + e);
            return;
        }
        final BUserAccounts accounts = getUserAccounts(userId);
        AuthenticatorInfo authenticatorInfo = getAuthenticatorInfo(account.type, userId);

        final boolean customTokens =
                authenticatorInfo != null && authenticatorInfo.desc.customTokens;

        final String callerPkg = caller.getPackageName();
        final Bundle options = verifiedOptions(loginOptions, caller);
        if (notifyOnAuthFailure) options.putBoolean(AccountManagerCompat.KEY_NOTIFY_ON_FAILURE, true);

        new Session(
                accounts,
                response,
                account.type,
                expectActivityLaunch,
                false /* stripAuthTokenFromResult */,
                account.name,
                false /* authDetailsRequired */) {

            @Override
            public void run() throws RemoteException {
                // If the caller doesn't have permission then create and return the
                // "grant permission" intent instead of the "getAuthToken" intent.
                mAuthenticator.getAuthToken(this, account, authTokenType, options);
            }

            @Override
            public void onResult(Bundle result) {
                checkResultCaller();
                if (result != null) {
                    String authToken = result.getString(AccountManager.KEY_AUTHTOKEN);
                    if (authToken != null) {
                        String name = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                        String type = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                        if (TextUtils.isEmpty(type) || TextUtils.isEmpty(name)) {
                            onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                    "the type and name should not be empty");
                            return;
                        }
                        Account resultAccount = new Account(name, type);
                        if (!account.equals(resultAccount)) {
                            onError(AccountManager.ERROR_CODE_INVALID_RESPONSE, "Unexpected account identity");
                            return;
                        }
                        if (setAccountVisibility(resultAccount, callerPkg, AccountManager.VISIBILITY_VISIBLE, mAccounts)) {
                            saveAllAccounts();
                        }
                        if (!customTokens) {
                            saveAuthTokenToDatabase(
                                    mAccounts,
                                    resultAccount,
                                    authTokenType,
                                    authToken);
                        }

                    }

                    Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
                    if (intent != null && notifyOnAuthFailure && !customTokens) {
//                            doNotification(
//                                    mAccounts,
//                                    account,
//                                    result.getString(AccountManager.KEY_AUTH_FAILED_MESSAGE),
//                                    intent, "android", accounts.userId);
                    }
                }
                super.onResult(result);
            }
        }.bind();
    }

    @Override
    public void addAccount(IAccountManagerResponse response, String accountType, String authTokenType, String[] requiredFeatures, boolean expectActivityLaunch, Bundle optionsIn, int userId) throws RemoteException {
        final ProcessRecord caller = requireCaller(userId);
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");

        final Bundle options = verifiedOptions(optionsIn, caller);

        BUserAccounts accounts = getUserAccounts(userId);
        new Session(accounts, response, accountType, expectActivityLaunch,
                true /* stripAuthTokenFromResult */, null /* accountName */,
                false /* authDetailsRequired */, true /* updateLastAuthenticationTime */) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.addAccount(this, mAccountType, authTokenType, requiredFeatures, options);
            }

            @Override public void onResult(Bundle result) {
                checkResultCaller();
                if (result != null && result.containsKey(AccountManager.KEY_ACCOUNT_NAME)
                        && result.containsKey(AccountManager.KEY_ACCOUNT_TYPE)) {
                    String type = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                    String name = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                    if (!accountType.equals(type) || TextUtils.isEmpty(name)) {
                        onError(AccountManager.ERROR_CODE_INVALID_RESPONSE, "Unexpected account identity");
                        return;
                    }
                    if (setAccountVisibility(new Account(name, type), caller.getPackageName(),
                            AccountManager.VISIBILITY_VISIBLE, mAccounts)) saveAllAccounts();
                }
                super.onResult(result);
            }
        }.bind();
    }

    @Override
    public void addAccountAsUser(IAccountManagerResponse response, String accountType, String authTokenType,
            String[] requiredFeatures, boolean expectActivityLaunch, Bundle options, int userId) throws RemoteException {
        requireCaller(userId);
        addAccount(response, accountType, authTokenType, requiredFeatures, expectActivityLaunch, options, userId);
    }

    @Override
    public void updateCredentials(IAccountManagerResponse response, Account account, String authTokenType, boolean expectActivityLaunch, Bundle loginOptions, int userId) throws RemoteException {
        requireCaller(userId);
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        final Bundle options = verifiedOptions(loginOptions, requireCaller(userId));
        BUserAccounts accounts = getUserAccounts(userId);
        new Session(accounts, response, account.type, expectActivityLaunch,
                true /* stripAuthTokenFromResult */, account.name,
                false /* authDetailsRequired */, true /* updateLastCredentialTime */) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.updateCredentials(this, account, authTokenType, options);
            }
        }.bind();
    }

    @Override
    public void editProperties(IAccountManagerResponse response, String accountType, boolean expectActivityLaunch, int userId) throws RemoteException {
        requireAuthenticator(accountType, userId);
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");

        BUserAccounts accounts = getUserAccounts(userId);
        new Session(accounts, response, accountType, expectActivityLaunch,
                true /* stripAuthTokenFromResult */, null /* accountName */,
                false /* authDetailsRequired */) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.editProperties(this, mAccountType);
            }
        }.bind();
    }

    @Override
    public void confirmCredentialsAsUser(IAccountManagerResponse response, Account account, Bundle options,
            boolean expectActivityLaunch, int userId) throws RemoteException {
        Objects.requireNonNull(response, "response cannot be null");
        Objects.requireNonNull(account, "account cannot be null");
        final Bundle verified = verifiedOptions(options, requireCaller(userId));
        new Session(getUserAccounts(userId), response, account.type, expectActivityLaunch,
                true, account.name, true, true) {
            @Override public void run() throws RemoteException {
                mAuthenticator.confirmCredentials(this, account, verified);
            }
        }.bind();
    }

    @Override
    public boolean accountAuthenticated(Account account, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        Objects.requireNonNull(account, "account cannot be null");
        BUserAccounts userAccounts = getUserAccounts(userId);
        if (userAccounts == null)
            return false;
        return updateLastAuthenticatedTime(userAccounts, account);
    }

    @Override
    public void getAuthTokenLabel(IAccountManagerResponse response, String accountType, String authTokenType, int userId) throws RemoteException {
        requireAuthenticator(accountType, userId);
        Preconditions.checkArgument(accountType != null, "accountType cannot be null");
        Preconditions.checkArgument(authTokenType != null, "authTokenType cannot be null");
//        if (UserHandle.getAppId(callingUid) != Process.SYSTEM_UID) {
//            throw new SecurityException("can only call from system");
//        }
        BUserAccounts accounts = getUserAccounts(userId);
        new Session(accounts, response, accountType, false /* expectActivityLaunch */,
                false /* stripAuthTokenFromResult */,  null /* accountName */,
                false /* authDetailsRequired */) {

            @Override
            public void run() throws RemoteException {
                mAuthenticator.getAuthTokenLabel(this, authTokenType);
            }

            @Override
            public void onResult(Bundle result) {
                checkResultCaller();
                if (result != null) {
                    String label = result.getString(AccountManager.KEY_AUTH_TOKEN_LABEL);
                    Bundle bundle = new Bundle();
                    bundle.putString(AccountManager.KEY_AUTH_TOKEN_LABEL, label);
                    super.onResult(bundle);
                } else {
                    super.onResult(result);
                }
            }
        }.bind();
    }

    @Override
    public Map getPackagesAndVisibilityForAccount(Account account, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) { return new HashMap<>(accounts.getVisibility(account)); }
    }



    protected void saveAuthTokenToDatabase(BUserAccounts accounts, Account account, String
                                           authTokenType, String authToken) {
        if (accounts == null)
            return;
        synchronized (accounts.lock) {
            accounts.getAuthToken(account).put(authTokenType, authToken);
            saveAllAccounts();
        }
    }



    protected String readAuthTokenInternal(BUserAccounts accounts, Account account,
                                           String authTokenType) {
        if (accounts == null)
            return null;
        // If not cached yet - do slow path and sync with db if necessary
        synchronized (accounts.lock) {
            Map<String, String> authToken = accounts.getAuthToken(account);
            return authToken.get(authTokenType);
        }
    }

    private void completeCloningAccount(IAccountManagerResponse response,
                                        final Bundle accountCredentials, final Account account, final BUserAccounts targetUser,
                                        final int parentUserId){
        new Session(targetUser, response, account.type, false,
                false /* stripAuthTokenFromResult */, account.name,
                false /* authDetailsRequired */) {

            @Override
            public void run() throws RemoteException {
                // Confirm that the owner's account still exists before this step.
                for (Account acc : getAccounts(parentUserId, mContext.getPackageName())) {
                    if (acc.equals(account)) {
                        mAuthenticator.addAccountFromCredentials(
                                this, account, accountCredentials);
                        break;
                    }
                }
            }

            @Override
            public void onResult(Bundle result) {
                checkResultCaller();
                // TODO: Anything to do if if succedded?
                // TODO: If it failed: Show error notification? Should we remove the shadow
                // account to avoid retries?
                // TODO: what we do with the visibility?

                super.onResult(result);
            }

            @Override
            public void onError(int errorCode, String errorMessage) {
                super.onError(errorCode,  errorMessage);
                // TODO: Show error notification to user
                // TODO: Should we remove the shadow account so that it doesn't keep trying?
            }

        }.bind();

    }

    public Account[] getAccounts(int userId, String opPackageName) {
        BUserAccounts userAccounts = getUserAccounts(userId);
        return userAccounts.toAccounts();
    }

    @Override
    public boolean addAccountExplicitlyWithVisibility(Account account, String password,
                                                      Bundle extras, Map packageToVisibility, int userId) {
        requireAuthenticator(account == null ? null : account.type, userId);
        /*
         * Child users are not allowed to add accounts. Only the accounts that are shared by the
         * parent profile can be added to child profile.
         *
         * TODO: Only allow accounts that were shared to be added by a limited user.
         */
        // fails if the account already exists
        BUserAccounts accounts = getUserAccounts(userId);
        return addAccountInternal(accounts, account, password, extras,
                (Map<String, Integer>) packageToVisibility);
    }

    @Override
    public boolean setAccountVisibility(Account account, String packageName, int newVisibility, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        if (newVisibility < AccountManager.VISIBILITY_UNDEFINED || newVisibility > AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE) {
            throw new IllegalArgumentException("Invalid account visibility");
        }
        BUserAccounts userAccounts = getUserAccounts(userId);
        if (userAccounts == null)
            return false;
        boolean changed = setAccountVisibility(account, packageName, newVisibility, userAccounts);
        if (changed) saveAllAccounts();
        return changed;
    }

    @Override
    public int getAccountVisibility(Account account, String packageName, int userId) throws RemoteException {
        requireAuthenticator(account == null ? null : account.type, userId);
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        if (AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE.equals(packageName)) {
            int visibility = getAccountVisibilityFromCache(account, packageName, accounts);
            if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
                return visibility;
            } else {
                return AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;
            }
        }
        if (AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE.equals(packageName)) {
            int visibility = getAccountVisibilityFromCache(account, packageName, accounts);
            if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
                return visibility;
            } else {
                return AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE;
            }
        }
        return resolveAccountVisibility(account, packageName, accounts);
    }

    @Override
    public Map getAccountsAndVisibilityForPackage(String packageName, String accountType, int userId) throws RemoteException {
        requireCaller(userId);
        String callerPackage = getCallingPackageName();
        Map<Account, Integer> visible = new HashMap<>();
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) {
            for (BAccount account : accounts.accounts) {
                if ((accountType == null || accountType.equals(account.account.type))
                        && isAuthenticatorPackage(callerPackage, account.account.type, userId)) {
                    Integer visibility = account.visibility.get(packageName);
                    if (visibility != null) visible.put(account.account, visibility);
                }
            }
        }
        return visible;
    }

    @Override
    public void registerAccountListener(String[] accountTypes, String opPackageName, int userId) throws RemoteException {
        ProcessRecord caller = requireCaller(userId);
        AccountAccessPolicy.enforceClaimedPackage(caller.getPackageName(), opPackageName);
        updateAccountListener(accountTypes, caller.getPackageName(), userId, true);
    }

    @Override
    public void unregisterAccountListener(String[] accountTypes, String opPackageName, int userId) throws RemoteException {
        ProcessRecord caller = requireCaller(userId);
        AccountAccessPolicy.enforceClaimedPackage(caller.getPackageName(), opPackageName);
        updateAccountListener(accountTypes, caller.getPackageName(), userId, false);
    }

    void updateAccountListener(String[] accountTypes, String packageName, int userId, boolean register) {
        String[] types = accountTypes == null ? new String[]{null} : accountTypes;
        synchronized (mAccountListeners) {
            Map<String, Map<String, Integer>> byType = mAccountListeners.get(userId);
            if (byType == null) {
                if (!register && types.length != 0) throw new IllegalArgumentException("Account listener was not registered");
                byType = new HashMap<>();
                mAccountListeners.put(userId, byType);
            }
            for (String type : types) {
                Map<String, Integer> packages = byType.get(type);
                if (packages == null) {
                    if (!register) throw new IllegalArgumentException("Account listener was not registered");
                    packages = new HashMap<>();
                    byType.put(type, packages);
                }
                Integer count = packages.get(packageName);
                if (register) packages.put(packageName, count == null ? 1 : count + 1);
                else if (count == null) throw new IllegalArgumentException("Account listener was not registered");
                else if (count == 1) packages.remove(packageName);
                else packages.put(packageName, count - 1);
                if (packages.isEmpty()) byType.remove(type);
            }
            if (byType.isEmpty()) mAccountListeners.remove(userId);
        }
    }

    private Set<String> visibleAccountListeners(Account account, BUserAccounts accounts) {
        Set<String> candidates = new HashSet<>();
        synchronized (mAccountListeners) {
            Map<String, Map<String, Integer>> byType = mAccountListeners.get(accounts.userId);
            if (byType != null) {
                Map<String, Integer> allTypes = byType.get(null);
                Map<String, Integer> thisType = byType.get(account.type);
                if (allTypes != null) candidates.addAll(allTypes.keySet());
                if (thisType != null) candidates.addAll(thisType.keySet());
            }
        }
        synchronized (accounts.lock) {
            candidates.removeIf(packageName -> {
                int visibility = resolveAccountVisibility(account, packageName, accounts);
                return visibility != AccountManager.VISIBILITY_VISIBLE
                        && visibility != AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;
            });
        }
        return candidates;
    }

    private void notifyAccountPackages(Set<String> packages, int userId) {
        for (String packageName : packages) notifyAccountPackage(packageName, userId);
    }

    private void notifyAccountPackage(String packageName, int userId) {
        // The event carries no account identity; each recipient re-queries its filtered view.
        Intent intent = new Intent(Build.VERSION.SDK_INT >= 26
                ? "android.accounts.action.VISIBLE_ACCOUNTS_CHANGED" : AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION)
                .setPackage(packageName).addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        sendAccountBroadcast(intent, userId);
    }

    void sendAccountBroadcast(Intent intent, int userId) {
        BActivityManagerService.get().sendPackageBroadcast(intent, userId);
    }

    boolean addAccountInternal(BUserAccounts accounts, Account account, String password,
                                       Bundle extras, Map<String, Integer> packageToVisibility) {
        if (accounts == null) {
            accounts = new BUserAccounts();
        }
        synchronized (accounts.lock) {
            BAccount bAccount = accounts.getAccount(account);
            if (bAccount != null) {
                Slog.d(TAG, "Account already exists");
                return false;
            }
            bAccount = accounts.addAccount(account);
            bAccount.password = password;
            if (extras != null) {
                for (String key : extras.keySet()) {
                    final String value = extras.getString(key);
                    bAccount.insertExtra(key, value);
                }
            }

            if (packageToVisibility != null) {
                for (Map.Entry<String, Integer> entry : packageToVisibility.entrySet()) {
                    bAccount.visibility.put(entry.getKey(), entry.getValue());
                }
            }
        }
        saveAllAccounts();
        notifyAccountPackages(visibleAccountListeners(account, accounts), accounts.userId);
        return true;
    }

    boolean setAccountVisibility(Account account, String packageName, int newVisibility, BUserAccounts accounts) {
        Set<String> changed;
        synchronized (accounts.lock) {
            BAccount bAccount = accounts.getAccount(account);
            if (bAccount == null)
                return false;
            Set<String> before = visibleAccountListeners(account, accounts);
            bAccount.visibility.put(packageName, newVisibility);
            Set<String> after = visibleAccountListeners(account, accounts);
            changed = new HashSet<>(before);
            changed.addAll(after);
            before.retainAll(after);
            changed.removeAll(before);
        }
        notifyAccountPackages(changed, accounts.userId);
        return true;
    }

    protected Account[] getAccountsFromCache(BUserAccounts userAccounts, String accountType,
            String callingPackage, boolean includeManagedNotVisible) {
        Account[] accounts;
        synchronized (userAccounts.lock) {
            accounts = accountType == null ? userAccounts.toAccounts() : userAccounts.getAccountsByType(accountType);
        }
        return filterAccounts(userAccounts, accounts, callingPackage, includeManagedNotVisible);
    }

    @NonNull
    private Account[] filterAccounts(BUserAccounts accounts, Account[] unfiltered, String callingPackage, boolean includeManagedNotVisible) {
        Map<Account, Integer> firstPass = new LinkedHashMap<>();
        for (Account account : unfiltered) {
            int visibility = resolveAccountVisibility(account, callingPackage, accounts);
            if ((visibility == AccountManager.VISIBILITY_VISIBLE
                    || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE)
                    || (includeManagedNotVisible
                    && (visibility
                    == AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE))) {
                firstPass.put(account, visibility);
            }
        }
//        Map<Account, Integer> secondPass =
//                filterSharedAccounts(accounts, firstPass, callingUid, callingPackage);
//
//        Account[] filtered = new Account[secondPass.size()];
//        filtered = secondPass.keySet().toArray(filtered);
        return firstPass.keySet().toArray(new Account[]{});
    }

    /**
     * Method which handles default values for Account visibility.
     *
     * @param account     The account to check visibility.
     * @param packageName Package name to check visibility
     * @param accounts    UserAccount that currently hosts the account and application
     * @return Visibility value, the method never returns AccountManager.VISIBILITY_UNDEFINED
     */
    private Integer resolveAccountVisibility(Account account, @NonNull String packageName,
                                             BUserAccounts accounts) {
        if (accounts == null) {
            return AccountManager.VISIBILITY_NOT_VISIBLE;
        }
        BAccount bAccount = accounts.getAccount(account);
        if (bAccount == null) {
            return AccountManager.VISIBILITY_NOT_VISIBLE;
        }

        if (isAuthenticatorPackage(packageName, account.type, accounts.userId)) {
            return AccountManager.VISIBILITY_VISIBLE;
        }
        // Return stored value if it was set.
        int visibility = getAccountVisibilityFromCache(account, packageName, accounts);
        if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
            return visibility;
        }
        return AccountManager.VISIBILITY_NOT_VISIBLE;
    }

    /**
     * Method returns visibility for given account and package name.
     *
     * @param account     The account to check visibility.
     * @param packageName Package name to check visibility.
     * @param accounts    UserAccount that currently hosts the account and application
     * @return Visibility value, AccountManager.VISIBILITY_UNDEFINED if no value was stored.
     */
    private int getAccountVisibilityFromCache(Account account, String packageName,
                                              BUserAccounts accounts) {
        synchronized (accounts.lock) {
            Map<String, Integer> accountVisibility =
                    getPackagesAndVisibilityForAccountLocked(account, accounts);
            Integer visibility = accountVisibility.get(packageName);
            return visibility != null ? visibility : AccountManager.VISIBILITY_UNDEFINED;
        }
    }

    private @NonNull
    Map<String, Integer> getPackagesAndVisibilityForAccountLocked(Account account,
                                                                  BUserAccounts accounts) {
        return accounts.getVisibility(account);
    }

    private void handleGetAccountsResult(
            IAccountManagerResponse response,
            Account[] accounts,
            String callingPackage,
            int userId) {

        if (needToStartChooseAccountActivity(accounts, callingPackage, userId)) {
            try {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "Account selection requires an implemented consent activity");
            } catch (RemoteException failure) {
                Slog.w(TAG, "Account selection response could not be delivered", failure);
            }
            return;
        }
        if (accounts.length == 1) {
            Bundle bundle = new Bundle();
            bundle.putString(AccountManager.KEY_ACCOUNT_NAME, accounts[0].name);
            bundle.putString(AccountManager.KEY_ACCOUNT_TYPE, accounts[0].type);
            onResult(response, bundle);
            return;
        }
        // No qualified account exists, return an empty Bundle.
        onResult(response, new Bundle());
    }

    private boolean needToStartChooseAccountActivity(Account[] accounts, String callingPackage, int userId) {
        if (accounts.length < 1) return false;
        if (accounts.length > 1) return true;
        Account account = accounts[0];
        BUserAccounts userAccounts = getUserAccounts(userId);
        int visibility = resolveAccountVisibility(account, callingPackage, userAccounts);
        if (visibility == AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE) return true;
        return false;
    }

    private String readUserDataInternal(BUserAccounts accounts, Account account, String key) {
        if (accounts == null)
            return null;
        synchronized (accounts.lock) {
            Map<String, String> accountUserData = accounts.getAccountUserData(account);
            return accountUserData.get(key);
        }
    }

    public String readPasswordInternal(BUserAccounts accounts, Account account) {
        if (accounts == null)
            return null;
        synchronized (accounts.lock) {
            BAccount bAccount = accounts.getAccount(account);
            if (bAccount == null)
                return null;
            return bAccount.password;
        }
    }

    public BUserAccounts getUserAccounts(int userId) {
        synchronized (mUserAccountsMap) {
            BUserAccounts bUserAccounts = mUserAccountsMap.get(userId);
            if (bUserAccounts == null) {
                bUserAccounts = new BUserAccounts();
                bUserAccounts.userId = userId;
                mUserAccountsMap.put(userId, bUserAccounts);
            }
            return mUserAccountsMap.get(userId);
        }
    }

    private boolean isAccountPresentForCaller(String accountName, String accountType, int userId) {
        BUserAccounts userAccounts = getUserAccounts(userId);
        if (userAccounts != null) {
            BAccount account = userAccounts.getAccount(new Account(accountName, accountType));
            return account != null;
        }
        return false;
    }

    boolean removeAccountInternal(BUserAccounts accounts, Account account) {
        Set<String> recipients;
        boolean removed;
        synchronized (accounts.lock) {
            recipients = visibleAccountListeners(account, accounts);
            removed = accounts.delAccount(account);
        }
        if (removed) {
            saveAllAccounts();
            notifyAccountPackages(recipients, accounts.userId);
        }
        return removed;
    }

    private class RemoveAccountSession extends Session {
        final Account mAccount;
        public RemoveAccountSession(BUserAccounts accounts, IAccountManagerResponse response,
                                    Account account, boolean expectActivityLaunch) {
            super(accounts, response, account.type, expectActivityLaunch,
                    true /* stripAuthTokenFromResult */, account.name,
                    false /* authDetailsRequired */);
            mAccount = account;
        }

        @Override
        public void run() throws RemoteException {
            mAuthenticator.getAccountRemovalAllowed(this, mAccount);
        }

        @Override
        public void onResult(Bundle result) {
                checkResultCaller();
            if (result != null && result.containsKey(AccountManager.KEY_BOOLEAN_RESULT)
                    && !result.containsKey(AccountManager.KEY_INTENT)) {
                final boolean removalAllowed = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
                if (removalAllowed) {
                    removeAccountInternal(mAccounts, mAccount);
                }
                IAccountManagerResponse response = getResponseAndClose();
                if (response != null) {
                    try {
                        response.onResult(result);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error calling onResult()", e);
                    }
                }
            }
            super.onResult(result);
        }
    }

    private class GetAccountsByTypeAndFeatureSession extends Session {
        private final String[] mFeatures;
        private volatile Account[] mAccountsOfType = null;
        private volatile ArrayList<Account> mAccountsWithFeatures = null;
        private volatile int mCurrentAccount = 0;
        private final int mUserId;
        private final String mPackageName;
        private final boolean mIncludeManagedNotVisible;

        public GetAccountsByTypeAndFeatureSession(
                BUserAccounts accounts,
                IAccountManagerResponse response,
                String type,
                String[] features,
                int userId,
                String packageName,
                boolean includeManagedNotVisible) {
            super(accounts, response, type, false /* expectActivityLaunch */,
                    true /* stripAuthTokenFromResult */, null /* accountName */,
                    false /* authDetailsRequired */);
            mUserId = userId;
            mFeatures = features;
            mPackageName = packageName;
            mIncludeManagedNotVisible = includeManagedNotVisible;
        }

        @Override
        public void run() throws RemoteException {
            mAccountsOfType = getAccountsFromCache(mAccounts, mAccountType, mPackageName, mIncludeManagedNotVisible);
            // check whether each account matches the requested features
            mAccountsWithFeatures = new ArrayList<>(mAccountsOfType.length);
            mCurrentAccount = 0;

            checkAccount();
        }

        public void checkAccount() {
            if (mCurrentAccount >= mAccountsOfType.length) {
                sendResult();
                return;
            }

            final IAccountAuthenticator accountAuthenticator = mAuthenticator;
            if (accountAuthenticator == null) {
                // It is possible that the authenticator has died, which is indicated by
                // mAuthenticator being set to null. If this happens then just abort.
                // There is no need to send back a result or error in this case since
                // that already happened when mAuthenticator was cleared.
                return;
            }
            try {
                accountAuthenticator.hasFeatures(this, mAccountsOfType[mCurrentAccount], mFeatures);
            } catch (RemoteException e) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "remote exception");
            }
        }

        @Override
        public void onResult(Bundle result) {
                checkResultCaller();
            mNumResults++;
            if (result == null) {
                onError(AccountManager.ERROR_CODE_INVALID_RESPONSE, "null bundle");
                return;
            }
            if (result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) {
                mAccountsWithFeatures.add(mAccountsOfType[mCurrentAccount]);
            }
            mCurrentAccount++;
            checkAccount();
        }

        public void sendResult() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    Account[] accounts = new Account[mAccountsWithFeatures.size()];
                    for (int i = 0; i < accounts.length; i++) {
                        accounts[i] = mAccountsWithFeatures.get(i);
                    }
                    Bundle result = new Bundle();
                    result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts);
                    response.onResult(result);
                } catch (RemoteException e) {
                    // if the caller is dead then there is no one to care about remote exceptions
                }
            }
        }
    }

    private static final class AuthenticatorInfo {
        final AuthenticatorDescription desc;
        final ServiceInfo serviceInfo;

        AuthenticatorInfo(AuthenticatorDescription desc, ServiceInfo info) {
            this.desc = desc;
            this.serviceInfo = info;
        }
    }

    private static AuthenticatorDescription parseAuthenticatorDescription(Resources resources, String packageName,
                                                                          AttributeSet attributeSet) {
        RstyleableStatic rstyleableStatic = BRRstyleable.get();
        TypedArray array = resources.obtainAttributes(attributeSet, rstyleableStatic.AccountAuthenticator());
        try {
            String accountType = array.getString(rstyleableStatic.AccountAuthenticator_accountType());
            int label = array.getResourceId(rstyleableStatic.AccountAuthenticator_label(), 0);
            int icon = array.getResourceId(rstyleableStatic.AccountAuthenticator_icon(), 0);
            int smallIcon = array.getResourceId(rstyleableStatic.AccountAuthenticator_smallIcon(), 0);
            int accountPreferences = array.getResourceId(rstyleableStatic.AccountAuthenticator_accountPreferences(), 0);
            boolean customTokens = array.getBoolean(rstyleableStatic.AccountAuthenticator_customTokens(), false);
            if (TextUtils.isEmpty(accountType)) {
                return null;
            }
            return new AuthenticatorDescription(accountType, packageName, label, icon, smallIcon, accountPreferences,
                    customTokens);
        } finally {
            array.recycle();
        }
    }

    private Map<String, AuthenticatorInfo> getAuthenticators(int userId) {
        Map<String, AuthenticatorInfo> authenticators = new HashMap<>();
        Intent intent = new Intent(AccountManager.ACTION_AUTHENTICATOR_INTENT);
        generateServicesMap(mPms.queryIntentServices(intent, PackageManager.GET_META_DATA, userId),
                authenticators, new RegisteredServicesParser(), userId);
        return authenticators;
    }

    private AuthenticatorInfo getAuthenticatorInfo(String accountType, int userId) {
        return getAuthenticators(userId).get(accountType);
    }

    private void generateServicesMap(List<ResolveInfo> services, Map<String, AuthenticatorInfo> map,
                                     RegisteredServicesParser accountParser, int userId) {
        java.util.Set<String> conflicts = new java.util.HashSet<>();
        for (ResolveInfo info : services) {
            XmlResourceParser parser = accountParser.getParser(mContext, info.serviceInfo,
                    AccountManager.AUTHENTICATOR_META_DATA_NAME);
            if (parser != null) {
                try {
                    AttributeSet attributeSet = Xml.asAttributeSet(parser);
                    int type;
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
                        // Nothing to do
                    }
                    if (AccountManager.AUTHENTICATOR_ATTRIBUTES_NAME.equals(parser.getName())) {
                        AuthenticatorDescription desc = parseAuthenticatorDescription(
                                accountParser.getResources(mContext, info.serviceInfo.applicationInfo),
                                info.serviceInfo.packageName, attributeSet);
                        if (desc != null && !conflicts.contains(desc.type)) {
                            if (desc.type.startsWith("com.google") &&
                                    !mPms.isTrustedMicroG(info.serviceInfo.packageName, userId)) continue;
                            AuthenticatorInfo previous = map.get(desc.type);
                            if (previous != null && !previous.serviceInfo.packageName.equals(info.serviceInfo.packageName)) {
                                map.remove(desc.type);
                                conflicts.add(desc.type);
                            } else {
                                map.put(desc.type, new AuthenticatorInfo(desc, info.serviceInfo));
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    parser.close();
                }
            }
        }
    }

    private abstract class Session extends IAccountAuthenticatorResponse.Stub
            implements IBinder.DeathRecipient, ServiceConnection {
        IAccountManagerResponse mResponse;
        final String mAccountType;
        final boolean mExpectActivityLaunch;
        final long mCreationTime;
        final String mAccountName;
        // Indicates if we need to add auth details(like last credential time)
        final boolean mAuthDetailsRequired;
        // If set, we need to update the last authenticated time. This is
        // currently
        // used on
        // successful confirming credentials.
        final boolean mUpdateLastAuthenticatedTime;

        public int mNumResults = 0;
        private int mNumRequestContinued = 0;
        private int mNumErrors = 0;

        IAccountAuthenticator mAuthenticator = null;

        private final boolean mStripAuthTokenFromResult;
        protected final BUserAccounts mAccounts;

        public Session(BUserAccounts accounts, IAccountManagerResponse response, String accountType,
                       boolean expectActivityLaunch, boolean stripAuthTokenFromResult, String accountName,
                       boolean authDetailsRequired) {
            this(accounts, response, accountType, expectActivityLaunch, stripAuthTokenFromResult,
                    accountName, authDetailsRequired, false /* updateLastAuthenticatedTime */);
        }

        public Session(BUserAccounts accounts, IAccountManagerResponse response, String accountType,
                       boolean expectActivityLaunch, boolean stripAuthTokenFromResult, String accountName,
                       boolean authDetailsRequired, boolean updateLastAuthenticatedTime) {
            super();
            //if (response == null) throw new IllegalArgumentException("response is null");
            if (accountType == null) throw new IllegalArgumentException("accountType is null");
            mAccounts = accounts;
            mStripAuthTokenFromResult = stripAuthTokenFromResult;
            mResponse = response;
            mAccountType = accountType;
            mExpectActivityLaunch = expectActivityLaunch;
            mCreationTime = SystemClock.elapsedRealtime();
            mAccountName = accountName;
            mAuthDetailsRequired = authDetailsRequired;
            mUpdateLastAuthenticatedTime = updateLastAuthenticatedTime;

            synchronized (mSessions) {
                mSessions.put(toString(), this);
            }
            if (response != null) {
                try {
                    response.asBinder().linkToDeath(this, 0 /* flags */);
                } catch (RemoteException e) {
                    mResponse = null;
                    binderDied();
                }
            }
        }

        protected void checkResultCaller() {
            if (Binder.getCallingPid() != android.os.Process.myPid()) {
                requireAuthenticator(mAccountType, mAccounts.userId);
            }
        }

        IAccountManagerResponse getResponseAndClose() {
            if (mResponse == null) {
                // this session has already been closed
                return null;
            }
            IAccountManagerResponse response = mResponse;
            close(); // this clears mResponse so we need to save the response before this call
            return response;
        }

        /**
         * Checks Intents, supplied via KEY_INTENT, to make sure that they don't violate our
         * security policy.
         * <p>
         * In particular we want to make sure that the Authenticator doesn't try to trick users
         * into launching arbitrary intents on the device via by tricking to click authenticator
         * supplied entries in the system Settings app.
         */
        protected boolean checkKeyIntent(int authUid, Intent intent) {
            // Explicitly set an empty ClipData to ensure that we don't offer to
            // promote any Uris contained inside for granting purposes
            if (intent.getClipData() == null) {
                intent.setClipData(ClipData.newPlainText(null, null));
            }
            intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
            long bid = Binder.clearCallingIdentity();
            try {
                ResolveInfo resolveInfo = mPms.resolveActivity(intent, 0, null, mAccounts.userId);
                if (resolveInfo == null) {
                    return false;
                }
                return isAuthenticatorPackage(resolveInfo.activityInfo.packageName, mAccountType, mAccounts.userId);
            } finally {
                Binder.restoreCallingIdentity(bid);
            }
        }

        private void close() {
            synchronized (mSessions) {
                if (mSessions.remove(toString()) == null) {
                    // the session was already closed, so bail out now
                    return;
                }
            }
            if (mResponse != null) {
                // stop listening for response deaths
                mResponse.asBinder().unlinkToDeath(this, 0 /* flags */);

                // clear this so that we don't accidentally send any further results
                mResponse = null;
            }
            cancelTimeout();
            unbind();
        }

        @Override
        public void binderDied() {
            mResponse = null;
            close();
        }

        protected String toDebugString() {
            return toDebugString(SystemClock.elapsedRealtime());
        }

        protected String toDebugString(long now) {
            return "Session: expectLaunch " + mExpectActivityLaunch
                    + ", connected " + (mAuthenticator != null)
                    + ", stats (" + mNumResults + "/" + mNumRequestContinued
                    + "/" + mNumErrors + ")"
                    + ", lifetime " + ((now - mCreationTime) / 1000.0);
        }

        void bind() {
            if (!bindToAuthenticator(mAccountType)) {
                Log.d(TAG, "bind attempt failed for " + toDebugString());
                failSession(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "bind failure");
            }
        }

        private void unbind() {
            if (mAuthenticator != null) {
                mAuthenticator = null;
                mContext.unbindService(this);
            }
        }

        public void cancelTimeout() {
            mHandler.removeMessages(MESSAGE_TIMED_OUT, this);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mAuthenticator = IAccountAuthenticator.Stub.asInterface(SynchronousBinder.wrap(service));
            mAuthenticatorCalls.execute(() -> {
                try {
                    run();
                } catch (RemoteException failure) {
                    failSession(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Authenticator connection failed");
                } catch (RuntimeException failure) {
                    failSession(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                            "Authenticator call failed: " + failure.getClass().getSimpleName());
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mAuthenticator = null;
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    response.onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                            "disconnected");
                } catch (RemoteException e) {
                }
            }
        }

        public abstract void run() throws RemoteException;

        public void onTimedOut() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    response.onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                            "timeout");
                } catch (RemoteException e) {
                }
            }
        }

        @Override
        public void onResult(Bundle result) {
                checkResultCaller();
            mNumResults++;
            Intent intent = null;
            if (result != null) {
                boolean isSuccessfulConfirmCreds = result.getBoolean(
                        AccountManager.KEY_BOOLEAN_RESULT, false);
                boolean isSuccessfulUpdateCredsOrAddAccount =
                        result.containsKey(AccountManager.KEY_ACCOUNT_NAME)
                                && result.containsKey(AccountManager.KEY_ACCOUNT_TYPE);
                // We should only update lastAuthenticated time, if
                // mUpdateLastAuthenticatedTime is true and the confirmRequest
                // or updateRequest was successful
                boolean needUpdate = mUpdateLastAuthenticatedTime
                        && (isSuccessfulConfirmCreds || isSuccessfulUpdateCredsOrAddAccount);
                if (needUpdate || mAuthDetailsRequired) {
                    boolean accountPresent = isAccountPresentForCaller(mAccountName, mAccountType, mAccounts.userId);
                    if (needUpdate && accountPresent) {
                        updateLastAuthenticatedTime(mAccounts, new Account(mAccountName, mAccountType));
                    }
                    if (mAuthDetailsRequired) {
                        long lastAuthenticatedTime = -1;
                        if (accountPresent) {
                            lastAuthenticatedTime = mAccounts
                                    .findAccountLastAuthenticatedTime(
                                            new Account(mAccountName, mAccountType));
                        }
                        result.putLong(AccountManager.KEY_LAST_AUTHENTICATED_TIME,
                                lastAuthenticatedTime);
                    }
                }
            }
            if (result != null
                    && (intent = result.getParcelable(AccountManager.KEY_INTENT)) != null) {
                if (!checkKeyIntent(
                        Binder.getCallingUid(),
                        intent)) {
                    onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                            "invalid intent in bundle returned");
                    return;
                }
            }
            if (result != null
                    && !TextUtils.isEmpty(result.getString(AccountManager.KEY_AUTHTOKEN))) {
                String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
                    Account account = new Account(accountName, accountType);
//                    cancelNotification(getSigninRequiredNotificationId(mAccounts, account),
//                            new UserHandle(mAccounts.userId));
                }
            }
            IAccountManagerResponse response;
            if (mExpectActivityLaunch && result != null
                    && result.containsKey(AccountManager.KEY_INTENT)) {
                response = mResponse;
            } else {
                response = getResponseAndClose();
            }
            if (response != null) {
                try {
                    if (result == null) {
                        response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                "null bundle returned");
                    } else {
                        if (mStripAuthTokenFromResult) {
                            result.remove(AccountManager.KEY_AUTHTOKEN);
                        }
                        if ((result.getInt(AccountManager.KEY_ERROR_CODE, -1) > 0) &&
                                (intent == null)) {
                            // All AccountManager error codes are greater than 0
                            response.onError(result.getInt(AccountManager.KEY_ERROR_CODE),
                                    result.getString(AccountManager.KEY_ERROR_MESSAGE));
                        } else {
                            response.onResult(result);
                        }
                    }
                } catch (RemoteException e) {
                    // if the caller is dead then there is no one to care about remote exceptions
                }
            }
        }

        @Override
        public void onRequestContinued() {
            checkResultCaller();
            mNumRequestContinued++;
        }

        @Override
        public void onError(int errorCode, String errorMessage) {
            checkResultCaller();
            failSession(errorCode, errorMessage);
        }

        private void failSession(int errorCode, String errorMessage) {
            mNumErrors++;
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    response.onError(errorCode, errorMessage);
                } catch (RemoteException e) {
                }
            } else {
            }
        }

        /**
         * find the component name for the authenticator and initiate a bind
         * if no authenticator or the bind fails then return false, otherwise return true
         */
        private boolean bindToAuthenticator(String authenticatorType) {
            AuthenticatorInfo authenticatorInfo = getAuthenticatorInfo(authenticatorType, mAccounts.userId);
            if (authenticatorInfo == null) {
                return false;
            }

//            if (!isLocalUnlockedUser(mAccounts.userId)
//                    && !authenticatorInfo.componentInfo.directBootAware) {
//                Slog.w(TAG, "Blocking binding to authenticator " + authenticatorInfo.componentName
//                        + " which isn't encryption aware");
//                return false;
//            }

            Intent intent = new Intent();
            intent.setAction(AccountManager.ACTION_AUTHENTICATOR_INTENT);
            ComponentName componentName = new ComponentName(authenticatorInfo.serviceInfo.packageName, authenticatorInfo.serviceInfo.name);
            intent.setComponent(componentName);
            // call userId
            intent.putExtra("_B_|_UserId", mAccounts.userId);

            int flags = Context.BIND_AUTO_CREATE;
            if (!mContext.bindService(intent, this, flags)) {
                return false;
            }

            return true;
        }
    }

    private void onResult(IAccountManagerResponse response, Bundle result) {
        if (result == null) {
            Log.e(TAG, "the result is unexpectedly null", new Exception());
        }
        try {
            response.onResult(result);
        } catch (RemoteException e) {
            // if the caller is dead then there is no one to care about remote
            // exceptions
        }
    }


    private boolean updateLastAuthenticatedTime(BUserAccounts userAccounts, Account account) {
        userAccounts.updateLastAuthenticatedTime(account);
        return true;
    }

    private ProcessRecord requireCaller(int userId) {
        ProcessRecord caller = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        AccountAccessPolicy.enforceCaller(caller == null ? -1 : caller.userId, userId,
                Binder.getCallingUid() == BlackBoxCore.getHostUid());
        return caller;
    }

    private void requireAuthenticator(String accountType, int userId) {
        ProcessRecord caller = requireCaller(userId);
        if (accountType == null || !isAuthenticatorPackage(caller.getPackageName(), accountType, userId)) {
            throw new SecurityException("Account credentials are accessible only to their authenticator");
        }
    }

    private boolean isAuthenticatorPackage(String packageName, String accountType, int userId) {
        AuthenticatorInfo authenticator = getAuthenticatorInfo(accountType, userId);
        if (authenticator == null) return false;
        if (accountType.startsWith("com.google")
                && !mPms.isTrustedMicroG(authenticator.serviceInfo.packageName, userId)) return false;
        BPackageSettings caller = mPms.getBPackageSetting(packageName);
        BPackageSettings owner = mPms.getBPackageSetting(authenticator.serviceInfo.packageName);
        if (caller == null || owner == null || !caller.getInstalled(userId) || !owner.getInstalled(userId)) return false;
        return AccountAccessPolicy.sameActualSigners(
                MicroGSignaturePolicy.actualSignerDigests(caller.pkg.mSignatures),
                MicroGSignaturePolicy.actualSignerDigests(owner.pkg.mSignatures));
    }

    private Bundle verifiedOptions(Bundle supplied, ProcessRecord caller) {
        Bundle options = supplied == null ? new Bundle() : new Bundle(supplied);
        AccountAccessPolicy.enforceClaimedPackage(caller.getPackageName(),
                options.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME));
        options.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, caller.getPackageName());
        options.putInt(AccountManager.KEY_CALLER_UID, caller.buid);
        options.putInt(AccountManager.KEY_CALLER_PID, caller.pid);
        return options;
    }

    private String getCallingPackageName() {
        int callingPid = Binder.getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            throw new IllegalArgumentException("ProcessRecord is null, PID: " + callingPid);
        return processByPid.getPackageName();
    }
}
