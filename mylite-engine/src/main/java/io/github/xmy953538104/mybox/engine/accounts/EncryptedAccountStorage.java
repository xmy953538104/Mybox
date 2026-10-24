package io.github.xmy953538104.mybox.engine.accounts;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Atomic authenticated snapshots, encrypted by an app-scoped Android Keystore key in production. */
public final class EncryptedAccountStorage implements AccountStorage {
    private static final int MAGIC = 0x4d424131;
    // Keep the original key identity so a Mylite rename cannot strand encrypted accounts.
    private static final String ALIAS = "mybox.vlite.accounts.v1";
    private final File file;
    private final SecretKey key;

    public EncryptedAccountStorage(Context context) throws IOException {
        this(new File(context.getFilesDir(), "vlite/accounts/user0.bin"), androidKey());
    }

    EncryptedAccountStorage(File file, SecretKey key) {
        this.file = file; this.key = key;
    }

    @Override public synchronized byte[] read() throws IOException {
        if (!file.exists()) return null;
        if (file.length() > 33L * 1024 * 1024) throw new IOException("Encrypted account store exceeds limit");
        byte[] encoded = Files.readAllBytes(file.toPath());
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IOException("Unsupported encrypted account store");
            int ivLength = input.readInt();
            if (ivLength != 12) throw new IOException("Invalid account store nonce");
            byte[] iv = new byte[ivLength]; input.readFully(iv);
            byte[] ciphertext = new byte[input.available()]; input.readFully(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(new byte[]{'M','B','A','1'});
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException failure) {
            throw new IOException("Account store authentication failed; existing data was retained", failure);
        }
    }

    @Override public synchronized void write(byte[] snapshot) throws IOException {
        if (snapshot.length > 32 * 1024 * 1024) throw new IOException("Account snapshot exceeds limit");
        byte[] encoded;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            cipher.updateAAD(new byte[]{'M','B','A','1'});
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC); output.writeInt(cipher.getIV().length); output.write(cipher.getIV());
                output.write(cipher.doFinal(snapshot));
            }
            encoded = bytes.toByteArray();
        } catch (GeneralSecurityException failure) { throw new IOException("Cannot encrypt account store", failure); }
        File directory = file.getParentFile();
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Cannot create account store directory");
        File temporary = new File(directory, "accounts-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(encoded); output.getFD().sync();
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary.toPath()); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static SecretKey androidKey() throws IOException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            return generator.generateKey();
        } catch (GeneralSecurityException failure) { throw new IOException("Account encryption key is unavailable", failure); }
    }
}
