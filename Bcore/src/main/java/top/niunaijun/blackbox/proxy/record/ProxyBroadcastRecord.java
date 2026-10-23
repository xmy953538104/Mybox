package top.niunaijun.blackbox.proxy.record;

import android.content.Intent;
import android.os.Bundle;

/**
 * Created by Milk on 4/7/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ProxyBroadcastRecord {
    public Intent mIntent;
    public int mUserId;

    public ProxyBroadcastRecord(Intent intent, int userId) {
        mIntent = intent;
        mUserId = userId;
    }

    public static void saveStub(Intent shadow, Intent target, int userId) {
        shadow.putExtra("_B_|_target_", target);
        shadow.putExtra("_B_|_user_id_", userId);
    }

    public static ProxyBroadcastRecord create(Intent intent) {
        Intent target = intent.getParcelableExtra("_B_|_target_");
        int userId = intent.getIntExtra("_B_|_user_id_", 0);
        if (target != null && intent.getBooleanExtra("_B_|_pending_broadcast_", false)) {
            target = new Intent(target);
            Bundle extras = intent.getExtras();
            if (extras != null) {
                extras.remove("_B_|_target_");
                extras.remove("_B_|_user_id_");
                extras.remove("_B_|_pending_broadcast_");
                target.putExtras(extras);
            }
        }
        return new ProxyBroadcastRecord(target, userId);
    }

    @Override
    public String toString() {
        return "ProxyBroadcastRecord{" +
                "mIntent=" + mIntent +
                ", mUserId=" + mUserId +
                '}';
    }
}
