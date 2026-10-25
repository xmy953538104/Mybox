package io.github.xmy953538104.mybox.engine;

import android.system.Os;
import android.test.InstrumentationTestCase;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;

public final class SealedTrustRuntimeTest extends InstrumentationTestCase {
    private static final String CERT = "ea693cc674481ac7187b9a7a89d6a395483ba654d7c5c58c6f2b3a8dfc444c4c";
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static TrustedMicrog trust(String hash) throws Exception {
        return new TrustedMicrog(new ByteArrayInputStream(("gms.apk.sha256=" + hash + "\ngms.cert.sha256=" + CERT).getBytes(StandardCharsets.US_ASCII)));
    }
    public void testCacheCannotHideSameLengthTamperingReplacementOrChangedPins() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 27) return;
        File file = File.createTempFile("sealed-trust-", ".apk", getInstrumentation().getTargetContext().getCacheDir());
        try {
            Files.write(file.toPath(), "abc".getBytes(StandardCharsets.US_ASCII)); Os.chmod(file.getPath(), 0444);
            Thread.sleep(2100);
            TrustedMicrog trust = trust(HASH);
            assertFalse(trust.verifySealedFile("com.google.android.gms", Collections.singletonList(CERT), file));
            assertTrue(trust(HASH).verifySealedFile("com.google.android.gms", Collections.singletonList(CERT), file));
            try { trust.verifySealedFile("com.google.android.gms", Collections.singletonList("other"), file); fail("Cached bytes bypassed signer validation"); }
            catch (SecurityException expected) { }
            try { trust(HASH.replace('a', 'b')).verifySealedFile("com.google.android.gms", Collections.singletonList(CERT), file); fail("Changed host pins reused a cached approval"); }
            catch (SecurityException expected) { }
            Os.chmod(file.getPath(), 0600);
            try { trust.verifySealedFile("com.google.android.gms", Collections.singletonList(CERT), file); fail("Writable APK reused cached approval"); }
            catch (SecurityException expected) { }
            java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(file.toPath());
            Files.write(file.toPath(), "abd".getBytes(StandardCharsets.US_ASCII));
            Files.setLastModifiedTime(file.toPath(), modified); Os.chmod(file.getPath(), 0444);
            assertEquals(3, file.length());
            try { trust.verifySealedFile("com.google.android.gms", Collections.singletonList(CERT), file); fail("Same-size modified bytes reused a cached approval"); }
            catch (SecurityException expected) { }
            File replacement = File.createTempFile("sealed-replace-", ".apk", file.getParentFile());
            try {
                Files.write(replacement.toPath(), "abc".getBytes(StandardCharsets.US_ASCII)); Os.chmod(replacement.getPath(), 0444);
                Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(replacement.toPath()); }
            Thread.sleep(2100);
            assertFalse("Replacement inode must be fully checked", trust.verifySealedFile("com.google.android.gms", Collections.singletonList(CERT), file));
            assertTrue(trust.verifySealedFile("com.google.android.gms", Collections.singletonList(CERT), file));
        } finally { Files.deleteIfExists(file.toPath()); }
    }
    public void testConcurrentColdReadersPerformOneFullVerification() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 27) return;
        File file = File.createTempFile("sealed-concurrent-", ".apk", getInstrumentation().getTargetContext().getCacheDir());
        try {
            Files.write(file.toPath(), "abc".getBytes(StandardCharsets.US_ASCII)); Os.chmod(file.getPath(), 0444);
            Thread.sleep(2100);
            TrustedMicrog trust = trust(HASH); java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger full = new java.util.concurrent.atomic.AtomicInteger(), hits = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Runnable verify = () -> { try { go.await(); if (trust.verifySealedFile("com.google.android.gms", Collections.singletonList(CERT), file)) hits.incrementAndGet(); else full.incrementAndGet(); } catch (Throwable error) { failure.set(error); } };
            Thread first = new Thread(verify), second = new Thread(verify); first.start(); second.start(); go.countDown(); first.join(5000); second.join(5000);
            assertFalse(first.isAlive()); assertFalse(second.isAlive()); assertNull(failure.get()); assertEquals(1, full.get()); assertEquals(1, hits.get());
        } finally { Files.deleteIfExists(file.toPath()); }
    }
}
