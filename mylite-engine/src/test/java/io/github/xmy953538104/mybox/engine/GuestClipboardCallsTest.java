package io.github.xmy953538104.mybox.engine;

import android.content.ClipDescription;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** JVM boundary tests; real Android clipboard/focus evidence belongs to ClipboardRuntimeTest. */
public final class GuestClipboardCallsTest {
    public static final class Original {
        int calls;
        Object[] arguments;
        RuntimeException failure;
        private ClipDescription record(Object... args) {
            calls++; arguments = args;
            if (failure != null) throw failure;
            return null;
        }
        public ClipDescription getPrimaryClipDescription(String pkg) { return record(pkg); }
        public ClipDescription getPrimaryClipDescription(String pkg, int user) { return record(pkg, user); }
        public ClipDescription getPrimaryClipDescription(String pkg, String tag, int user, int device) { return record(pkg, tag, user, device); }
        public ClipDescription getPrimaryClipDescription(String pkg, String tag, int user) { return record(pkg, tag, user); }
        public void addPrimaryClipChangedListener(Object listener, String pkg, String tag, int user, int device) {
            record(listener, pkg, tag, user, device);
        }
        public boolean hasPrimaryClip(String pkg, String tag, int user, int device) { record(pkg, tag, user, device); return true; }
    }

    private static final String GUEST = "fixture.guest";
    private static Method metadata(Class<?>... types) throws Exception { return Original.class.getMethod("getPrimaryClipDescription", types); }
    private static GuestClipboardCalls adapter(Original original, int sdk, AtomicBoolean focus) {
        return new GuestClipboardCalls(original, GUEST, "physical.host", "physical-attribution", 12, 7,
                sdk, focus::get, () -> { });
    }

    @Test public void verifiedAndroidSignaturesMapOnlyAuthorizedIdentity() throws Throwable {
        for (int sdk = 26; sdk <= 35; sdk++) {
            Original original = new Original();
            GuestClipboardCalls calls = adapter(original, sdk, new AtomicBoolean(true));
            Method method;
            Object[] input;
            Object[] expected;
            if (sdk <= 28) {
                method = metadata(String.class); input = new Object[]{GUEST}; expected = new Object[]{"physical.host"};
            } else if (sdk <= 33) {
                method = metadata(String.class, int.class); input = new Object[]{GUEST, 0}; expected = new Object[]{"physical.host", 12};
            } else {
                method = metadata(String.class, String.class, int.class, int.class);
                input = new Object[]{GUEST, null, 0, 7}; expected = new Object[]{"physical.host", "physical-attribution", 12, 7};
            }
            Object[] unchanged = input.clone();
            calls.invoke(null, method, input);
            assertEquals(1, original.calls); assertArrayEquals(expected, original.arguments); assertArrayEquals(unchanged, input);
        }
    }

    @Test public void unfocusedOrUnknownActivityCannotReadEvenWhenHostHasFocus() throws Throwable {
        Original original = new Original();
        AtomicBoolean authenticatedFocus = new AtomicBoolean(false);
        GuestClipboardCalls calls = adapter(original, 34, authenticatedFocus);
        assertNull(calls.invoke(null, metadata(String.class, String.class, int.class, int.class), new Object[]{GUEST, null, 0, 7}));
        assertEquals(0, original.calls);
        authenticatedFocus.set(true);
        calls.invoke(null, metadata(String.class, String.class, int.class, int.class), new Object[]{GUEST, null, 0, 7});
        assertEquals(1, original.calls);
        authenticatedFocus.set(false);
        calls.invoke(null, metadata(String.class, String.class, int.class, int.class), new Object[]{GUEST, null, 0, 7});
        assertEquals(1, original.calls);
    }

    @Test public void foreignClaimsAndIncomingBinderIdentityNeverReachPhysicalRead() throws Exception {
        Original original = new Original();
        GuestClipboardCalls calls = adapter(original, 34, new AtomicBoolean(true));
        Method method = metadata(String.class, String.class, int.class, int.class);
        for (Object[] args : new Object[][]{{"another.guest", null, 0, 7}, {"physical.host", null, 0, 7},
                {GUEST, null, 1, 7}, {GUEST, null, 0, 8}}) {
            assertThrows(SecurityException.class, () -> calls.invoke(null, method, args));
        }
        assertThrows(UnsupportedOperationException.class,
                () -> calls.invoke(null, method, new Object[]{GUEST, "unmapped", 0, 7}));
        GuestClipboardCalls remote = new GuestClipboardCalls(original, GUEST, "physical.host", null, 12, 7, 34,
                () -> true, () -> { throw new SecurityException("Incoming remote caller"); });
        assertThrows(SecurityException.class, () -> remote.invoke(null, method, new Object[]{GUEST, null, 0, 7}));
        assertEquals(0, original.calls);
    }

    @Test public void listenersAndOtherQueriesRemainUnmodifiedAndErrorsRemainReal() throws Throwable {
        Original original = new Original();
        GuestClipboardCalls calls = adapter(original, 34, new AtomicBoolean(false));
        Object listener = new Object();
        Object[] input = {listener, GUEST, "guest-tag", 4, 9};
        calls.invoke(null, Original.class.getMethod("addPrimaryClipChangedListener", Object.class, String.class, String.class, int.class, int.class), input);
        assertArrayEquals(input, original.arguments);
        assertSame(listener, original.arguments[0]);
        Object[] query = {GUEST, "guest-tag", 4, 9};
        assertEquals(Boolean.TRUE, calls.invoke(null,
                Original.class.getMethod("hasPrimaryClip", String.class, String.class, int.class, int.class), query));
        assertArrayEquals(query, original.arguments);
        original.failure = new SecurityException("Physical OS denial");
        GuestClipboardCalls focused = adapter(original, 34, new AtomicBoolean(true));
        Throwable failure = assertThrows(SecurityException.class, () -> focused.invoke(null,
                metadata(String.class, String.class, int.class, int.class), new Object[]{GUEST, null, 0, 7}));
        assertSame(original.failure, failure);
    }

    @Test public void unknownMetadataShapeFailsBeforeAnyPhysicalRead() throws Exception {
        Original original = new Original();
        GuestClipboardCalls calls = adapter(original, 34, new AtomicBoolean(true));
        assertThrows(UnsupportedOperationException.class, () -> calls.invoke(null,
                metadata(String.class, String.class, int.class), new Object[]{GUEST, null, 0}));
        assertEquals(0, original.calls);
    }
}
