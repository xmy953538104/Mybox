package io.github.xmy953538104.mybox.engine.accounts;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EncryptedAccountStorageTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void encryptedSnapshotReopensButWrongKeyAndTamperingDoNotResetData() throws Exception {
        File file = new File(temporary.getRoot(), "accounts.bin");
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        byte[] plain = "fixture-secret-password-token-not-real".getBytes(StandardCharsets.UTF_8);
        EncryptedAccountStorage storage = new EncryptedAccountStorage(file, key);
        storage.write(plain);
        byte[] encoded = Files.readAllBytes(file.toPath());
        assertFalse(new String(encoded, StandardCharsets.ISO_8859_1).contains("fixture-secret-password"));
        assertArrayEquals(plain, new EncryptedAccountStorage(file, key).read());
        try { new EncryptedAccountStorage(file, KeyGenerator.getInstance("AES").generateKey()).read(); fail(); }
        catch (IOException expected) { }
        assertArrayEquals(encoded, Files.readAllBytes(file.toPath()));
        encoded[encoded.length-1] ^= 1; Files.write(file.toPath(), encoded);
        try { storage.read(); fail(); } catch (IOException expected) { }
        assertArrayEquals(encoded, Files.readAllBytes(file.toPath()));
    }

    @Test public void repeatedWritesUseFreshAuthenticatedNonces() throws Exception {
        File file = new File(temporary.getRoot(), "accounts.bin");
        EncryptedAccountStorage storage = new EncryptedAccountStorage(file, KeyGenerator.getInstance("AES").generateKey());
        byte[] data = "test-only".getBytes(StandardCharsets.UTF_8);
        storage.write(data); byte[] before = Files.readAllBytes(file.toPath());
        storage.write(data); byte[] after = Files.readAllBytes(file.toPath());
        assertFalse(java.util.Arrays.equals(before, after));
        assertArrayEquals(data, storage.read());
    }
}
