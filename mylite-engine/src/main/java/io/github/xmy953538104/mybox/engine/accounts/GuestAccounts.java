package io.github.xmy953538104.mybox.engine.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Installs one process-wide virtual AccountManager endpoint before guest Application creation. */
public final class GuestAccounts implements InvocationHandler {
    private static final String MANAGER = "android.accounts.IAccountManager";
    private static volatile GuestAccounts installed;
    private final String guestPackage, hostPackage;
    private final IVirtualAccountService service;
    private final IBinder endpoint;
    private final int serverPid;
    private final QueryObservations observations;

    private GuestAccounts(String guestPackage, String hostPackage, IBinder endpoint, boolean diagnostics) throws RemoteException {
        if (endpoint == null || !endpoint.isBinderAlive()) throw new IllegalArgumentException("Virtual account service is unavailable");
        this.guestPackage = guestPackage; this.hostPackage = hostPackage;
        this.endpoint = endpoint; service = IVirtualAccountService.Stub.asInterface(endpoint);
        observations = diagnostics ? new QueryObservations() : null;
        Bundle identity = execute("describe", request(), null);
        serverPid = identity.getInt("serverPid", -1);
        if (serverPid <= 0 || serverPid == Process.myPid() || identity.getInt("serverUid", -1) != Process.myUid()) {
            throw new SecurityException("Account service is not a separate engine process");
        }
    }

