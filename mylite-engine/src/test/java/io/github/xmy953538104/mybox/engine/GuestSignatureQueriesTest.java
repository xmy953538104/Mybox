package io.github.xmy953538104.mybox.engine;

import android.content.pm.PackageManager;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GuestSignatureQueriesTest {
    @Test public void comparisonRequiresTheCompleteSignerSetAndPreservesUnsignedResults() {
        assertEquals(PackageManager.SIGNATURE_MATCH, GuestSignatureQueries.compare(Arrays.asList("a", "b"), Arrays.asList("b", "a")));
        assertEquals(PackageManager.SIGNATURE_NO_MATCH, GuestSignatureQueries.compare(Arrays.asList("a", "b"), Collections.singletonList("a")));
        assertEquals(PackageManager.SIGNATURE_NO_MATCH, GuestSignatureQueries.compare(Collections.singletonList("a"), Collections.singletonList("b")));
        assertEquals(PackageManager.SIGNATURE_FIRST_NOT_SIGNED, GuestSignatureQueries.compare(Collections.emptyList(), Collections.singletonList("b")));
        assertEquals(PackageManager.SIGNATURE_SECOND_NOT_SIGNED, GuestSignatureQueries.compare(Collections.singletonList("a"), Collections.emptyList()));
        assertEquals(PackageManager.SIGNATURE_NEITHER_SIGNED, GuestSignatureQueries.compare(Collections.emptyList(), Collections.emptyList()));
    }

    @Test public void onlyVerifiedPlatformShapesAndVirtualUserZeroAreAccepted() {
        GuestSignatureQueries.validateArguments(false, new Object[]{"package.a", "package.b"});
        GuestSignatureQueries.validateArguments(false, new Object[]{null, "package.b", 0});
        GuestSignatureQueries.validateArguments(true, new Object[]{-1, 10000});
        for (Object user : new Object[]{1, -1, 0L, null}) {
            try { GuestSignatureQueries.validateArguments(false, new Object[]{"package.a", "package.b", user}); fail(); }
            catch (SecurityException expected) { }
        }
        try { GuestSignatureQueries.validateArguments(true, new Object[]{110000, 10000}); fail(); }
        catch (SecurityException expected) { }
        for (Object[] bad : new Object[][]{null, {}, {"package.a"}, {"package.a", 7}, {"package.a", "package.b", 0, 0}}) {
            try { GuestSignatureQueries.validateArguments(false, bad); fail(); }
            catch (EngineException expected) { }
        }
        for (Object[] bad : new Object[][]{{0L, 0}, {"package.a", "package.b"}, {0, 0, 0}}) {
            try { GuestSignatureQueries.validateArguments(true, bad); fail(); }
            catch (EngineException expected) { }
        }
    }
}
