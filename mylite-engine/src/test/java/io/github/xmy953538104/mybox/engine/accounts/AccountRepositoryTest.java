package io.github.xmy953538104.mybox.engine.accounts;

import static org.junit.Assert.*;

import io.github.xmy953538104.mybox.engine.CallerIdentity;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class AccountRepositoryTest {
    private static final String OWNER = "org.fixture.auth", APP = "org.fixture.consumer", OTHER = "org.fixture.other";
    private final Map<String,List<String>> certificates = new HashMap<>();
    private final Map<String,List<String>> compatibilityDefaults = new HashMap<>();
    private final List<Set<String>> notifications = new ArrayList<>();
    private final MemoryStorage storage = new MemoryStorage();
    private AuthenticatorRegistration authenticator;
    private AccountRepository repository;
    private CallerIdentity owner, consumer, other;
    private final AccountKey key = new AccountKey("synthetic-user", "org.fixture.account");
    private final AccountRepository.Directory directory = new AccountRepository.Directory() {
        @Override public AuthenticatorRegistration authenticator(String type) {
            return authenticator.accountType.equals(type) ? authenticator : null;
        }
        @Override public List<String> certificates(String packageName) { return certificates.get(packageName); }
        @Override public boolean usesLegacyVisibleDefault(AuthenticatorRegistration owner, String target, List<String> real) {
            return real.equals(compatibilityDefaults.get(target));
        }
    };

    @Before public void setUp() throws Exception {
        certificates.put(OWNER, Collections.singletonList("auth-cert"));
        certificates.put(APP, Collections.singletonList("app-cert"));
        certificates.put(OTHER, Collections.singletonList("other-cert"));
        authenticator = registration("auth-cert", true);
        owner = caller(OWNER, "auth-cert"); consumer = caller(APP, "app-cert"); other = caller(OTHER, "other-cert");
        repository = reopen();
    }

    private AccountRepository reopen() throws IOException {
        return new AccountRepository(storage, directory, (user, packages) -> {
            assertEquals(0, user); notifications.add(new HashSet<>(packages));
        });
    }

    @Test public void approvedDefaultEnumeratesWithoutCredentialAuthorityAndRespectsExplicitDenials() throws Exception {
        repository.add(owner, key, "password", Collections.singletonMap("field", "private-data"), null);
        repository.setToken(owner, key, "oauth", "private-token");
        assertTrue(repository.accounts(consumer, null).isEmpty());
        byte[] before = storage.bytes.clone();
        compatibilityDefaults.put(APP, Collections.singletonList("app-cert"));
        assertEquals(AccountRepository.USER_VISIBLE, repository.getVisibility(owner, key, APP));
        assertEquals(Collections.singletonList(key), repository.accounts(consumer, null));
        assertTrue(repository.accounts(other, null).isEmpty());
        denied(() -> repository.password(consumer, key));
        denied(() -> repository.userData(consumer, key, "field"));
        denied(() -> repository.peekToken(consumer, key, "oauth"));
        denied(() -> repository.grantedToken(consumer, key, "oauth"));
        denied(() -> repository.setPassword(consumer, key, "replace"));
        denied(() -> repository.grantToken(consumer, key, "oauth", APP, true));
        assertArrayEquals("A default visibility query persisted grants or account data", before, storage.bytes);
        for (int value : new int[]{AccountRepository.HIDDEN, AccountRepository.USER_HIDDEN}) {
            repository.setVisibility(owner, key, APP, value);
            assertEquals(value, repository.getVisibility(owner, key, APP));
            assertTrue(repository.accounts(consumer, null).isEmpty());
        }
        repository.setVisibility(owner, key, APP, 0);
        repository.setVisibility(owner, key, AccountRepository.LEGACY_VISIBLE, AccountRepository.USER_HIDDEN);
        assertTrue(repository.accounts(consumer, null).isEmpty());
        repository.setVisibility(owner, key, AccountRepository.LEGACY_VISIBLE, AccountRepository.USER_VISIBLE);
        repository = reopen();
        assertEquals(Collections.singletonList(key), repository.accounts(consumer, null));
        compatibilityDefaults.clear();
        assertTrue(repository.accounts(consumer, null).isEmpty());
        assertEquals("password", repository.password(owner, key));
        assertEquals("private-token", repository.peekToken(owner, key, "oauth"));
    }

    @Test public void compatibilityDefaultRejectsReplacedConsumerAndRetiredAuthenticator() throws Exception {
        repository.add(owner, key, "password", null, null);
        compatibilityDefaults.put(APP, Collections.singletonList("app-cert"));
        assertEquals(Collections.singletonList(key), repository.accounts(consumer, null));
        certificates.put(APP, Collections.singletonList("replacement-cert"));
        denied(() -> repository.accounts(consumer, null));
        assertTrue(repository.accounts(caller(APP, "replacement-cert"), null).isEmpty());
        certificates.put(APP, Collections.singletonList("app-cert"));
        certificates.put(OWNER, Collections.singletonList("new-auth-cert"));
        authenticator = registration("new-auth-cert", true);
        assertEquals(AccountRepository.HIDDEN, repository.getVisibility(consumer, key, APP));
        assertTrue(repository.accounts(consumer, null).isEmpty());
        denied(() -> repository.password(owner, key));
    }

    @Test public void visibilityChangesNotifyOnlyMatchingVisibleListenersAndQueriesStayFresh() throws Exception {
        repository.registerListener(consumer, null);
        repository.registerListener(other, new String[]{key.type});
        assertTrue(repository.accounts(consumer, null).isEmpty());
        repository.add(owner, key, "password", null, null);
        assertTrue(notifications.isEmpty());
        repository.setVisibility(owner, key, APP, AccountRepository.VISIBLE);
        assertEquals(Collections.singletonList(key), repository.accounts(consumer, null));
        assertTrue(repository.accounts(other, null).isEmpty());
        assertEquals(Collections.singleton(APP), notifications.get(0));
        repository.setVisibility(owner, key, APP, AccountRepository.HIDDEN);
        assertTrue(repository.accounts(consumer, null).isEmpty());
        assertEquals(Collections.singleton(APP), notifications.get(1));
        repository.remove(owner, key);
        assertEquals("Deleting an already hidden account does not notify hidden consumers", 2, notifications.size());
        try { AccountRepository.requireUser(10); fail(); } catch (SecurityException expected) { }
    }

    @Test public void currentConsumerDoesNotGainHiddenAccountAccess() throws Exception {
        repository.add(owner, key, "fixture-password", Collections.singletonMap("field", "fixture-data"), null);
        repository.setToken(owner, key, "oauth", "fixture-token");
        repository.requireCurrent(consumer);
        assertTrue(repository.accounts(consumer, key.type).isEmpty());
        denied(() -> repository.requireVisible(consumer, key));
        denied(() -> repository.grantedToken(consumer, key, "oauth"));
        denied(() -> repository.password(consumer, key));
        denied(() -> repository.userData(consumer, key, "field"));
        denied(() -> repository.peekToken(consumer, key, "oauth"));
        certificates.put(APP, Collections.singletonList("replacement-cert"));
        denied(() -> repository.requireCurrent(consumer));
    }

    @Test public void authenticatorProjectsManagedHiddenAccountsWithoutGrantingTheTargetAccess() throws Exception {
        repository.add(owner, key, "fixture-password", null, null);
        repository.setToken(owner, key, "oauth", "fixture-token");
        assertEquals(AccountRepository.USER_VISIBLE, repository.getVisibility(owner, key, AccountRepository.LEGACY_VISIBLE));
        assertEquals(AccountRepository.USER_HIDDEN, repository.getVisibility(owner, key, AccountRepository.LEGACY_HIDDEN));
        assertEquals(AccountRepository.USER_HIDDEN, repository.getVisibility(owner, key, APP));
        byte[] stored = storage.bytes.clone();
        assertEquals(Collections.singletonList(key), repository.accountsByTypeForPackage(owner, key.type, APP));
        assertArrayEquals("Projection changed account state or grants", stored, storage.bytes);
        assertTrue(repository.accounts(consumer, key.type).isEmpty());
        assertTrue(repository.accountsByTypeForPackage(consumer, key.type, APP).isEmpty());
        assertTrue(repository.accountsByTypeForPackage(owner, null, APP).isEmpty());
        denied(() -> repository.requireVisible(consumer, key));
        denied(() -> repository.grantedToken(consumer, key, "oauth"));
        denied(() -> repository.password(consumer, key));
        repository.setVisibility(owner, key, APP, AccountRepository.HIDDEN);
        assertTrue(repository.accountsByTypeForPackage(owner, key.type, APP).isEmpty());
        repository.setVisibility(owner, key, APP, AccountRepository.USER_HIDDEN);
        assertEquals(Collections.singletonList(key), repository.accountsByTypeForPackage(owner, key.type, APP));
        repository.setVisibility(owner, key, APP, 0);
        repository = reopen();
        assertEquals(Collections.singletonList(key), repository.accountsByTypeForPackage(owner, key.type, APP));
        assertTrue(repository.accounts(consumer, null).isEmpty());
        assertTrue(repository.accountsByTypeForPackage(owner, key.type, "missing.package").isEmpty());
    }

    @Test public void ownerProjectionRejectsRetiredCallersAndOldAuthenticatorRecords() throws Exception {
        repository.add(owner, key, null, null, null);
        certificates.put(APP, Collections.singletonList("auth-cert"));
        CallerIdentity sameSignerPeer = caller(APP, "auth-cert");
        repository.setVisibility(owner, key, APP, AccountRepository.VISIBLE);
        assertEquals(Collections.singletonList(key), repository.accounts(sameSignerPeer, key.type));
        assertTrue(repository.accountsByTypeForPackage(sameSignerPeer, key.type, APP).isEmpty());
        denied(() -> repository.password(sameSignerPeer, key));
        certificates.put(APP, Collections.singletonList("replacement-consumer-cert"));
        denied(() -> repository.accountsByTypeForPackage(consumer, key.type, APP));
        CallerIdentity replacementConsumer = caller(APP, "replacement-consumer-cert");
        assertTrue(repository.accounts(replacementConsumer, null).isEmpty());
        assertEquals(Collections.singletonList(key), repository.accountsByTypeForPackage(owner, key.type, APP));
        certificates.put(OWNER, Collections.singletonList("replacement-auth-cert"));
        authenticator = registration("replacement-auth-cert", true);
        denied(() -> repository.accountsByTypeForPackage(owner, key.type, APP));
        CallerIdentity replacementOwner = caller(OWNER, "replacement-auth-cert");
        assertTrue(repository.accountsByTypeForPackage(replacementOwner, key.type, APP).isEmpty());
    }

    @Test public void visibilityIsNotPasswordUserDataOrRawTokenAuthority() throws Exception {
        repository.add(owner, key, "secret-password", Collections.singletonMap("field", "secret-data"), null);
        repository.setToken(owner, key, "oauth", "secret-token");
        repository.setVisibility(owner, key, APP, AccountRepository.VISIBLE);
        assertEquals("secret-password", repository.password(owner, key));
        assertEquals("secret-data", repository.userData(owner, key, "field"));
        assertEquals("secret-token", repository.peekToken(owner, key, "oauth"));
        denied(() -> repository.password(consumer, key));
        denied(() -> repository.userData(consumer, key, "field"));
        denied(() -> repository.peekToken(consumer, key, "oauth"));
        denied(() -> repository.setPassword(consumer, key, "overwrite"));
        denied(() -> repository.setToken(consumer, key, "oauth", "overwrite"));
        denied(() -> repository.remove(consumer, key));
        CallerIdentity forgedCertificate = caller(OWNER, "attacker-cert");
        denied(() -> repository.password(forgedCertificate, key));
        denied(() -> repository.add(consumer, new AccountKey("another", key.type), "x", null, null));
    }

    @Test public void ordinaryTokenAccessNeedsSeparateGrantAndPasswordChangeInvalidatesTokens() throws Exception {
        authenticator = registration("auth-cert", false);
        repository.add(owner, key, "password", null, Collections.singletonMap(APP, AccountRepository.VISIBLE));
        repository.setToken(owner, key, "oauth", "auth-token");
        denied(() -> repository.grantedToken(consumer, key, "oauth"));
        repository.grantToken(owner, key, "oauth", APP, true);
        assertEquals("auth-token", repository.grantedToken(consumer, key, "oauth"));
        repository.grantToken(owner, key, "oauth", APP, false);
        denied(() -> repository.grantedToken(consumer, key, "oauth"));
        repository.grantToken(owner, key, "oauth", APP, true);
        repository.setPassword(owner, key, "new-password");
        assertNull(repository.grantedToken(consumer, key, "oauth"));
    }

    @Test public void perClientTokensAreCertificateScopedAndExpireWithoutCrossClientInvalidation() throws Exception {
        repository.add(owner, key, null, null, Collections.singletonMap(AccountRepository.LEGACY_HIDDEN, AccountRepository.USER_VISIBLE));
        assertEquals(AccountRepository.USER_VISIBLE, repository.getVisibility(owner, key, AccountRepository.LEGACY_HIDDEN));
        repository.issuedToken(owner, consumer, key, "oauth", "same-string", 200);
        repository.issuedToken(owner, other, key, "oauth", "same-string", 200);
        assertEquals("same-string", repository.cachedIssuedToken(consumer, key, "oauth", 100));
        assertNull(repository.cachedIssuedToken(consumer, key, "oauth", 200));
        repository.invalidateToken(consumer, key.type, "same-string");
        assertNull(repository.cachedIssuedToken(consumer, key, "oauth", 100));
        assertEquals("same-string", repository.cachedIssuedToken(other, key, "oauth", 100));
        certificates.put(OTHER, Collections.singletonList("replacement-cert"));
        CallerIdentity replacement = caller(OTHER, "replacement-cert");
        assertNull(repository.cachedIssuedToken(replacement, key, "oauth", 100));
        denied(() -> repository.cachedIssuedToken(other, key, "oauth", 100));
        denied(() -> repository.issuedToken(consumer, other, key, "oauth", "fake", 300));
    }

    @Test public void authenticatorCertificateReplacementCannotReadOldOwnerSecrets() throws Exception {
        repository.add(owner, key, "retained-secret", null, Collections.singletonMap(APP, AccountRepository.VISIBLE));
        certificates.put(OWNER, Collections.singletonList("new-auth-cert"));
        authenticator = registration("new-auth-cert", true);
        CallerIdentity replacement = caller(OWNER, "new-auth-cert");
        denied(() -> repository.password(replacement, key));
        denied(() -> repository.password(owner, key));
        assertTrue(repository.accounts(consumer, null).isEmpty());
        assertTrue(repository.accounts(replacement, null).isEmpty());
    }

    @Test public void failedPersistenceDoesNotChangeLiveStateOrNotifyAndSuccessfulDataReopens() throws Exception {
        repository.registerListener(consumer, null);
        storage.fail = true;
        try {
            repository.add(owner, key, "test-password", null, Collections.singletonMap(APP, AccountRepository.VISIBLE));
            fail();
        } catch (IOException expected) { }
        assertTrue(repository.accounts(owner, null).isEmpty());
        assertTrue(notifications.isEmpty());
        storage.fail = false;
        repository.add(owner, key, "test-password", null, Collections.singletonMap(APP, AccountRepository.VISIBLE));
        repository.setToken(owner, key, "oauth", "test-token");
        AccountRepository opened = reopen();
        assertEquals("test-password", opened.password(owner, key));
        assertEquals("test-token", opened.peekToken(owner, key, "oauth"));
        assertEquals(Collections.singletonList(key), opened.accounts(consumer, null));
        assertEquals(1, notifications.size());
    }

    @Test public void listenerUnregisterReferenceCountsAndTypeFilteringAreEnforced() throws Exception {
        repository.registerListener(consumer, new String[]{key.type});
        repository.registerListener(consumer, new String[]{key.type});
        repository.registerListener(other, new String[]{"unrelated.type"});
        repository.unregisterListener(consumer, new String[]{key.type});
        repository.add(owner, key, null, null, Collections.singletonMap(AccountRepository.LEGACY_HIDDEN, AccountRepository.VISIBLE));
        assertEquals(Collections.singleton(APP), notifications.get(0));
        repository.unregisterListener(consumer, new String[]{key.type});
        repository.rename(owner, key, "renamed-synthetic-user");
        assertEquals(1, notifications.size());
        try { repository.unregisterListener(consumer, new String[]{key.type}); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void invalidPersistenceIsRejectedWithoutErasingSnapshot() throws Exception {
        storage.bytes = new byte[]{0,0,0,1,0x7f,0x7f,0x7f,0x7f};
        byte[] before = storage.bytes.clone();
        try { reopen(); fail(); } catch (IOException expected) { }
        assertArrayEquals(before, storage.bytes);
    }

    private AuthenticatorRegistration registration(String certificate, boolean custom) {
        return new AuthenticatorRegistration(key.type, OWNER, OWNER + ".Authenticator",
                Collections.singletonList(certificate), custom, 0,0,0,0);
    }

    private static CallerIdentity caller(String name, String certificate) throws Exception {
        // Only the engine server constructs production identities; this is a JVM server fixture.
        Constructor<CallerIdentity> constructor = CallerIdentity.class.getDeclaredConstructor(String.class, int.class, int.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name, 10001, 101, Collections.singletonList(certificate));
    }

    private interface Action { void run() throws Exception; }
    private static void denied(Action action) throws Exception {
        try { action.run(); fail("Untrusted operation was accepted"); } catch (SecurityException expected) { }
    }
    private static final class MemoryStorage implements AccountStorage {
        byte[] bytes; boolean fail;
        @Override public byte[] read() { return bytes == null ? null : bytes.clone(); }
        @Override public void write(byte[] value) throws IOException {
            if (fail) throw new IOException("Fixture write failure"); bytes = value.clone();
        }
    }
}
