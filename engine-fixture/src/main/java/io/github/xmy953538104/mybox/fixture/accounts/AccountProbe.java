package io.github.xmy953538104.mybox.fixture.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.accounts.AuthenticatorException;
import android.accounts.OnAccountsUpdateListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Exercises Android AccountManager against a real cross-process, ordinary platform authenticator. */
public final class AccountProbe {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    public static void run(Context context) {
        if (!RUNNING.compareAndSet(false, true)) return;
        new Thread(() -> {
            JSONObject report = new JSONObject(); String stage = "start";
            AccountManager manager = null; OnAccountsUpdateListener listener = null; boolean listenerRegistered = false;
            try {
                manager = AccountManager.get(context);
                String type = FixtureAuthenticatorService.TYPE;
                stage = "directory";
                boolean own = false, google = false;
                for (AuthenticatorDescription description : manager.getAuthenticatorTypes()) {
                    own |= type.equals(description.type);
                    google |= "com.google".equals(description.type) && context.getPackageName().equals(description.packageName);
                }
                require(own, "Fixture authenticator missing"); require(!google, "Untrusted Google metadata was accepted");
                report.put("trustedDirectory", true);
                stage = "cache";
                for (Account existing : manager.getAccountsByType(type)) manager.removeAccountExplicitly(existing);
                require(manager.getAccountsByType(type).length == 0, "Fixture cleanup failed");
                CountDownLatch accountAdded = new CountDownLatch(1);
                listener = values -> {
                    for (Account value : values) if (type.equals(value.type) && "fixture-local".equals(value.name)) accountAdded.countDown();
                };
                manager.addOnAccountsUpdatedListener(listener, null, false, new String[]{type});
                listenerRegistered = true;
                Account account = new Account("fixture-local", type);
                Bundle extras = new Bundle(); extras.putString("fixture-key", "fixture-value");
                require(manager.addAccountExplicitly(account, "fixture-only-password", extras), "Account was not added");
                require(manager.getAccountsByType(type).length == 1, "Same manager retained stale account cache");
                require(accountAdded.await(10, TimeUnit.SECONDS), "Account update listener was not delivered");
                report.put("accountListener", true);
                require("fixture-value".equals(manager.getUserData(account, "fixture-key")), "Userdata mismatch");
                manager.setUserData(account, "fixture-key", "fixture-new");
                require("fixture-new".equals(manager.getUserData(account, "fixture-key")), "Userdata cache was stale");
                report.put("sameInstanceCache", true);
                stage = "permission";
                require(context.checkPermission("android.permission.ACCOUNT_MANAGER", Process.myPid(), Process.myUid())
                        == PackageManager.PERMISSION_DENIED, "Guest received ambient ACCOUNT_MANAGER");
                report.put("noAmbientPermission", true);
                stage = "token";
                String nonce = java.util.UUID.randomUUID().toString(), scope = "fixture-scope:" + nonce;
                Bundle tokenOptions = new Bundle(); tokenOptions.putString("fixture-token-nonce", nonce);
                Bundle token = manager.getAuthToken(account, scope, tokenOptions, false, null, null).getResult(20, TimeUnit.SECONDS);
                require(("fixture-token:" + scope).equals(token.getString(AccountManager.KEY_AUTHTOKEN)), "Authenticator response mismatch");
                File witness = new File(context.getFilesDir(), "account-authenticator-" + nonce + ".json");
                JSONObject actual = new JSONObject(new String(java.nio.file.Files.readAllBytes(witness.toPath()), StandardCharsets.UTF_8));
                require(nonce.equals(actual.getString("nonce")), "Authenticator receipt belongs to another request");
                int authenticatorPid = actual.getInt("pid");
                require(authenticatorPid > 0 && authenticatorPid != Process.myPid(), "Authenticator did not run in its separate process");
                java.nio.file.Files.delete(witness.toPath());
                report.put("authenticatorBinder", true);
                stage = "features";
                require(manager.hasFeatures(account, new String[]{"fixture-supported"}, null, null).getResult(20, TimeUnit.SECONDS), "Supported feature was rejected");
                require(!manager.hasFeatures(account, new String[]{"fixture-unsupported"}, null, null).getResult(20, TimeUnit.SECONDS), "Unsupported feature was accepted");
                Account[] selected = manager.getAccountsByTypeAndFeatures(type, new String[]{"fixture-supported"}, null, null).getResult(20, TimeUnit.SECONDS);
                require(selected.length == 1, "Feature-filtered account list mismatch");
                report.put("authenticatorFeatures", true);
                stage = "auth-error";
                try { manager.getAuthToken(account, "fixture-error", null, false, null, null).getResult(20, TimeUnit.SECONDS); throw new AssertionError("Authenticator error was swallowed"); }
                catch (AuthenticatorException | IllegalArgumentException expected) { report.put("authenticatorError", true); }
                stage = "caller-acl";
                try { manager.getPassword(new Account("unowned", "com.google")); throw new AssertionError("Foreign account password access passed"); }
                catch (SecurityException expected) { report.put("ownerAcl", true); }
                Field service = AccountManager.class.getDeclaredField("mService"); service.setAccessible(true);
                Object endpoint = service.get(manager);
                Method query = Class.forName("android.accounts.IAccountManager").getMethod("getAccountsAsUser", String.class, int.class, String.class);
                expectDenied(() -> query.invoke(endpoint, type, 1, context.getPackageName()));
                expectDenied(() -> query.invoke(endpoint, type, 0, "unrelated.package"));
                report.put("userAndPackageAcl", true);
                stage = "async-add";
                Bundle added = manager.addAccount(type, null, null, null, null, null, null).getResult(20, TimeUnit.SECONDS);
                require("fixture-async".equals(added.getString(AccountManager.KEY_ACCOUNT_NAME)), "Async account was not created");
                require(manager.getAccountsByType(type).length == 2, "Async account did not persist");
                report.put("asyncAdd", true);
                stage = "invalid-ui-intent";
                for (String invalid : new String[]{"subclass", "grant"}) {
                    Bundle rejected = new Bundle(); rejected.putBoolean("fixture-interactive", true);
                    rejected.putString("fixture-invalid-intent", invalid);
                    try {
                        manager.addAccount(type, null, null, rejected, null, null, null).getResult(20, TimeUnit.SECONDS);
                        throw new AssertionError("Invalid authenticator UI was returned: " + invalid);
                    } catch (AuthenticatorException expected) { }
                }
                report.put("invalidUiIntent", true);
                stage = "interactive-add";
                Bundle options = new Bundle(); options.putBoolean("fixture-interactive", true);
                Bundle interactive = manager.addAccount(type, null, null, options, (android.app.Activity) context, null, null).getResult(25, TimeUnit.SECONDS);
                require("fixture-ui".equals(interactive.getString(AccountManager.KEY_ACCOUNT_NAME)), "Interactive response did not return");
                require(manager.getAccountsByType(type).length == 3, "Interactive account did not persist");
                report.put("interactiveAdd", true);
                stage = "cleanup";
                for (Account existing : manager.getAccountsByType(type)) manager.removeAccountExplicitly(existing);
                require(manager.getAccountsByType(type).length == 0, "Removed accounts remained cached");
                report.put("removed", true);
            } catch (Throwable failure) {
                try { report.put("failure", failure.getClass().getSimpleName()); report.put("stage", stage); } catch (Exception ignored) { }
            } finally {
                if (listenerRegistered) {
                    try { manager.removeOnAccountsUpdatedListener(listener); }
                    catch (RuntimeException failure) {
                        try { report.put("failure", failure.getClass().getSimpleName()); report.put("stage", "listener-cleanup"); } catch (Exception ignored) { }
                    }
                }
            }
            try {
                File temporary = new File(context.getFilesDir(), "account-result.json.tmp");
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(report.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
                }
                if (!temporary.renameTo(new File(context.getFilesDir(), "account-result.json"))) throw new IllegalStateException("Account report publication failed");
            } catch (Exception failure) { throw new IllegalStateException("Account fixture report failed", failure); }
            finally { RUNNING.set(false); }
        }, "AccountFixture").start();
    }

    private interface Action { void run() throws Exception; }
    private static void expectDenied(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Virtual account identity check was bypassed"); }
        catch (InvocationTargetException expected) { if (!(expected.getCause() instanceof SecurityException)) throw expected; }
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private AccountProbe() { }
}
