package io.github.xmy953538104.googlecheck;

import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Explicit diagnostic selection only; no account entry, policy change or authentication verdict. */
public final class GoogleAccountObservationTest extends InstrumentationTestCase {
    public void testExistingStoreQueryCounters() throws Exception {
        InstalledPackage store = Engine.get().packages().get("com.android.vending");
        assertNotNull("This observation requires an already installed Store", store);
        GoogleRuntimeTest.ActivityInspector inspector = new GoogleRuntimeTest.ActivityInspector();
        Engine.get().launch(store.packageName);
        inspector.awaitResumed(null, store);
        inspector.diagnoseActivityWait(store);
        System.out.println("MYLITE_ACCOUNT_QUERY_OBSERVATION recorded=true accountValuesRetained=false authenticationVerified=false");
    }
}
