package io.github.xmy953538104.mybox.fixture;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
public final class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() {
        try (FileOutputStream out = getContext().openFileOutput("provider-created.txt", 0)) {
            out.write(getContext().getPackageName().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            return true;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
