package io.github.xmy953538104.mybox.engine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;

public class TrustedMicrogTest {
    private static final String CERT = "ea693cc674481ac7187b9a7a89d6a395483ba654d7c5c58c6f2b3a8dfc444c4c";
    private static TrustedMicrog trust() throws IOException {
        String pins = "gms.apk.sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\ngms.cert.sha256=" + CERT;
        return new TrustedMicrog(new ByteArrayInputStream(pins.getBytes(StandardCharsets.US_ASCII)));
    }
    @Test public void exactBytesAndSignerPass() throws Exception {
        trust().verify("com.google.android.gms", Collections.singletonList(CERT), new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)));
    }
    @Test(expected = SecurityException.class) public void publicDevelopmentSignerDoesNotTrustOtherBytes() throws Exception {
        trust().verify("com.google.android.gms", Collections.singletonList(CERT), new ByteArrayInputStream("other build".getBytes(StandardCharsets.US_ASCII)));
    }
    @Test(expected = SecurityException.class) public void exactBytesDoNotTrustAnotherSigner() throws Exception {
        trust().verify("com.google.android.gms", Collections.singletonList("another-cert"), new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)));
    }
    @Test(expected = SecurityException.class) public void wrongPackageFails() throws Exception {
        trust().verify("org.fixture.impostor", Collections.singletonList(CERT), new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)));
    }
    @Test(expected = IOException.class) public void absentPinsFailClosed() throws Exception {
        new TrustedMicrog(new ByteArrayInputStream(new byte[0]));
    }
}
