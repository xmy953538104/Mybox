package io.github.xmy953538104.googlecheck;

import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.BoundIdentityScopeRuntimeTest;

/** Actual signed helper and real server rejection; no registered-service exemption. */
public final class GoogleBoundIdentityRuntimeTest extends InstrumentationTestCase {
    public void testActualSourceHelperSharesHostScopeButCannotGrantAnUnregisteredProcess() throws Exception {
        BoundIdentityScopeRuntimeTest.exerciseRemoteScopes(getInstrumentation().getTargetContext(),true);
    }
}