    @SuppressWarnings("unchecked")
    public static synchronized void install(Context context, String guestPackage, IBinder endpoint) throws Exception {
        if (installed != null) {
            if (!installed.guestPackage.equals(guestPackage) || installed.endpoint != endpoint) throw new SecurityException("Account process identity is already sealed");
            return;
        }
        GuestAccounts handler = new GuestAccounts(guestPackage, context.getPackageName(), endpoint,
                (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        Class<?> contract = Class.forName(MANAGER);
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, handler);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(MANAGER); return true; }
                throw new UnsupportedOperationException("Raw platform account transactions are not supported");
            }
        };
        local.attachInterface((IInterface) proxy, MANAGER);
        Field cacheField = field(Class.forName("android.os.ServiceManager"), "sCache");
        Map<String, IBinder> cache = (Map<String, IBinder>) cacheField.get(null);
        synchronized (cache) { cache.put(Context.ACCOUNT_SERVICE, local); }
        AccountManager manager = AccountManager.get(context);
        field(AccountManager.class, "mService").set(manager, proxy);
        disableCache(manager, "disableLocalAccountCaches", "mAccountsForUserCache");
        disableCache(manager, "disableLocalUserInfoCaches", "mUserDataCache");
        Object readBack = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class)
                .invoke(null, Context.ACCOUNT_SERVICE);
        if (readBack != local || field(AccountManager.class, "mService").get(manager) != proxy) {
            throw new IllegalStateException("Virtual account endpoint installation did not stick");
        }
        installed = handler;
    }

    public static boolean isAuthenticator(ServiceInfo info) throws RemoteException {
        GuestAccounts current = requireInstalled();
        if (info == null || !current.guestPackage.equals(info.packageName)) return false;
        Bundle request = current.request(); request.putString("component", info.name);
        return current.execute("isAuthenticator", request, null).getBoolean("value");
    }

    static boolean isAuthenticatorPeer(int uid, int pid) throws RemoteException {
        if (uid != Process.myUid() || pid <= 0) return false;
        GuestAccounts current = requireInstalled();
        Bundle request = current.request(); request.putInt("realUid", uid); request.putInt("realPid", pid);
        return current.execute("authorizeAuthenticatorPeer", request, null).getBoolean("value");
    }

    private static GuestAccounts requireInstalled() {
        GuestAccounts current = installed;
        if (current == null) throw new IllegalStateException("Virtual account service has not been installed");
        return current;
    }

    private static void disableCache(AccountManager manager, String operation, String backingField) throws Exception {
        try { AccountManager.class.getDeclaredMethod(operation).invoke(manager); }
        catch (NoSuchMethodException absent) {
            // Old Android releases without these caches need no cache invalidation bypass.
            try { field(AccountManager.class, backingField); }
            catch (NoSuchFieldException noCache) { return; }
            throw new IllegalStateException("Account cache exists but cannot be disabled", absent);
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] supplied) throws Throwable {
        long trace = io.github.xmy953538104.mybox.engine.EngineDiagnostics.begin("account_api", method.getName(), 0);
        try {
            Object result = invokeOperation(proxy, method, supplied);
            io.github.xmy953538104.mybox.engine.EngineDiagnostics.event("account_api", "returned", trace,
                    result instanceof android.accounts.Account[] ? ((android.accounts.Account[]) result).length : 0);
            return result;
        } catch (Throwable failure) {
            io.github.xmy953538104.mybox.engine.EngineDiagnostics.failure("account_api", method.getName(), trace, failure); throw failure;
        }
    }
    private Object invokeOperation(Object proxy, Method method, Object[] supplied) throws Throwable {
        String name = method.getName(); Object[] args = supplied == null ? new Object[0] : supplied;
        if (name.equals("asBinder")) return endpoint;
        if (name.equals("toString")) return "Mybox virtual AccountManager";
        if (name.equals("hashCode")) return System.identityHashCode(proxy);
        if (name.equals("equals")) return proxy == args[0];
        Bundle request = request(); IVirtualAccountResponse response = null;
        String operation = name;
        switch (name) {
            case "getPassword": case "getPreviousName": case "removeAccountExplicitly":
            case "accountAuthenticated": case "someUserHasAccount": case "getPackagesAndVisibilityForAccount":
                signature(method, "android.accounts.Account"); account(request, args[0]); break;
            case "getUserData": case "peekAuthToken":
                signature(method, "android.accounts.Account", "java.lang.String"); account(request, args[0]);
                request.putString(name.equals("getUserData") ? "key" : "tokenType", (String) args[1]); break;
            case "setPassword":
                signature(method, "android.accounts.Account", "java.lang.String"); account(request, args[0]); request.putString("password", (String) args[1]); break;
            case "clearPassword":
                signature(method, "android.accounts.Account"); account(request, args[0]); operation = "setPassword"; break;
            case "setAuthToken": case "setUserData":
                signature(method, "android.accounts.Account", "java.lang.String", "java.lang.String"); account(request, args[0]);
                request.putString(name.equals("setAuthToken") ? "tokenType" : "key", (String) args[1]); request.putString("value", (String) args[2]); break;
            case "invalidateAuthToken":
                signature(method, "java.lang.String", "java.lang.String"); request.putString("type", (String) args[0]); request.putString("token", (String) args[1]); break;
            case "getAuthenticatorTypes":
                signature(method, "int"); request.putInt("userId", (Integer) args[0]); break;
            case "getAccountsAsUser":
                signature(method, "java.lang.String", "int", "java.lang.String"); request.putString("type", (String) args[0]);
                request.putInt("userId", (Integer) args[1]); checkOp(args[2]); operation = "getAccounts"; break;
            case "getAccounts":
                signature(method, "java.lang.String", "java.lang.String"); request.putString("type", (String) args[0]); checkOp(args[1]); break;
            case "getAccountsForPackage":
                signature(method, "java.lang.String", "int", "java.lang.String"); request.putString("targetPackage", (String) args[0]);
                request.putInt("targetUid", (Integer) args[1]); checkOp(args[2]); operation = "getAccounts"; break;
            case "getAccountsByTypeForPackage":
                signature(method, "java.lang.String", "java.lang.String", "java.lang.String"); request.putString("type", (String) args[0]);
                request.putString("targetPackage", (String) args[1]); checkOp(args[2]); break;
            case "getAccountsAndVisibilityForPackage":
                signature(method, "java.lang.String", "java.lang.String"); request.putString("targetPackage", (String) args[0]); request.putString("type", (String) args[1]); break;
            case "addAccountExplicitly":
                if (args.length == 3) signature(method, "android.accounts.Account", "java.lang.String", "android.os.Bundle");
                else { signature(method, "android.accounts.Account", "java.lang.String", "android.os.Bundle", "java.lang.String"); checkOp(args[3]); }
                account(request, args[0]); request.putString("password", (String) args[1]); request.putBundle("extras", (Bundle) args[2]); break;
            case "addAccountExplicitlyWithVisibility":
                if (args.length == 4) signature(method, "android.accounts.Account", "java.lang.String", "android.os.Bundle", "java.util.Map");
                else { signature(method, "android.accounts.Account", "java.lang.String", "android.os.Bundle", "java.util.Map", "java.lang.String"); checkOp(args[4]); }
                account(request, args[0]); request.putString("password", (String) args[1]); request.putBundle("extras", (Bundle) args[2]);
                request.putBundle("visibility", visibility((Map<?, ?>) args[3])); operation = "addAccountExplicitly"; break;
            case "setAccountVisibility":
                signature(method, "android.accounts.Account", "java.lang.String", "int"); account(request, args[0]);
                request.putString("targetPackage", (String) args[1]); request.putInt("visibility", (Integer) args[2]); break;
            case "getAccountVisibility":
                signature(method, "android.accounts.Account", "java.lang.String"); account(request, args[0]); request.putString("targetPackage", (String) args[1]); break;
            case "registerAccountListener": case "unregisterAccountListener":
                signature(method, "[Ljava.lang.String;", "java.lang.String"); request.putStringArray("types", (String[]) args[0]); checkOp(args[1]); break;
            case "updateAppPermission":
                signature(method, "android.accounts.Account", "java.lang.String", "int", "boolean"); account(request, args[0]);
                request.putString("tokenType", (String) args[1]); request.putInt("targetUid", (Integer) args[2]); request.putBoolean("value", (Boolean) args[3]); break;
            case "hasAccountAccess":
                signature(method, "android.accounts.Account", "java.lang.String", "android.os.UserHandle"); account(request, args[0]);
                request.putString("targetPackage", (String) args[1]); request.putInt("userId", userId(args[2])); break;
            case "renameAccount":
                signature(method, "android.accounts.IAccountManagerResponse", "android.accounts.Account", "java.lang.String");
                response = response(args[0]); account(request, args[1]); request.putString("newName", (String) args[2]); break;
            case "getAccountsByFeatures": case "getAccountByTypeAndFeatures":
                signature(method, "android.accounts.IAccountManagerResponse", "java.lang.String", "[Ljava.lang.String;", "java.lang.String");
                response = response(args[0]); request.putString("type", (String) args[1]); request.putStringArray("features", (String[]) args[2]); checkOp(args[3]); break;
            case "hasFeatures":
                if (args.length == 4) { signature(method, "android.accounts.IAccountManagerResponse", "android.accounts.Account", "[Ljava.lang.String;", "java.lang.String"); checkOp(args[3]); }
                else { signature(method, "android.accounts.IAccountManagerResponse", "android.accounts.Account", "[Ljava.lang.String;", "int", "java.lang.String"); request.putInt("userId", (Integer) args[3]); checkOp(args[4]); }
                response = response(args[0]); account(request, args[1]); request.putStringArray("features", (String[]) args[2]); break;
            case "getAuthToken":
                signature(method, "android.accounts.IAccountManagerResponse", "android.accounts.Account", "java.lang.String", "boolean", "boolean", "android.os.Bundle");
                response = response(args[0]); account(request, args[1]); request.putString("tokenType", (String) args[2]);
                request.putBoolean("expectActivity", (Boolean) args[4]); request.putBundle("options", (Bundle) args[5]); break;
            case "addAccount": case "addAccountAsUser":
                if (name.equals("addAccount")) signature(method, "android.accounts.IAccountManagerResponse", "java.lang.String", "java.lang.String", "[Ljava.lang.String;", "boolean", "android.os.Bundle");
                else { signature(method, "android.accounts.IAccountManagerResponse", "java.lang.String", "java.lang.String", "[Ljava.lang.String;", "boolean", "android.os.Bundle", "int"); request.putInt("userId", (Integer) args[6]); }
                response = response(args[0]); request.putString("type", (String) args[1]); request.putString("tokenType", (String) args[2]);
                request.putStringArray("features", (String[]) args[3]); request.putBoolean("expectActivity", (Boolean) args[4]); request.putBundle("options", (Bundle) args[5]); operation = "addAccount"; break;
            case "updateCredentials":
                signature(method, "android.accounts.IAccountManagerResponse", "android.accounts.Account", "java.lang.String", "boolean", "android.os.Bundle");
                response = response(args[0]); account(request, args[1]); request.putString("tokenType", (String) args[2]);
                request.putBoolean("expectActivity", (Boolean) args[3]); request.putBundle("options", (Bundle) args[4]); break;
            case "confirmCredentialsAsUser":
                signature(method, "android.accounts.IAccountManagerResponse", "android.accounts.Account", "android.os.Bundle", "boolean", "int");
                response = response(args[0]); account(request, args[1]); request.putBundle("options", (Bundle) args[2]);
                request.putBoolean("expectActivity", (Boolean) args[3]); request.putInt("userId", (Integer) args[4]); operation = "confirmCredentials"; break;
            case "editProperties":
                signature(method, "android.accounts.IAccountManagerResponse", "java.lang.String", "boolean"); response = response(args[0]);
                request.putString("type", (String) args[1]); request.putBoolean("expectActivity", (Boolean) args[2]); break;
            case "getAuthTokenLabel":
                signature(method, "android.accounts.IAccountManagerResponse", "java.lang.String", "java.lang.String"); response = response(args[0]);
                request.putString("type", (String) args[1]); request.putString("tokenType", (String) args[2]); break;
            case "removeAccountAsUser":
                signature(method, "android.accounts.IAccountManagerResponse", "android.accounts.Account", "boolean", "int"); response = response(args[0]);
                account(request, args[1]); request.putBoolean("expectActivity", (Boolean) args[2]); request.putInt("userId", (Integer) args[3]); operation = "removeAccount"; break;
            case "isCredentialsUpdateSuggested":
                signature(method, "android.accounts.IAccountManagerResponse", "android.accounts.Account", "java.lang.String"); response = response(args[0]);
                account(request, args[1]); request.putString("statusToken", (String) args[2]); break;
            default: throw new UnsupportedOperationException("Virtual AccountManager does not implement " + name);
        }
        Bundle result = execute(operation, request, response);
        if (method.getReturnType() == void.class) return null;
        if (method.getReturnType() == Account[].class) return accounts(result.getParcelableArray("value"));
        if (method.getReturnType() == AuthenticatorDescription[].class) {
            Parcelable[] values = result.getParcelableArray("value");
            AuthenticatorDescription[] descriptions = new AuthenticatorDescription[values.length];
            for (int i = 0; i < values.length; i++) descriptions[i] = (AuthenticatorDescription) values[i];
            return descriptions;
        }
        if (name.equals("getPackagesAndVisibilityForAccount")) return AccountWire.visibility(result.getBundle("value"));
        if (name.equals("getAccountsAndVisibilityForPackage")) {
            Account[] values = accounts(result.getParcelableArray("accounts")); int[] visibility = result.getIntArray("visibility");
            if (visibility == null || values.length != visibility.length) throw new IllegalStateException("Invalid visibility response");
            Map<Account, Integer> map = new HashMap<>(); for (int i = 0; i < values.length; i++) map.put(values[i], visibility[i]); return map;
        }
        return result.get("value");
    }

    private IVirtualAccountResponse response(Object value) {
        if (!(value instanceof IInterface)) throw new IllegalArgumentException("Platform account response is absent");
        IBinder target = ((IInterface) value).asBinder();
        return new IVirtualAccountResponse.Stub() {
            private void requireServer() {
                if (Binder.getCallingPid() != serverPid || Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Account result requires its engine server");
            }
            @Override public void onResult(Bundle result) throws RemoteException { requireServer(); AccountWire.callback(target, AccountWire.MANAGER_RESPONSE, result, 0, null); }
            @Override public void onError(int code, String message) throws RemoteException { requireServer(); AccountWire.callback(target, AccountWire.MANAGER_RESPONSE, null, code, message); }
        };
    }

    private Bundle request() { Bundle request = new Bundle(); request.putInt("version", AccountWire.VERSION); request.putString("opPackage", guestPackage); return request; }
    private Bundle execute(String operation, Bundle request, IVirtualAccountResponse response) throws RemoteException {
        int observation = observations == null ? -1 : observations.begin(operation);
        if (observations != null && observations.retainEnumerationCaller(observation)) {
            Throwable callsite = new Throwable("Account enumeration call site; arguments omitted");
            StackTraceElement[] frames = callsite.getStackTrace();
            callsite.setStackTrace(java.util.Arrays.copyOf(frames, Math.min(24, frames.length)));
            android.util.Log.i("MyliteAccounts", "Ordinary account enumeration caller", callsite);
        }
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle result = service.execute(operation, request, response);
            if (result == null) throw new IllegalStateException("Virtual account response is absent");
            result.setClassLoader(Account.class.getClassLoader());
            if (observation >= 0) {
                int cardinality = -1, visibility = -1;
                if (observation <= 2) {
                    Parcelable[] values = result.getParcelableArray(observation == 2 ? "accounts" : "value");
                    cardinality = values == null ? 0 : values.length;
                } else if (observation == 3) visibility = result.getInt("value", -1);
                observations.completed(observation, cardinality, visibility);
            }
            return result;
        } catch (RemoteException | RuntimeException failure) {
            if (observation >= 0) observations.failed(observation);
            throw failure;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    public static void logDiagnostics() {
        GuestAccounts current = installed;
        if (current != null && current.observations != null) android.util.Log.i("MyliteAccounts",
                "Queries [ordinary,typedOwner,visibilityMap,getVisibility,setVisibility,token,add,confirm]"
                + " x [attempt,syncResponse,error,emptyList,nonemptyList]=" + java.util.Arrays.toString(current.observations.snapshot())
                + " visibility [undefined,visible,userVisible,hidden,userHidden]=" + java.util.Arrays.toString(current.observations.visibilitySnapshot()));
    }

    static final class QueryObservations {
        private final java.util.concurrent.atomic.AtomicIntegerArray counts = new java.util.concurrent.atomic.AtomicIntegerArray(40);
        private final java.util.concurrent.atomic.AtomicIntegerArray visibility = new java.util.concurrent.atomic.AtomicIntegerArray(5);
        int begin(String operation) {
            int index;
            switch (operation) {
                case "getAccounts": index = 0; break;
                case "getAccountsByTypeForPackage": index = 1; break;
                case "getAccountsAndVisibilityForPackage": index = 2; break;
                case "getAccountVisibility": index = 3; break;
                case "setAccountVisibility": index = 4; break;
                case "getAuthToken": index = 5; break;
                case "addAccount": index = 6; break;
                case "confirmCredentials": index = 7; break;
                default: return -1;
            }
            increment(counts, index * 5); return index;
        }
        void completed(int index, int cardinality, int value) {
            increment(counts, index * 5 + 1);
            if (cardinality >= 0) increment(counts, index * 5 + (cardinality == 0 ? 3 : 4));
            if (value >= 0 && value <= 4) increment(visibility, value);
        }
        void failed(int index) { increment(counts, index * 5 + 2); }
        boolean retainEnumerationCaller(int index) {
            int attempts = counts.get(0);
            return index == 0 && attempts > 0 && attempts <= 2;
        }
        int[] snapshot() { return copy(counts); }
        int[] visibilitySnapshot() { return copy(visibility); }
        private static int[] copy(java.util.concurrent.atomic.AtomicIntegerArray values) {
            int[] copy = new int[values.length()]; for (int i = 0; i < copy.length; i++) copy[i] = values.get(i); return copy;
        }
        private static void increment(java.util.concurrent.atomic.AtomicIntegerArray values, int index) {
            values.updateAndGet(index, previous -> previous == 1000000 ? previous : previous + 1);
        }
    }
    private void checkOp(Object value) {
        if (value != null && !guestPackage.equals(value) && !hostPackage.equals(value)) throw new SecurityException("Claimed account package does not match guest");
    }
    private static void account(Bundle request, Object value) { if (!(value instanceof Account)) throw new IllegalArgumentException("Account is absent"); request.putParcelable("account", (Account) value); }
    private static int userId(Object handle) throws Exception {
        if (handle == null) throw new IllegalArgumentException("User handle is absent");
        return (Integer) handle.getClass().getMethod("getIdentifier").invoke(handle);
    }
    private static Bundle visibility(Map<?, ?> values) {
        Bundle result = new Bundle();
        if (values != null) for (Map.Entry<?, ?> item : values.entrySet()) {
            if (!(item.getKey() instanceof String) || !(item.getValue() instanceof Integer)) throw new IllegalArgumentException("Invalid account visibility map");
            result.putInt((String) item.getKey(), (Integer) item.getValue());
        }
        return result;
    }
    private static Account[] accounts(Parcelable[] values) {
        if (values == null) throw new IllegalStateException("Account list is absent");
        Account[] accounts = new Account[values.length]; for (int i = 0; i < values.length; i++) accounts[i] = (Account) values[i]; return accounts;
    }
    static void signature(Method method, String... expected) {
        Class<?>[] actual = method.getParameterTypes();
        if (actual.length != expected.length) throw new UnsupportedOperationException("Unsupported platform account signature: " + method.getName());
        for (int i = 0; i < actual.length; i++) if (!actual[i].getName().equals(expected[i])) {
            throw new UnsupportedOperationException("Unsupported platform account signature: " + method.getName());
        }
    }
}
