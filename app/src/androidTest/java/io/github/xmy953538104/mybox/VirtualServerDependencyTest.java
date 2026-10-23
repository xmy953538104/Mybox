// SPDX-License-Identifier: Apache-2.0
package io.github.xmy953538104.mybox;

import android.app.ActivityManager;
import android.content.Context;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;

import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.ServiceManager;

public final class VirtualServerDependencyTest extends InstrumentationTestCase {
    public void testServerStaysDependentOnActiveClientAcrossIdlePeriod() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ((MyboxApplication) context.getApplicationContext()).requireEngine();
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        String serverName = context.getPackageName() + context.getString(top.niunaijun.blackbox.R.string.black_box_service_name);
        IBinder original = BlackBoxCore.get().getService(ServiceManager.PACKAGE_MANAGER);
        int serverPid = -1;
        long deadline = SystemClock.elapsedRealtime() + 35000;
        do {
            List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
            assertNotNull(processes);
            ActivityManager.RunningAppProcessInfo server = null, client = null;
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pid == Process.myPid()) client = process;
                if (serverName.equals(process.processName)) server = process;
            }
            assertNotNull("Instrumentation client is missing", client);
            assertTrue("Test requires an active instrumentation client", client.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
            assertNotNull("Virtual server disappeared while its client was active", server);
            if (serverPid == -1) serverPid = server.pid;
            assertEquals("Virtual server silently restarted", serverPid, server.pid);
            assertTrue("Virtual server became cached behind an active client: " + server.importance,
                    server.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
            Thread.sleep(1000);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertSame(original, BlackBoxCore.get().getService(ServiceManager.PACKAGE_MANAGER));
        assertTrue(original.isBinderAlive());
        assertNull(BlackBoxCore.getBPackageManager().getApplicationInfo("absent.dependency.fixture", 0, 0));
    }
}
