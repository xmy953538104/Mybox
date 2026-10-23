package io.github.xmy953538104.mybox;

import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.test.AndroidTestCase;
import top.niunaijun.blackbox.proxy.record.ProxyBroadcastRecord;

public final class BroadcastRecordTest extends AndroidTestCase {
    public void testPendingIntentFillInReachesGuest() {
        Intent target = new Intent("guest.INSTALL_RESULT").putExtra("original", "kept");
        Intent outer = new Intent("mybox.stub_receiver");
        ProxyBroadcastRecord.saveStub(outer, target, 3);
        outer.putExtra("_B_|_pending_broadcast_", true);
        outer.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS);
        outer.putExtra(PackageInstaller.EXTRA_SESSION_ID, 27);
        ProxyBroadcastRecord result = ProxyBroadcastRecord.create(outer);
        assertEquals("guest.INSTALL_RESULT", result.mIntent.getAction());
        assertEquals(3, result.mUserId);
        assertEquals("kept", result.mIntent.getStringExtra("original"));
        assertEquals(PackageInstaller.STATUS_SUCCESS,
                result.mIntent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999));
        assertEquals(27, result.mIntent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1));
        assertFalse(result.mIntent.hasExtra("_B_|_target_"));
        assertFalse(target.hasExtra(PackageInstaller.EXTRA_STATUS));
    }
}
