package io.github.xmy953538104.mybox.engine;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

abstract class EngineProvider extends ContentProvider {
    @Override public boolean onCreate() {
        Engine.attach(getContext());
        return true;
    }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String order) {
        throw new UnsupportedOperationException("Engine providers expose only private calls");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
