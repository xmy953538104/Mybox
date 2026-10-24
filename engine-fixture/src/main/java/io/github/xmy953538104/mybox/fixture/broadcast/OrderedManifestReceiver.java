package io.github.xmy953538104.mybox.fixture.broadcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Ordinary manifest receiver, including real asynchronous ordered result completion. */
public final class OrderedManifestReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!isOrderedBroadcast()) {
            try (java.io.FileOutputStream output = context.openFileOutput(
                    "broadcast-unordered-" + intent.getStringExtra("run"), Context.MODE_PRIVATE)) {
                output.write(1);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            return;
        }
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                Thread.sleep(30);
                result.setResultCode(result.getResultCode() + 1);
                String before = result.getResultData();
                result.setResultData((before == null ? "" : before) + "M");
                Bundle extras = result.getResultExtras(true);
                extras.putString("chain", extras.getString("chain", "") + "M");
                extras.putInt("manifest_pid", android.os.Process.myPid());
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            finally { result.finish(); }
        }, "ordered-fixture").start();
    }
}
