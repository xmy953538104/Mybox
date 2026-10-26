#!/usr/bin/env python3
"""Run the real MyboxIdentity methods with explicit, simulated Android identities.

The regular build also compiles production sources against the Android SDK. These
small JVM doubles exercise the decision/transport boundary, not Android Binder.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


STUBS = {
    "android/os/IBinder.java": """
package android.os;
public interface IBinder { }
""",
    "io/github/xmy953538104/mybox/engine/BoundServiceIdentity.java": """
package io.github.xmy953538104.mybox.engine;
public final class BoundServiceIdentity {
    private static final ThreadLocal<android.os.IBinder> current = new ThreadLocal<>();
    public static android.os.IBinder currentLeaseV1() { return current.get(); }
    public static void simulatedScope(android.os.IBinder lease) { if(lease==null) current.remove(); else current.set(lease); }
}
""",
    "android/os/Process.java": """
package android.os;
public final class Process {
    public static int myUid() { return 10123; }
    public static int myPid() { return 123; }
}
""",
    "android/os/Binder.java": """
package android.os;
public final class Binder {
    public static int uid = Process.myUid(), pid = Process.myPid(), clears;
    public static int getCallingUid() { return uid; }
    public static int getCallingPid() { return pid; }
    public static long clearCallingIdentity() {
        long token = ((long) uid << 32) | (pid & 0xffffffffL);
        uid = Process.myUid(); pid = Process.myPid(); clears++; return token;
    }
    public static void restoreCallingIdentity(long token) {
        uid = (int) (token >>> 32); pid = (int) token;
    }
}
""",
    "android/os/Bundle.java": """
package android.os;
public final class Bundle {
    private final java.util.Map<String, Object> values = new java.util.HashMap<>();
    public void putInt(String name, int value) { values.put(name, value); }
    public int getInt(String name, int fallback) {
        Object value = values.get(name); return value == null ? fallback : (Integer) value;
    }
    public void putString(String name, String value) { values.put(name, value); }
    public String getString(String name) { return (String) values.get(name); }
    public void putStringArray(String name, String[] value) { values.put(name, value); }
    public String[] getStringArray(String name) { return (String[]) values.get(name); }
    public void putBinder(String name, IBinder value) { values.put(name,value); }
    public IBinder getBinder(String name) { return (IBinder) values.get(name); }
}
""",
    "android/net/Uri.java": """
package android.net;
public final class Uri {
    private final String value;
    private Uri(String value) { this.value = value; }
    public static Uri parse(String value) { return new Uri(value); }
    @Override public String toString() { return value; }
}
""",
    "android/content/pm/ProviderInfo.java": """
package android.content.pm;
public final class ProviderInfo { public String packageName = "io.github.xmy953538104.mybox"; }
""",
    "android/content/pm/ApplicationInfo.java": """
package android.content.pm;
public final class ApplicationInfo { public int uid = android.os.Process.myUid(); }
""",
    "android/content/pm/PackageManager.java": """
package android.content.pm;
public final class PackageManager {
    public static class NameNotFoundException extends Exception { }
    public ProviderInfo provider = new ProviderInfo();
    public int calls;
    public ProviderInfo resolveContentProvider(String authority, int flags) {
        if (!"io.github.xmy953538104.mybox.identity".equals(authority) || flags != 0)
            throw new AssertionError("Unexpected identity discovery");
        calls++; return provider;
    }
}
""",
    "android/content/ContentResolver.java": """
package android.content;
public final class ContentResolver {
    public int calls, originalUid, originalPid;
    public android.os.IBinder expectedLease;
    public String lastMethod;
    public android.os.Bundle response;
    public RuntimeException failure;
    public android.os.Bundle call(android.net.Uri uri, String method, String arg, android.os.Bundle request) {
        calls++;
        lastMethod=method;
        if (!"content://io.github.xmy953538104.mybox.identity".equals(uri.toString())
                || !("resolveCallingIdentity".equals(method) || "resolveBoundMessageIdentity".equals(method)) || arg != null)
            throw new AssertionError("Unexpected identity transport");
        originalUid = request.getInt("originalUid", -1);
        originalPid = request.getInt("originalPid", -1);
        if("resolveBoundMessageIdentity".equals(method) && (originalPid!=0 || request.getInt("leaseVersion",-1)!=1
                || expectedLease==null || expectedLease!=request.getBinder("lease")))
            throw new SecurityException("Simulated lease rejected");
        if (android.os.Binder.getCallingUid() != android.os.Process.myUid()
                || android.os.Binder.getCallingPid() != android.os.Process.myPid())
            throw new AssertionError("Forwarded credentials were not isolated from the querying process");
        if (failure != null) throw failure;
        return response;
    }
}
""",
    "android/content/Context.java": """
package android.content;
public final class Context {
    public static final int CONTEXT_INCLUDE_CODE=1;
    public static int hostContextLoads;
    public final android.content.pm.PackageManager manager = new android.content.pm.PackageManager();
    public final ContentResolver resolver = new ContentResolver();
    private final android.content.pm.ApplicationInfo application = new android.content.pm.ApplicationInfo();
    public android.content.pm.PackageManager getPackageManager() { return manager; }
    public ContentResolver getContentResolver() { return resolver; }
    public android.content.pm.ApplicationInfo getApplicationInfo() { return application; }
    public String getPackageName() { return "com.google.android.gms"; }
    public Context createPackageContext(String pkg,int flags) throws android.content.pm.PackageManager.NameNotFoundException {
        if(!"io.github.xmy953538104.mybox".equals(pkg) || flags!=CONTEXT_INCLUDE_CODE) throw new AssertionError("Unexpected host class loading");
        hostContextLoads++; return this;
    }
    public ClassLoader getClassLoader() { return Context.class.getClassLoader(); }
}
""",
}


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    candidate = Path(java_home, "bin", name + (".exe" if os.name == "nt" else "")) if java_home else None
    if candidate and candidate.is_file():
        return str(candidate)
    found = shutil.which(name)
    if not found:
        raise SystemExit(f"{name} is required")
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Patched MyboxIdentity.java")
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    test = Path(__file__).with_name("IdentitySelfTest.java")
    with tempfile.TemporaryDirectory(prefix="mybox-identity-jvm-") as temporary:
        root = Path(temporary)
        inputs = []
        for relative, text in STUBS.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            inputs.append(str(path))
        classes = root / "classes"
        classes.mkdir()
        subprocess.run([java_tool("javac"), "--release", "8", "-d", str(classes),
                        *inputs, str(source), str(test)], check=True)
        subprocess.run([java_tool("java"), "-ea", "-cp", str(classes),
                        "org.microg.gms.common.IdentitySelfTest"], check=True)
    print("JVM simulated-framework self identity checks passed; this is not Android Binder evidence.")


if __name__ == "__main__":
    main()
