package io.github.xmy953538104.mybox.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Nonexported OS wake entry; carries no caller-supplied account, component or completion. */
public final class ContentSyncReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ServerContentSync.WAKE.equals(intent.getAction())
                && !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) throw new SecurityException("Invalid sync wake action");
        EngineServerProvider.wakeContentSync();
    }
}
