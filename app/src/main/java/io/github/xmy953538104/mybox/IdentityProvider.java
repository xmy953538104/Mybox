package io.github.xmy953538104.mybox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BlackBoxSystem;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;

public final class IdentityProvider extends ContentProvider {
    @Override public boolean onCreate() {
        BlackBoxSystem.getSystem().startup();
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"resolveCallingIdentity".equals(method) || extras == null) {
            throw new IllegalArgumentException("Unsupported identity query");
        }
        int queryingPid = Binder.getCallingPid();
        if (Binder.getCallingUid() != BlackBoxCore.getHostUid()) {
            throw new SecurityException("Caller is outside Mybox");
        }
        return BPackageManagerService.get().resolveCallingIdentity(queryingPid,
                extras.getInt("originalUid", -1), extras.getInt("originalPid", -1));
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
