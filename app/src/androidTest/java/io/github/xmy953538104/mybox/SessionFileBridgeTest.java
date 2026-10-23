package io.github.xmy953538104.mybox;

import android.os.ParcelFileDescriptor;
import android.test.InstrumentationTestCase;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.core.system.pm.installer.SessionFileBridge;

public final class SessionFileBridgeTest extends InstrumentationTestCase {
    public void testPlatformStreamFsyncAndCloseProtocol() throws Exception {
        ((MyboxApplication) getInstrumentation().getTargetContext().getApplicationContext()).requireEngine();
        File target = File.createTempFile("filebridge-", ".apk", getInstrumentation().getTargetContext().getCacheDir());
        AtomicBoolean closed = new AtomicBoolean();
        SessionFileBridge bridge = new SessionFileBridge(target, 0, () -> closed.set(true));
        try {
            Class<?> api = Class.forName("android.os.FileBridge$FileBridgeOutputStream");
            java.lang.reflect.Constructor<?> constructor = api.getDeclaredConstructor(ParcelFileDescriptor.class);
            constructor.setAccessible(true);
            bridge.start();
            byte[] expected = new byte[12345];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) i;
            try (OutputStream stream = (OutputStream) constructor.newInstance(bridge.client())) {
                stream.write(expected);
                api.getDeclaredMethod("fsync").invoke(stream);
            }
            bridge.join(5000);
            assertFalse("Bridge did not finish", bridge.isAlive());
            assertFalse("Bridge reported I/O failure", bridge.failed());
            assertTrue("Close was not acknowledged", closed.get());
            byte[] actual = new byte[expected.length];
            int offset = 0;
            try (FileInputStream input = new FileInputStream(target)) {
                while (offset < actual.length) {
                    int count = input.read(actual, offset, actual.length - offset);
                    if (count == -1) break;
                    offset += count;
                }
                assertEquals(-1, input.read());
            }
            assertEquals(expected.length, offset);
            assertTrue("Bridge changed the APK bytes", Arrays.equals(expected, actual));
        } finally {
            bridge.cancel();
            target.delete();
        }
    }
}
