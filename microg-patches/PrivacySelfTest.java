package org.microg.gms.common;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Keep auth recovery working while diagnostic stack traces omit HTTP bodies. */
public final class PrivacySelfTest {
    public static void main(String[] args) {
        String body = "Error=BadAuthentication\nAuth=secret-test-token\nCookie=secret-test-cookie";
        NotOkayException failure = new NotOkayException(403, body);
        assert body.equals(failure.getMessage()) : "Auth recovery lost its response";
        assert failure.toString().contains("403") : "HTTP status omitted";
        StringWriter output = new StringWriter();
        new IOException("Auth failed", failure).printStackTrace(new PrintWriter(output));
        assert !output.toString().contains("secret-test") : "Credential in nested stack trace";
        assert !new NotOkayException(body).toString().contains(body) : "Legacy constructor leaks";
        System.out.println("HTTP error privacy and auth recovery self-test passed");
    }
}
