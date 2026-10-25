package io.github.xmy953538104.mybox.engine;

import android.os.Bundle;
import android.os.IBinder;

/** Control messages carry publication state, never pretend to implement the guest Service interface. */
final class BoundServiceWire {
    static final String LIFECYCLE = "io.github.xmy953538104.mybox.engine.BoundLifecycle.v1";
    static final String EXTRA_OWNER = "mybox.bound.owner";
    static final String GROUP = "io.github.xmy953538104.mybox.engine.BoundGroup.v1";
    static final String LEASE = "io.github.xmy953538104.mybox.engine.BoundLease.v1";
    static final String CARRIER = "io.github.xmy953538104.mybox.engine.BoundCarrier.v1";
    static final String CALLBACK = "io.github.xmy953538104.mybox.engine.BoundCallback.v1";
    static final String EXTRA_GROUP = "mybox.bound.group";
    static final int CALL = IBinder.FIRST_CALL_TRANSACTION;
    static Bundle call(IBinder endpoint, String descriptor, Bundle request) {
        Bundle result = EngineProtocol.request(endpoint, descriptor, CALL, request);
        return checked(result);
    }
    static Bundle checked(Bundle result) {
        String failure = result.getString("failure_type");
        if (failure != null) {
            String message = result.getString("failure_message", "Bound Service operation failed");
            if (SecurityException.class.getName().equals(failure)) throw new SecurityException(message);
            if (IllegalArgumentException.class.getName().equals(failure)) throw new IllegalArgumentException(message);
            throw new EngineException(message);
        }
        return result;
    }
    static Bundle failure(Exception error) {
        Bundle result = EngineProtocol.response();
        result.putString("failure_type", error.getClass().getName());
        result.putString("failure_message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        return result;
    }
    static Bundle operation(String operation) {
        Bundle result = EngineProtocol.response(); result.putString("operation", operation); return result;
    }
    static Bundle owner(IBinder client, long generation, String packageGeneration, int pid) {
        Bundle result = EngineProtocol.response(); result.putBinder("client", client); result.putLong("generation", generation);
        result.putString("package_generation", packageGeneration); result.putInt("pid", pid); return result;
    }
    static boolean sameOwner(Bundle expected, Bundle actual) {
        if (expected == null || actual == null || expected.getInt("protocol") != EngineProtocol.VERSION
                || actual.getInt("protocol") != EngineProtocol.VERSION) return false;
        IBinder client = expected.getBinder("client"); String version = expected.getString("package_generation");
        return client != null && client.equals(actual.getBinder("client")) && expected.getLong("generation") > 0
                && expected.getLong("generation") == actual.getLong("generation") && version != null
                && version.equals(actual.getString("package_generation")) && expected.getInt("pid") > 0
                && expected.getInt("pid") == actual.getInt("pid");
    }
    private BoundServiceWire() { }
}
