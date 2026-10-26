package io.github.xmy953538104.googlecheck;

import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

/** Explicit diagnostic selection only; no account entry, policy change or authentication verdict. */
public final class GoogleAccountObservationTest extends InstrumentationTestCase {
    public void testExistingStoreQueryCounters() throws Exception {
        InstalledPackage store = Engine.get().packages().get("com.android.vending");
        InstalledPackage microg = Engine.get().packages().get("com.google.android.gms");
        assertNotNull("This observation requires an already installed Store", store);
        assertNotNull("This observation requires the existing microG installation", microg);
        GoogleRuntimeTest.ActivityInspector inspector = new GoogleRuntimeTest.ActivityInspector();
        Engine.get().prepareGoogleServices();
        Engine.get().launch(store.packageName);
        inspector.awaitResumed(null, store);
        // Resumption can precede asynchronous account discovery; idle is only a snapshot boundary.
        getInstrumentation().getUiAutomation().waitForIdle(1000, 15000);
        inspector.diagnoseActivityWait(store);
        inspector.diagnoseActivityWait(microg);
        System.out.println("MYLITE_ACCOUNT_QUERY_OBSERVATION requestedStoreAndMicrog=true bootstrapRequested=true accountValuesRetained=false authenticationVerified=false");
    }
}
