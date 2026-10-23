package top.niunaijun.blackbox.core.system.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.test.InstrumentationTestCase;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import io.github.xmy953538104.mybox.MyboxApplication;
import top.niunaijun.blackbox.utils.compat.AccountManagerCompat;

/** Uses only synthetic accounts and an in-memory backend; never reads or writes user accounts. */
public final class AccountChangeTest extends InstrumentationTestCase {
    private static final String TYPE = "mybox.fixture.account";
    private static final String VISIBLE = "fixture.visible";
    private static final Account ACCOUNT = new Account("fixture-user", TYPE);

    public void testSameManagerObservesAddVisibilityAndRemoveWithoutLeaking() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ((MyboxApplication) context.getApplicationContext()).requireEngine();
        FixtureService service = new FixtureService();
        BUserAccounts accounts = accounts(101);
        Client visible = new Client(context, service, accounts, VISIBLE);
        Client hidden = new Client(context, service, accounts, "fixture.hidden");
        Client otherUser = new Client(context, service, accounts(102), VISIBLE);
        boolean disabled = AccountManagerCompat.disableSystemCaches(visible);
        assertEquals(Build.VERSION.SDK_INT >= 31, disabled);
        if (disabled) {
            assertCachesDisabled(visible.manager);
            assertCachesDisabled(new Client(context, service, accounts, "fixture.later").manager);
        }
        assertEquals(0, visible.manager.getAccounts().length);
        visible.manager.addOnAccountsUpdatedListener(visible.listener, null, true);
        hidden.manager.addOnAccountsUpdatedListener(hidden.listener, null, true);
        otherUser.manager.addOnAccountsUpdatedListener(otherUser.listener, null, true);
        try {
            assertEquals(Integer.valueOf(0), visible.updates.poll(5, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(0), hidden.updates.poll(5, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(0), otherUser.updates.poll(5, TimeUnit.SECONDS));
            Map<String, Integer> grants = new HashMap<>();
            grants.put(VISIBLE, AccountManager.VISIBILITY_VISIBLE);
            assertTrue(service.addAccountInternal(accounts, ACCOUNT, "fixture-only", null, grants));
            assertEquals(Integer.valueOf(1), visible.updates.poll(5, TimeUnit.SECONDS));
            assertEquals(1, visible.manager.getAccounts().length);
            assertEquals(0, hidden.manager.getAccounts().length);
            assertEquals(Collections.singletonList("101:" + VISIBLE), service.notifications);

            visible.userData = "before";
            assertEquals("before", visible.manager.getUserData(ACCOUNT, "fixture-metadata"));
            visible.userData = "after";
            assertEquals("after", visible.manager.getUserData(ACCOUNT, "fixture-metadata"));
            assertTrue(service.setAccountVisibility(ACCOUNT, VISIBLE, AccountManager.VISIBILITY_NOT_VISIBLE, accounts));
            assertEquals(Integer.valueOf(0), visible.updates.poll(5, TimeUnit.SECONDS));
            assertEquals(0, visible.manager.getAccounts().length);
            assertTrue(service.setAccountVisibility(ACCOUNT, VISIBLE, AccountManager.VISIBILITY_VISIBLE, accounts));
            assertEquals(Integer.valueOf(1), visible.updates.poll(5, TimeUnit.SECONDS));
            assertEquals(1, visible.manager.getAccounts().length);
            assertTrue(service.removeAccountInternal(accounts, ACCOUNT));
            assertEquals(Integer.valueOf(0), visible.updates.poll(5, TimeUnit.SECONDS));
            assertEquals(0, visible.manager.getAccounts().length);
            assertEquals(Arrays.asList("101:" + VISIBLE, "101:" + VISIBLE,
                    "101:" + VISIBLE, "101:" + VISIBLE), service.notifications);
            assertTrue("Hidden caller received an account change", hidden.updates.isEmpty());
            assertTrue("Other user received an account change", otherUser.updates.isEmpty());
        } finally {
            visible.manager.removeOnAccountsUpdatedListener(visible.listener);
            hidden.manager.removeOnAccountsUpdatedListener(hidden.listener);
            otherUser.manager.removeOnAccountsUpdatedListener(otherUser.listener);
        }
        service.notifications.clear();
        assertTrue(service.addAccountInternal(accounts, ACCOUNT, null, null,
                Collections.singletonMap(VISIBLE, AccountManager.VISIBILITY_VISIBLE)));
        assertTrue("Unregistered listener was notified", service.notifications.isEmpty());
    }

    public void testListenerTypesReferenceCountsAndCallerValidation() throws Exception {
        ((MyboxApplication) getInstrumentation().getTargetContext().getApplicationContext()).requireEngine();
        FixtureService service = new FixtureService();
        BUserAccounts accounts = accounts(101);
        service.updateAccountListener(new String[]{TYPE}, VISIBLE, 101, true);
        service.updateAccountListener(new String[]{TYPE}, VISIBLE, 101, true);
        service.updateAccountListener(new String[]{TYPE}, VISIBLE, 101, false);
        service.updateAccountListener(new String[]{"unrelated.type"}, "fixture.unrelated", 101, true);
        service.updateAccountListener(null, VISIBLE, 102, true);
        Map<String, Integer> grants = new HashMap<>();
        grants.put(VISIBLE, AccountManager.VISIBILITY_VISIBLE);
        grants.put("fixture.unrelated", AccountManager.VISIBILITY_VISIBLE);
        assertTrue(service.addAccountInternal(accounts, ACCOUNT, null, null, grants));
        assertEquals(Collections.singletonList("101:" + VISIBLE), service.notifications);
        service.updateAccountListener(new String[]{TYPE}, VISIBLE, 101, false);
        service.notifications.clear();
        assertTrue(service.removeAccountInternal(accounts, ACCOUNT));
        assertTrue(service.notifications.isEmpty());
        try {
            service.updateAccountListener(new String[]{TYPE}, VISIBLE, 101, false);
            fail("Unbalanced unregister must fail");
        } catch (IllegalArgumentException expected) { }
        try {
            service.registerAccountListener(null, VISIBLE, 101);
            fail("Host is not a registered guest caller");
        } catch (SecurityException expected) { }
        try {
            AccountAccessPolicy.enforceClaimedPackage(VISIBLE, "fixture.other");
            fail("A caller cannot register for another package");
        } catch (SecurityException expected) { }
    }

    private static BUserAccounts accounts(int userId) {
        BUserAccounts accounts = new BUserAccounts();
        accounts.userId = userId;
        return accounts;
    }

    private static void assertCachesDisabled(AccountManager manager) throws Exception {
        for (String name : new String[]{"mAccountsForUserCache", "mUserDataCache"}) {
            Field field = AccountManager.class.getDeclaredField(name);
            field.setAccessible(true);
            Object cache = field.get(manager);
            Method disabled;
            try { disabled = cache.getClass().getMethod("isDisabled"); }
            catch (NoSuchMethodException android12) { disabled = cache.getClass().getMethod("isDisabledLocal"); }
            assertEquals("Required platform cache was not disabled: " + name, Boolean.TRUE,
                    disabled.invoke(cache));
        }
    }

    private static final class FixtureService extends BAccountManagerService {
        final List<Client> clients = new ArrayList<>();
        final List<String> notifications = new ArrayList<>();
        @Override void saveAllAccounts() { /* Fixture state must never reach the real account file. */ }
        @Override void sendAccountBroadcast(Intent intent, int userId) {
            assertNull("Account notifications must not carry identity or credentials", intent.getExtras());
            assertTrue((intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) != 0);
            notifications.add(userId + ":" + intent.getPackage());
            for (Client client : clients) {
                if (client.accounts.userId == userId && client.packageName.equals(intent.getPackage())
                        && client.receiver != null && client.filter.hasAction(intent.getAction())) {
                    client.receiver.onReceive(client, intent);
                }
            }
        }
    }

    private static final class Client extends ContextWrapper {
        final FixtureService service;
        final BUserAccounts accounts;
        final String packageName;
        final AccountManager manager;
        final LinkedBlockingQueue<Integer> updates = new LinkedBlockingQueue<>();
        final OnAccountsUpdateListener listener = accounts -> updates.add(accounts.length);
        BroadcastReceiver receiver;
        IntentFilter filter;
        String userData;

        Client(Context context, FixtureService service, BUserAccounts accounts, String packageName) throws Exception {
            super(context);
            this.service = service;
            this.accounts = accounts;
            this.packageName = packageName;
            Class<?> api = Class.forName("android.accounts.IAccountManager");
            Object backend = Proxy.newProxyInstance(api.getClassLoader(), new Class[]{api}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getAccountsAsUser":
                        return service.getAccountsFromCache(accounts, null, packageName, false);
                    case "getUserData": return userData;
                    case "registerAccountListener":
                    case "unregisterAccountListener":
                        service.updateAccountListener((String[]) args[0], packageName, accounts.userId,
                                "registerAccountListener".equals(method.getName()));
                        return null;
                    default: throw new AssertionError("Unexpected fixture API: " + method.getName());
                }
            });
            manager = AccountManager.class.getConstructor(Context.class, api).newInstance(this, backend);
            service.clients.add(this);
        }

        @Override public String getPackageName() { return packageName; }
        public String getOpPackageName() { return packageName; }
        public int getUserId() { return accounts.userId; }
        @Override public Object getSystemService(String name) {
            return Context.ACCOUNT_SERVICE.equals(name) ? manager : super.getSystemService(name);
        }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            this.receiver = receiver;
            this.filter = filter;
            return null;
        }
        @Override public void unregisterReceiver(BroadcastReceiver receiver) { this.receiver = null; }
    }
}
