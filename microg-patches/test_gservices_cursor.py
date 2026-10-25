#!/usr/bin/env python3
"""Exercise the patched production query method with simulated cursor ownership.

This JVM harness does not prove Android Binder, SQLite or Play startup behavior.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

from test_identity_self import java_tool


def query_method(source):
    start = "    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {"
    end = "    @Override\n    public String getType(Uri uri) {"
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError("Expected the pinned GServicesProvider query/getType boundaries")
    method = source[source.index(start):source.index(end)].rstrip()
    if not method.endswith("}"):
        raise ValueError("Incomplete production query method")
    return method


HARNESS = r'''
import java.util.*;
public final class GservicesCursorSelfTest {
    interface Cursor { void close(); }
    static final class MatrixCursor implements Cursor {
        static int created, closed;
        static MatrixCursor last;
        final List<String[]> rows = new ArrayList<>();
        boolean isClosed;
        MatrixCursor(String[] columns) { created++; last = this; }
        void addRow(String[] row) { rows.add(row); }
        int getCount() { return rows.size(); }
        public void close() {
            if (isClosed) throw new AssertionError("Cursor closed twice");
            isClosed = true; closed++;
        }
    }
    static final class Uri { }
    static final Uri PREFIX_URI = new Uri(), MAIN_URI = new Uri();
    static final String TAG = "simulated";
    static final class Log {
        static void w(String tag, String message) { }
        static void d(String tag, String message) { }
    }
    static final class MyboxIdentity {
        static boolean allowed = true;
        static RuntimeException failure;
        static int calls, failAt;
        static boolean isCallingSelf(Object context) {
            if (++calls == failAt) throw failure;
            return allowed;
        }
    }
    static final class DatabaseHelper {
        final Map<String, String> values = new HashMap<>();
        Error failure;
        String get(String name) { if (failure != null) throw failure; return values.get(name); }
        Map<String, String> search(String prefix) {
            if (failure != null) throw failure;
            return values;
        }
    }
    final DatabaseHelper databaseHelper = new DatabaseHelper();
    final Map<String, String> cache = new HashMap<>();
    final Set<String> cachedPrefixes = new HashSet<>();
    Object getContext() { return this; }
    String getCallingPackage() { return "simulated.caller"; }
    boolean callerIsGoogle() { return false; }
    boolean callerIsOwner() { return false; }
    boolean callerHasReadPermissionRequested() { return false; }

    // PRODUCTION_QUERY

    private static void empty(GservicesCursorSelfTest provider, Uri uri, String[] names) {
        int created = MatrixCursor.created, closed = MatrixCursor.closed;
        assert provider.query(uri, null, null, names, null) == null : "Empty result must remain null";
        assert MatrixCursor.created == created + 1;
        assert MatrixCursor.closed == closed + 1 : "Empty cursor leaked";
    }
    private static void nonempty(Uri uri, String[] names) {
        GservicesCursorSelfTest provider = new GservicesCursorSelfTest();
        provider.databaseHelper.values.put("prefix.present", "value");
        int closed = MatrixCursor.closed;
        MatrixCursor result = (MatrixCursor) provider.query(uri, null, null, names, null);
        assert result == MatrixCursor.last && result.getCount() == 1;
        assert "value".equals(result.rows.get(0)[1]);
        assert !result.isClosed && MatrixCursor.closed == closed : "Returned cursor was closed before use";
        result.close();
    }
    public static void main(String[] args) {
        GservicesCursorSelfTest provider = new GservicesCursorSelfTest();
        for (int i = 0; i < 1000; i++) empty(provider, MAIN_URI, new String[]{"absent." + i});
        empty(provider, PREFIX_URI, new String[]{"missing-prefix"});
        nonempty(MAIN_URI, new String[]{"prefix.present"});
        nonempty(PREFIX_URI, new String[]{"prefix."});
        int created = MatrixCursor.created;
        MyboxIdentity.allowed = false;
        assert provider.query(MAIN_URI, null, null, new String[]{"denied"}, null) == null;
        assert MatrixCursor.created == created : "Rejected request allocated a cursor";
        MyboxIdentity.allowed = true;
        for (Uri uri : new Uri[]{MAIN_URI, PREFIX_URI}) {
            GservicesCursorSelfTest broken = new GservicesCursorSelfTest();
            Error failure = new AssertionError("simulated database failure");
            broken.databaseHelper.failure = failure;
            int closed = MatrixCursor.closed;
            try { broken.query(uri, null, null, new String[]{"absent"}, null); throw new AssertionError("Failure swallowed"); }
            catch (Error actual) { assert actual == failure : "Original failure replaced"; }
            assert MatrixCursor.closed == closed + 1 : "Failed query leaked its cursor";
        }
        int closed = MatrixCursor.closed;
        try { provider.query(MAIN_URI, null, null, null, null); throw new AssertionError("Null arguments accepted"); }
        catch (NullPointerException expected) { }
        assert MatrixCursor.closed == closed + 1;
        MyboxIdentity.calls = 0; MyboxIdentity.failAt = 2;
        RuntimeException failure = new SecurityException("simulated mid-query identity failure");
        MyboxIdentity.failure = failure;
        closed = MatrixCursor.closed;
        try { provider.query(MAIN_URI, null, null, new String[]{"android_id"}, null); throw new AssertionError("Identity failure swallowed"); }
        catch (RuntimeException actual) { assert actual == failure; }
        assert MatrixCursor.closed == closed + 1;
        assert MatrixCursor.created == MatrixCursor.closed : "A test cursor remained owned without a caller";
        System.out.println("Simulated production GServices query: empty null/close, returned ownership, rejection and exception cleanup passed.");
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Patched GServicesProvider.java")
    parser.add_argument("--self-test", action="store_true", help="Check method extraction without Java")
    args = parser.parse_args()
    if args.self_test:
        sample = "    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {\n        return null;\n    }\n\n    @Override\n    public String getType(Uri uri) {\n    }\n"
        assert query_method(sample).endswith("return null;\n    }")
        for invalid in ("", sample + sample, sample.replace("    public String getType", "    public String other")):
            try:
                query_method(invalid)
            except ValueError:
                continue
            raise AssertionError("Changed source boundaries were accepted")
        assert HARNESS.count("// PRODUCTION_QUERY") == 1
        print("Production-method extraction checks passed; JVM behavior not executed.")
        return
    if args.source is None:
        parser.error("--source or --self-test is required")
    method = query_method(args.source.resolve(strict=True).read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="mybox-gservices-jvm-") as temporary:
        root = Path(temporary)
        source = root / "GservicesCursorSelfTest.java"
        source.write_text(HARNESS.replace("// PRODUCTION_QUERY", method), encoding="utf-8")
        subprocess.run([java_tool("javac"), "--release", "8", "-d", str(root), str(source)], check=True, timeout=60)
        subprocess.run([java_tool("java"), "-ea", "-cp", str(root), "GservicesCursorSelfTest"], check=True, timeout=60)
    print("JVM simulated cursor checks passed; this is not Android/SQLite/Play runtime evidence.")


if __name__ == "__main__":
    main()
