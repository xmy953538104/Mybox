package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Collections;

public final class DexLoadIdentityTest {
    @Test public void mapsOnlyTheOwnerAndPreservesDexContextAndOriginalArguments() {
        Object paths = Collections.singletonMap("/data/user/0/host/files/engine/guest/base.apk", "PCL[]");
        Object[] arguments = {"com.google.android.gms", paths, "arm64"};
        Object[] mapped = GuestPackageQueries.dexLoadArguments(arguments, "host", "com.google.android.gms");
        assertEquals("host", mapped[0]);
        assertSame(paths, mapped[1]);
        assertEquals("arm64", mapped[2]);
        assertEquals("com.google.android.gms", arguments[0]);
        Object[] legacy = {"guest", Collections.singletonList("dalvik.system.PathClassLoader"),
                Collections.singletonList("/a.apk:/b.apk"), "x86_64"};
        assertEquals(4, GuestPackageQueries.dexLoadArguments(legacy, "host", "guest").length);
    }

    @Test public void rejectsForeignOrUnboundGuestReports() {
        for (String bound : new String[]{null, "guest"}) {
            try {
                GuestPackageQueries.dexLoadArguments(new Object[]{"other", Collections.emptyMap(), "arm64"}, "host", bound);
                fail("Foreign package report was attributed to the host");
            } catch (SecurityException expected) { }
        }
        assertEquals("host", GuestPackageQueries.dexLoadArguments(
                new Object[]{"host", Collections.emptyMap(), "arm64"}, "host", null)[0]);
    }
}
