package io.github.xmy953538104.mybox.engine;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.test.InstrumentationTestCase;

public final class GuestDiagnosticBoundaryTest extends InstrumentationTestCase {
    public void testOnewayCaptureRequiresTheExactReservationCapability() {
        IBinder token = new Binder(); Bundle ticket = new Bundle(); ticket.putBinder("diagnostic_token", token);
        GuestRuntime.requireDiagnosticTicket(token, ticket, IBinder.FLAG_ONEWAY);
        try { GuestRuntime.requireDiagnosticTicket(new Binder(), ticket, IBinder.FLAG_ONEWAY); fail("Another reservation's ticket was accepted"); }
        catch (SecurityException expected) { }
        try { GuestRuntime.requireDiagnosticTicket(token, new Bundle(), IBinder.FLAG_ONEWAY); fail("Missing ticket was accepted"); }
        catch (SecurityException expected) { }
        try { GuestRuntime.requireDiagnosticTicket(null, ticket, IBinder.FLAG_ONEWAY); fail("Unprepared guest admitted diagnostics"); }
        catch (SecurityException expected) { }
        try { GuestRuntime.requireDiagnosticTicket(token, ticket, 0); fail("Blocking diagnostic IPC was accepted"); }
        catch (SecurityException expected) { }
    }
}
