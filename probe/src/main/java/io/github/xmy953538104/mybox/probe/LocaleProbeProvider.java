// SPDX-License-Identifier: Apache-2.0
package io.github.xmy953538104.mybox.probe;

import android.app.LocaleManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.LocaleList;

public final class LocaleProbeProvider extends ContentProvider {
    private String initialResourceLocale;

    @Override public boolean onCreate() {
        LocaleList locales = getContext().getResources().getConfiguration().getLocales();
        initialResourceLocale = locales.isEmpty() ? "" : locales.get(0).toLanguageTag();
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        result.putInt("pid", android.os.Process.myPid());
        result.putString("preferences", getContext().getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags());
        LocaleList resources = getContext().getResources().getConfiguration().getLocales();
        result.putString("firstResourceLocale", resources.isEmpty() ? "" : resources.get(0).toLanguageTag());
        result.putString("initialResourceLocale", initialResourceLocale);
        result.putString("translated", getContext().getString(R.string.locale_probe));
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
