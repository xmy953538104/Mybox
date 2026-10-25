package io.github.xmy953538104.mybox.engine;

import android.os.Message;
import android.test.InstrumentationTestCase;
import java.util.Arrays;

public final class PackageCacheBoundaryTest extends InstrumentationTestCase {
    public void testMixedPhysicalRemovalRetainsEveryOtherEntryAndOriginalPayload() {
        Message message = Message.obtain(); String[] original = {"guest", "physical", "guest", null};
        try {
            message.what = 133; message.arg1 = 0; message.arg2 = 72; message.obj = original;
            assertFalse(LaunchInterceptor.filterPhysicalPackageRemoval(message, "guest", 133, 0));
            assertTrue(Arrays.equals(new String[]{"physical", null}, (String[]) message.obj));
            assertTrue(Arrays.equals(new String[]{"guest", "physical", "guest", null}, original));
            assertEquals(133, message.what); assertEquals(0, message.arg1); assertEquals(72, message.arg2);
        } finally { message.recycle(); }
    }

    public void testOnlyCurrentGuestRemovalIsConsumed() {
        Message message = Message.obtain();
        try {
            message.what = 133; message.arg1 = 0; message.obj = new String[]{"guest", "guest"};
            assertTrue(LaunchInterceptor.filterPhysicalPackageRemoval(message, "guest", 133, 0));
        } finally { message.recycle(); }
    }

    public void testUnboundUnrelatedReplacementAndUnknownMessagesRemainUntouched() {
        Message message = Message.obtain(); String[] original = {"guest"};
        try {
            message.what = 133; message.arg1 = 0; message.obj = original;
            assertFalse(LaunchInterceptor.filterPhysicalPackageRemoval(message, null, 133, 0)); assertSame(original, message.obj);
            assertFalse(LaunchInterceptor.filterPhysicalPackageRemoval(message, "other", 133, 0)); assertSame(original, message.obj);
            for (int command : new int[]{2, 3, 99}) {
                message.arg1 = command;
                assertFalse(LaunchInterceptor.filterPhysicalPackageRemoval(message, "guest", 133, 0)); assertSame(original, message.obj);
            }
            message.arg1 = 0; message.what = 134;
            assertFalse(LaunchInterceptor.filterPhysicalPackageRemoval(message, "guest", 133, 0)); assertSame(original, message.obj);
            message.what = 133; message.obj = "guest";
            assertFalse(LaunchInterceptor.filterPhysicalPackageRemoval(message, "guest", 133, 0)); assertEquals("guest", message.obj);
        } finally { message.recycle(); }
    }
}
