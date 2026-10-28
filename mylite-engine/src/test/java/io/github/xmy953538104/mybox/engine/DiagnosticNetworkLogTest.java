package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DiagnosticNetworkLogTest {
    @Test public void onlyKnownPlayRouteAndStatusSurviveSensitiveUrlsAndPayloads() {
        DiagnosticNetworkLog.Event error = DiagnosticNetworkLog.parse("E/Volley  ( 21483): [38] Unexpected response code 400 for https://play-fe.googleapis.com/fdfe/getItems?token=PrivateSecret");
        assertNotNull(error); assertEquals(400, error.status); assertEquals("getItems", error.route); assertEquals(21483, error.pid);
        DiagnosticNetworkLog.Event response = DiagnosticNetworkLog.parse("D/Volley(21483): HTTP response for request=<https://play-fe.googleapis.com/fdfe/delivery?account=Secret> [lifetime=123], [size=51], [rc=403], [retryCount=0] SecretBody");
        assertNotNull(response); assertEquals(403, response.status); assertEquals("delivery", response.route);
        assertNull(DiagnosticNetworkLog.parse("E/Other(21483): Unexpected response code 400 for https://play-fe.googleapis.com/fdfe/getItems"));
        assertNull(DiagnosticNetworkLog.parse("E/Volley(21483): Unexpected response code 400 for https://play-fe.googleapis.com.evil/fdfe/getItems"));
        assertNull(DiagnosticNetworkLog.parse("E/Volley(21483): Unexpected response code 400 for https://play-fe.googleapis.com/fdfe/getItemsSecret"));
        assertNull(DiagnosticNetworkLog.parse("E/Volley(21483): Unexpected response code 999 for https://play-fe.googleapis.com/fdfe/getItems"));
        assertNull(DiagnosticNetworkLog.parse(new String(new char[4097])));
    }
}
