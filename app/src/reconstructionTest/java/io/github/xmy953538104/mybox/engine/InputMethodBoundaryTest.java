package io.github.xmy953538104.mybox.engine;

import android.os.Bundle;
import android.os.IBinder;
import android.test.InstrumentationTestCase;
import android.view.inputmethod.EditorInfo;
import java.lang.reflect.Method;

public final class InputMethodBoundaryTest extends InstrumentationTestCase {
    public static final class Recorder {
        EditorInfo received; IBinder token; int calls, sequence;
        public Object startInputOrWindowGainedFocus(int reason, Object client, IBinder token, int flags, int mode, int windowFlags, EditorInfo editor) {
            calls++; received = editor; this.token = token; return this;
        }
        public void startInputOrWindowGainedFocusAsync(int reason, Object client, IBinder token, int flags, int mode, int windowFlags,
                EditorInfo editor, Object inputConnection, Object accessibilityConnection, int targetSdk, int user, Object dispatcher, int sequence) {
            calls++; received = editor; this.token = token; this.sequence = sequence;
        }
    }
    public void testPhysicalEditorIsCopiedAndForeignIdentityCannotReachIme() throws Throwable {
        Recorder recorder = new Recorder();
        GuestInputMethodCalls boundary = new GuestInputMethodCalls(recorder, "host", "guest");
        Method method = Recorder.class.getMethod("startInputOrWindowGainedFocus", int.class, Object.class, IBinder.class, int.class, int.class, int.class, EditorInfo.class);
        EditorInfo editor = new EditorInfo(); editor.packageName = "guest"; editor.fieldId = 42;
        editor.inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
        editor.initialSelStart = 2; editor.initialSelEnd = 5; editor.extras = new Bundle(); editor.extras.putString("fixture", "preserved");
        IBinder window = new android.os.Binder(); Object[] args = {1, null, window, 2, 3, 4, editor};
        assertSame(recorder, boundary.invoke(null, method, args));
        assertNotSame(editor, recorder.received); assertEquals("host", recorder.received.packageName); assertEquals("guest", editor.packageName);
        assertSame(window, recorder.token); assertEquals(editor.inputType, recorder.received.inputType);
        assertEquals(42, recorder.received.fieldId); assertEquals(2, recorder.received.initialSelStart); assertEquals(5, recorder.received.initialSelEnd);
        recorder.received.extras.putString("fixture", "changed"); assertEquals("preserved", editor.extras.getString("fixture"));
        editor.packageName = "foreign";
        try { boundary.invoke(null, method, args); fail("Foreign editor reached the OS"); }
        catch (SecurityException expected) { }
        assertEquals(1, recorder.calls);
        args[6] = null; boundary.invoke(null, method, args); assertNull(recorder.received); assertSame(window, recorder.token);
    }
    public void testAsyncStartRetainsRealWindowAndSequenceWithoutMutatingEditor() throws Throwable {
        Recorder recorder = new Recorder();
        GuestInputMethodCalls boundary = new GuestInputMethodCalls(recorder, "host", "guest");
        Method method = Recorder.class.getMethod("startInputOrWindowGainedFocusAsync", int.class, Object.class, IBinder.class, int.class, int.class,
                int.class, EditorInfo.class, Object.class, Object.class, int.class, int.class, Object.class, int.class);
        EditorInfo editor = new EditorInfo(); editor.packageName = "guest"; editor.fieldId = 91;
        IBinder window = new android.os.Binder();
        Object[] args = {1, null, window, 2, 3, 4, editor, null, null, 35, 0, null, 731};
        assertNull(boundary.invoke(null, method, args));
        assertEquals(1, recorder.calls); assertSame(window, recorder.token); assertEquals(731, recorder.sequence);
        assertEquals("host", recorder.received.packageName); assertEquals(91, recorder.received.fieldId);
        assertEquals("guest", editor.packageName); assertSame(editor, args[6]);
        editor.packageName = "foreign";
        try { boundary.invoke(null, method, args); fail("Foreign async editor reached the physical service"); }
        catch (SecurityException expected) { }
        assertEquals(1, recorder.calls);
    }
}
