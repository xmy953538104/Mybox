#!/usr/bin/env python3
"""Exercise production query/authorization methods with simulated Android boundaries.

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


def authorization_methods(source):
    start = "    private boolean callerIsGoogle() {"
    end = "    @Override\n    public Cursor query("
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError("Expected the pinned GServices authorization boundaries")
    return source[source.index(start):source.index(end)].rstrip()


def package_methods(source):
    methods = []
    for signature in (
        "    public static String getCallingPackage(@NonNull Context context) {",
        "    public static String getCallingPackage(@NonNull Context context, @Nullable MyboxIdentity.Identity identity) {",
    ):
        if source.count(signature) != 1:
            raise ValueError("Expected the pinned PackageUtils calling-package overloads")
        start = source.index(signature)
        opening = source.index("{", start)
        depth, end = 1, opening + 1
        while end < len(source) and depth:
            depth += (source[end] == "{") - (source[end] == "}")
            end += 1
        if depth:
            raise ValueError("Incomplete production package method")
        methods.append(source[start:end].replace("@NonNull ", "").replace("@Nullable ", ""))
    return "\n\n".join(methods)


def statistics_class(source):
    start = "final class GServicesQueryStats {"
    if source.count(start) != 1 or not source.rstrip().endswith("}"):
        raise ValueError("Expected the pinned GServices diagnostic class")
    return "static " + source[source.index(start):].strip()


def render(production, packages, statistics):
    field = "    private final GServicesQueryStats queryStats = new GServicesQueryStats();"
    if production.count(field) != 1:
        raise ValueError("Expected the actual production diagnostic field")
    return (HARNESS.replace("// PRODUCTION_QUERY", query_method(production))
            .replace("// PRODUCTION_HELPERS", authorization_methods(production))
            .replace("// PRODUCTION_PACKAGES", package_methods(packages))
            .replace("// PRODUCTION_STATS_FIELD", field.strip())
            .replace("// PRODUCTION_STATS", statistics_class(statistics)))


HARNESS = r'''
import java.util.*;
import java.util.concurrent.atomic.*;
public final class GservicesCursorSelfTest {
    interface Cursor { void close(); }
    static final class MatrixCursor implements Cursor {
        static int created, closed;
        final List<String[]> rows = new ArrayList<>();
        boolean isClosed;
        MatrixCursor(String[] columns) { created++; }
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
    static final int SDK_INT = 35;
    static final class Log {
        static final List<String> diagnostics = new ArrayList<>();
        static Error diagnosticFailure;
        static void w(String tag, String message) { }
        static void d(String tag, String message) { }
        static void i(String tag, String message) {
            if (diagnosticFailure != null) throw diagnosticFailure;
            assert "MyliteGServices".equals(tag);
            diagnostics.add(message);
        }
    }
    static final class Binder {
        static int uid = 10123, pid = 8;
        static int getCallingUid() { return uid; }
        static int getCallingPid() { return pid; }
    }
    static final class Process {
        static int myUid() { return 10123; }
        static int myPid() { return 7; }
    }
    static final class ApplicationInfo { int uid = Process.myUid(); }
    static final class PackageInfo { String[] requestedPermissions; }
    static final class PackageManager {
        static final int GET_PERMISSIONS = 4096;
        static final class NameNotFoundException extends Exception { }
        boolean google, requested, missing;
        int signatures, permissionReads;
        RuntimeException signatureFailure, permissionFailure;
        String lastPackage;
        PackageInfo getPackageInfo(String pkg, int flags) throws NameNotFoundException {
            permissionReads++; lastPackage = pkg;
            if (permissionFailure != null) throw permissionFailure;
            if (missing) throw new NameNotFoundException();
            PackageInfo info = new PackageInfo();
            info.requestedPermissions = requested ? new String[]{"com.google.android.providers.gsf.permission.READ_GSERVICES"} : null;
            return info;
        }
    }
    static final class ExtendedPackageInfo {
        final PackageManager manager;
        ExtendedPackageInfo(PackageManager manager, String pkg) { this.manager = manager; manager.lastPackage = pkg; }
        boolean isGooglePackage() {
            manager.signatures++;
            if (manager.signatureFailure != null) throw manager.signatureFailure;
            return manager.google;
        }
    }
    static final class DevicePolicyManager {
        boolean owner, profileOwner;
        int calls;
        RuntimeException failure;
        boolean isDeviceOwnerApp(String pkg) { calls++; if (failure != null) throw failure; return owner; }
        boolean isProfileOwnerApp(String pkg) { calls++; if (failure != null) throw failure; return profileOwner; }
    }
    static final class Context {
        final ApplicationInfo application = new ApplicationInfo();
        final PackageManager manager = new PackageManager();
        final DevicePolicyManager policy = new DevicePolicyManager();
        String processPackage = "standalone.caller", uidPackage;
        int processLookups, uidLookups;
        RuntimeException packageFailure;
        String getPackageName() { return "microg.self"; }
        ApplicationInfo getApplicationInfo() { return application; }
        PackageManager getPackageManager() { return manager; }
    }
    static final class ContextCompat {
        static DevicePolicyManager getSystemService(Context context, Class<?> type) { return context.policy; }
    }
    static final class MyboxIdentity {
        static final class Identity {
            final String packageName;
            Identity(String pkg) { packageName = pkg; }
        }
        static Identity response = new Identity("microg.self");
        static RuntimeException failure;
        static int calls, failAt;
        static Identity resolve(Context context) {
            if (++calls == failAt) throw failure;
            return response;
        }
    }
    static final class PackageUtils {
        static String packageFromProcessId(Context context, int pid) {
            context.processLookups++;
            if (context.packageFailure != null) throw context.packageFailure;
            return context.processPackage;
        }
        static String firstPackageFromUserId(Context context, int uid) { context.uidLookups++; return context.uidPackage; }
        // PRODUCTION_PACKAGES
    }
    static final class DatabaseHelper {
        final Map<String, String> values = new HashMap<>();
        Error failure;
        String get(String name) { if (failure != null) throw failure; return values.get(name); }
        Map<String, String> search(String prefix) { if (failure != null) throw failure; return values; }
    }
    final DatabaseHelper databaseHelper = new DatabaseHelper();
    final Map<String, String> cache = new HashMap<>();
    final Set<String> cachedPrefixes = new HashSet<>();
    final Context context = new Context();
    Context getContext() { return context; }
    String getCallingPackage() { return "diagnostic.only"; }
    // PRODUCTION_STATS
    // PRODUCTION_STATS_FIELD
    // PRODUCTION_HELPERS
    // PRODUCTION_QUERY

    private static GservicesCursorSelfTest caller(String pkg) {
        MyboxIdentity.response = pkg == null ? null : new MyboxIdentity.Identity(pkg);
        MyboxIdentity.calls = 0; MyboxIdentity.failAt = 0; MyboxIdentity.failure = null;
        Binder.uid = Process.myUid(); Binder.pid = Process.myPid() + 1;
        GservicesCursorSelfTest provider = new GservicesCursorSelfTest();
        provider.databaseHelper.values.put("present", "value");
        provider.databaseHelper.values.put("android_id", "synthetic-id");
        return provider;
    }
    private static void rows(GservicesCursorSelfTest provider, Uri uri, String[] names, int expected) {
        int created = MatrixCursor.created, closed = MatrixCursor.closed;
        MatrixCursor result = (MatrixCursor) provider.query(uri, null, null, names, null);
        if (expected == 0) {
            assert result == null : "Empty/denied result must remain null";
            assert MatrixCursor.created - created == MatrixCursor.closed - closed : "Unreturned cursor leaked";
        } else {
            assert result != null && result.getCount() == expected;
            assert !result.isClosed && MatrixCursor.closed == closed : "Caller-owned cursor was closed";
            result.close();
        }
    }
    private static void queryScopeAndAuthorization() {
        GservicesCursorSelfTest local = caller("foreign");
        Binder.pid = Process.myPid(); MyboxIdentity.failAt = 1; MyboxIdentity.failure = new SecurityException("No local IPC");
        rows(local, MAIN_URI, new String[]{"present", "android_id"}, 2);
        assert MyboxIdentity.calls == 0 && local.context.manager.signatures == 0;
        GservicesCursorSelfTest differentUid = caller("verified.play");
        Binder.pid = Process.myPid(); Binder.uid = Process.myUid() + 1; differentUid.context.manager.google = true;
        rows(differentUid, MAIN_URI, new String[]{"present"}, 1);
        assert MyboxIdentity.calls == 1 : "A matching PID alone skipped identity verification";

        GservicesCursorSelfTest google = caller("verified.play"); google.context.manager.google = true;
        rows(google, MAIN_URI, new String[]{"present", "android_id", "android_id"}, 3);
        assert MyboxIdentity.calls == 1 && google.context.manager.signatures == 3 : "Identity or certificate decision was cached at the wrong scope";
        assert "verified.play".equals(google.context.manager.lastPackage);
        google.context.manager.google = false;
        rows(google, MAIN_URI, new String[]{"present"}, 0);
        assert MyboxIdentity.calls == 2 : "A second request reused the first identity";

        GservicesCursorSelfTest reader = caller("reader"); reader.context.manager.requested = true;
        rows(reader, MAIN_URI, new String[]{"present", "android_id"}, 1);
        assert MyboxIdentity.calls == 1 && reader.context.manager.permissionReads == 1;
        reader.context.policy.owner = true;
        rows(reader, MAIN_URI, new String[]{"android_id"}, 1);
        assert MyboxIdentity.calls == 2;
        GservicesCursorSelfTest ownerOnly = caller("owner"); ownerOnly.context.policy.owner = true;
        rows(ownerOnly, MAIN_URI, new String[]{"android_id"}, 0);
        assert ownerOnly.context.policy.calls == 0 : "Owner bypassed the outer read gate";

        GservicesCursorSelfTest standalone = caller(null); Binder.uid = Process.myUid() + 1;
        standalone.context.manager.requested = true; standalone.context.processPackage = null; standalone.context.uidPackage = "uid.fallback";
        rows(standalone, MAIN_URI, new String[]{"present", "android_id"}, 1);
        assert MyboxIdentity.calls == 1 && standalone.context.processLookups > 0 && standalone.context.uidLookups > 0;
        GservicesCursorSelfTest legacySharedUid = caller(null);
        rows(legacySharedUid, MAIN_URI, new String[]{"present"}, 1);
        assert MyboxIdentity.calls == 1 : "Standalone shared-UID semantics changed";

        GservicesCursorSelfTest changing = caller("first"); changing.context.manager.requested = true;
        rows(changing, MAIN_URI, new String[]{"present"}, 1);
        MyboxIdentity.response = new MyboxIdentity.Identity("second");
        rows(changing, MAIN_URI, new String[]{"present"}, 1);
        assert MyboxIdentity.calls == 2 && "second".equals(changing.context.manager.lastPackage);
        int before = MyboxIdentity.calls;
        assert "second".equals(PackageUtils.getCallingPackage(changing.context));
        assert MyboxIdentity.calls == before + 1 : "Existing PackageUtils caller stopped resolving";
    }
    private static void exceptionsAndOwnership() {
        GservicesCursorSelfTest absent = caller("microg.self");
        for (int i = 0; i < 1000; i++) rows(absent, MAIN_URI, new String[]{"absent." + i}, 0);
        rows(absent, PREFIX_URI, new String[]{"missing-prefix"}, 0);
        rows(caller("microg.self"), PREFIX_URI, new String[]{"pre"}, 1);
        for (Uri uri : new Uri[]{MAIN_URI, PREFIX_URI}) {
            GservicesCursorSelfTest broken = caller("microg.self"); broken.databaseHelper.failure = new AssertionError("database failure");
            int closed = MatrixCursor.closed;
            try { broken.query(uri, null, null, new String[]{"absent"}, null); throw new AssertionError("Database failure swallowed"); }
            catch (Error actual) { assert actual == broken.databaseHelper.failure; }
            assert MatrixCursor.closed == closed + 1;
        }
        GservicesCursorSelfTest identity = caller("reader");
        Binder.pid = 0;
        SecurityException denied = new SecurityException("Identity bridge rejection");
        MyboxIdentity.failure = denied; MyboxIdentity.failAt = 1;
        int created = MatrixCursor.created;
        try { identity.query(MAIN_URI, null, null, new String[]{"present"}, null); throw new AssertionError("Identity failure swallowed"); }
        catch (SecurityException actual) { assert actual == denied; }
        assert MatrixCursor.created == created;
        GservicesCursorSelfTest noSecondResolve = caller("verified.play"); noSecondResolve.context.manager.google = true;
        MyboxIdentity.failure = denied; MyboxIdentity.failAt = 2;
        rows(noSecondResolve, MAIN_URI, new String[]{"android_id"}, 1);
        assert MyboxIdentity.calls == 1;

        GservicesCursorSelfTest signature = caller("reader"); signature.context.manager.requested = true;
        signature.context.manager.signatureFailure = denied;
        rows(signature, MAIN_URI, new String[]{"present"}, 1); // Google exceptions remain false; READ can still allow.
        signature.context.manager.permissionFailure = denied;
        try { signature.query(MAIN_URI, null, null, new String[]{"present"}, null); throw new AssertionError("READ exception swallowed"); }
        catch (SecurityException actual) { assert actual == denied; }
        GservicesCursorSelfTest missing = caller("reader"); missing.context.manager.missing = true;
        rows(missing, MAIN_URI, new String[]{"present"}, 0);
        GservicesCursorSelfTest owner = caller("reader"); owner.context.manager.requested = true; owner.context.policy.failure = denied;
        rows(owner, MAIN_URI, new String[]{"android_id"}, 0);

        GservicesCursorSelfTest fallback = caller(null); Binder.uid = Process.myUid() + 1; fallback.context.packageFailure = denied;
        try { fallback.query(MAIN_URI, null, null, new String[]{"present"}, null); throw new AssertionError("Fallback exception swallowed by READ"); }
        catch (SecurityException actual) { assert actual == denied; }
        GservicesCursorSelfTest update = caller("reader"); MyboxIdentity.failAt = 1; MyboxIdentity.failure = denied;
        assert !update.callerIsGoogle() : "Update Google helper changed its catch boundary";
        GservicesCursorSelfTest nullArgs = caller("microg.self"); int closed = MatrixCursor.closed;
        try { nullArgs.query(MAIN_URI, null, null, null, null); throw new AssertionError("Null names accepted"); }
        catch (NullPointerException expected) { }
        assert MatrixCursor.closed == closed + 1;
        assert MatrixCursor.created == MatrixCursor.closed : "Unowned test cursor remained open";
    }
    private static void diagnosticCountsAndSampling() {
        Log.diagnostics.clear();
        GservicesCursorSelfTest empty = caller("microg.self"); empty.databaseHelper.values.clear();
        rows(empty, PREFIX_URI, new String[]{"private-prefix-do-not-log"}, 0);
        rows(empty, MAIN_URI, new String[]{"private-name-do-not-log"}, 0);
        rows(empty, new Uri(), new String[]{"private-other-do-not-log"}, 0);
        assert empty.queryStats.queries.get() == 3;
        assert empty.queryStats.outcomes.get(1) == 1 && empty.queryStats.outcomes.get(5) == 2
                : "Prefix and non-prefix query branches were conflated";
        assert empty.queryStats.rows.get() == 0;
        GservicesCursorSelfTest denied = caller("reader");
        rows(denied, PREFIX_URI, new String[]{"private-prefix-do-not-log"}, 0);
        assert denied.queryStats.outcomes.get(0) == 1 : "Denied read became authorized-null";
        GservicesCursorSelfTest present = caller("microg.self");
        rows(present, MAIN_URI, new String[]{"present", "android_id"}, 2);
        assert present.queryStats.outcomes.get(6) == 1 && present.queryStats.rows.get() == 2;
        for (String line : Log.diagnostics) {
            assert line.contains("completed=") && line.contains("totalUs=") && line.contains("maxUs=");
            assert !line.contains("private-") && !line.contains("android_id") && !line.contains("synthetic-id")
                    && !line.contains("microg.self") && !line.contains("diagnostic.only") : "Sensitive query data reached diagnostic logs";
        }
        Log.diagnostics.clear();
        GServicesQueryStats stats = new GServicesQueryStats();
        for (int i = 0; i < 8192; i++)
            stats.record(PREFIX_URI, PREFIX_URI, GServicesQueryStats.AUTHORIZED_NULL, 0, System.nanoTime());
        assert stats.queries.get() == 8192 && stats.outcomes.get(1) == 8192;
        assert Log.diagnostics.size() == 64 : "Diagnostic snapshots exceeded their lifetime bound";
        for (int i = 0; i < 4; i++) assert Log.diagnostics.get(i).startsWith("completed=" + (i + 1) + " ");
        assert Log.diagnostics.get(4).startsWith("completed=128 ");
        assert Log.diagnostics.get(63).startsWith("completed=7680 ");
        for (long count : new long[]{-1, 0, 5, 127, 129, 7681, 7808, Long.MAX_VALUE})
            assert !GServicesQueryStats.sample(count) : "Invalid sampling boundary " + count;
        assert stats.totalNanos.get() >= stats.maxNanos.get() && stats.maxNanos.get() >= 0;
        long before = stats.queries.get();
        stats.record(PREFIX_URI, PREFIX_URI, -1, 0, System.nanoTime());
        stats.record(PREFIX_URI, PREFIX_URI, GServicesQueryStats.ROWS, -1, System.nanoTime());
        assert stats.queries.get() == before : "Invalid diagnostics changed counters";
    }
    private static void diagnosticFailuresAreTransparent() {
        Log.diagnosticFailure = new AssertionError("Injected diagnostic sink failure");
        try {
            GservicesCursorSelfTest success = caller("microg.self");
            rows(success, MAIN_URI, new String[]{"present"}, 1);
            assert success.queryStats.outcomes.get(6) == 1;
            GservicesCursorSelfTest empty = caller("microg.self");
            rows(empty, PREFIX_URI, new String[]{"absent"}, 0);
            assert empty.queryStats.outcomes.get(1) == 1;
            GservicesCursorSelfTest failure = caller("reader");
            SecurityException original = new SecurityException("Original authorization failure");
            MyboxIdentity.failure = original; MyboxIdentity.failAt = 1;
            try { failure.query(PREFIX_URI, null, null, new String[]{"private"}, null); throw new AssertionError("Original failure swallowed"); }
            catch (SecurityException actual) { assert actual == original : "Diagnostics replaced the original exception"; }
            assert failure.queryStats.outcomes.get(3) == 1;
            GservicesCursorSelfTest database = caller("microg.self");
            database.databaseHelper.failure = new AssertionError("Original database failure");
            try { database.query(MAIN_URI, null, null, new String[]{"absent"}, null); throw new AssertionError("Original failure swallowed"); }
            catch (Error actual) { assert actual == database.databaseHelper.failure; }
            assert database.queryStats.outcomes.get(7) == 1;
        } finally { Log.diagnosticFailure = null; }
        assert MatrixCursor.created == MatrixCursor.closed : "Diagnostic failure changed cursor ownership";
    }
    public static void main(String[] args) {
        queryScopeAndAuthorization(); exceptionsAndOwnership();
        diagnosticCountsAndSampling(); diagnosticFailuresAreTransparent();
        System.out.println("Simulated production GServices query/helpers: identity, decisions, cursor ownership, safe bounded diagnostics and exception transparency passed.");
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Patched GServicesProvider.java")
    parser.add_argument("--package-source", type=Path, help="Patched PackageUtils.java; normally inferred from the source tree")
    parser.add_argument("--stats-source", type=Path, help="Patched GServicesQueryStats.java; normally beside the provider")
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
        assert HARNESS.count("// PRODUCTION_HELPERS") == 1
        assert HARNESS.count("// PRODUCTION_PACKAGES") == 1
        assert HARNESS.count("// PRODUCTION_STATS") == 2  # class and actual field placeholders
        assert HARNESS.count("// PRODUCTION_STATS_FIELD") == 1
        assert statistics_class("final class GServicesQueryStats { }") == "static final class GServicesQueryStats { }"
        for invalid in ("", "class Other { }", "final class GServicesQueryStats { }" * 2):
            try:
                statistics_class(invalid)
            except ValueError:
                continue
            raise AssertionError("Changed diagnostic class boundaries were accepted")
        print("Production-method extraction checks passed; JVM behavior not executed.")
        return
    if args.source is None:
        parser.error("--source or --self-test is required")
    source_path = args.source.resolve(strict=True)
    production = source_path.read_text(encoding="utf-8")
    package_path = args.package_source or source_path.parents[8] / "play-services-base/core/src/main/java/org/microg/gms/common/PackageUtils.java"
    stats_path = args.stats_source or source_path.with_name("GServicesQueryStats.java")
    generated = render(production, package_path.resolve(strict=True).read_text(encoding="utf-8"),
                       stats_path.resolve(strict=True).read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="mybox-gservices-jvm-") as temporary:
        root = Path(temporary)
        source = root / "GservicesCursorSelfTest.java"
        source.write_text(generated, encoding="utf-8")
        subprocess.run([java_tool("javac"), "--release", "8", "-d", str(root), str(source)], check=True, timeout=60)
        subprocess.run([java_tool("java"), "-ea", "-cp", str(root), "GservicesCursorSelfTest"], check=True, timeout=60)
    print("JVM simulated cursor checks passed; this is not Android/SQLite/Play runtime evidence.")


if __name__ == "__main__":
    main()
