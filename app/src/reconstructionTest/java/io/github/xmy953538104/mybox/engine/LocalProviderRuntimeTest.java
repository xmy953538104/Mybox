package io.github.xmy953538104.mybox.engine;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

public final class LocalProviderRuntimeTest extends InstrumentationTestCase {
    public void testSignedGuestLoadsLaterDependencyAndBothAliasesExactlyOnce() throws Exception {
        Engine engine = Engine.get();
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            File apk = new File(getInstrumentation().getTargetContext().getCacheDir(), "local-providers-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                     FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[16384];
                    for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                }
                installed = engine.install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        io.github.xmy953538104.mybox.FixtureRun.prepare(installed, "providers");
        engine.launch(name);
        File report = new File(installed.dataDir, "files/local-providers.json");
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!report.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertTrue("Guest did not report its Provider initialization", report.isFile());
        JSONObject result = new JSONObject(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
        android.util.Log.i("MyliteProviders", "LOCAL_PROVIDER_REPORT=" + result);
        assertFalse(result.toString(), result.has("failure"));
        assertEquals(name, result.getString("package"));
        assertEquals(GuestProbe.describe(name).getInt("pid"), result.getInt("pid"));
        assertTrue(result.toString(), result.getBoolean("dependencyReady"));
        assertTrue(result.toString(), result.getBoolean("applicationSawReady"));
        assertEquals(result.getString("firstInstance"), result.getString("secondInstance"));
        assertEquals(result.getString("firstInstance"), result.getString("backgroundInstance"));
        for (String count : new String[]{"dependentConstructed", "dependentCreated", "settingsConstructed", "settingsCreated"}) {
            assertEquals(count + ": " + result, 1, result.getInt(count));
        }
        assertEquals("dependent.enter\nsettings.create\ndependent.exit\napplication.create\n", result.getString("events"));
    }

    public void testRealProvidersRejectCycleAndDoNotRetryPartialObjects() throws Exception {
        onMain(() -> {
            cycleACreated = cycleBCreated = 0;
            try (Harness harness = new Harness(CycleA.class, "cycle_a", CycleB.class, "cycle_b")) {
                long start = SystemClock.elapsedRealtime();
                Throwable first = failure(harness.providers::installAll);
                assertTrue(first.toString(), chain(first).contains("Provider initialization cycle"));
                assertTrue(chain(first), chain(first).contains(CycleA.class.getName() + " -> " + CycleB.class.getName() + " -> " + CycleA.class.getName()));
                assertTrue("Cycle waited instead of failing", SystemClock.elapsedRealtime() - start < 2000);
                assertSame(first, failure(() -> harness.acquire("cycle_a")));
                assertEquals(1, cycleACreated);
                assertEquals(1, cycleBCreated);
                assertNull(harness.existing(CycleA.class));
                assertNull(harness.existing(CycleB.class));
            }
        });
    }

    public void testRealProviderRejectsSelfCycle() throws Exception {
        onMain(() -> {
            selfCreated = 0;
            try (Harness harness = new Harness(SelfCycle.class, "self")) {
                Throwable first = failure(harness.providers::installAll);
                assertTrue(chain(first), chain(first).contains(SelfCycle.class.getName() + " -> " + SelfCycle.class.getName()));
                assertSame(first, failure(() -> harness.acquire("self")));
                assertEquals(1, selfCreated);
                assertNull(harness.existing(SelfCycle.class));
            }
        });
    }

    public void testConstructorAndOnCreateFailuresRemainFailures() throws Exception {
        onMain(() -> {
            constructorAttempts = createAttempts = 0;
            try (Harness harness = new Harness(ConstructorFailure.class, "constructor")) {
                Throwable first = failure(harness.providers::installAll);
                assertTrue(chain(first), chain(first).contains("fixture-constructor-failure"));
                assertSame(first, failure(() -> harness.acquire("constructor")));
                assertEquals(1, constructorAttempts);
                assertNull(harness.existing(ConstructorFailure.class));
            }
            try (Harness harness = new Harness(CreateFailure.class, "create")) {
                Throwable first = failure(harness.providers::installAll);
                assertTrue(chain(first), chain(first).contains("fixture-oncreate-failure"));
                assertSame(first, failure(() -> harness.acquire("create")));
                assertEquals(1, createAttempts);
                assertNull(harness.existing(CreateFailure.class));
            }
        });
    }

    public void testWorkerCannotDeadlockMainInitializationAndCanReadWhenReady() throws Exception {
        onMain(() -> {
            workerFailure = null;
            readyCreates = 0;
            try (Harness harness = new Harness(WorkerParent.class, "worker_parent", ReadyProvider.class, "ready")) {
                harness.providers.installAll();
                assertNotNull("Concurrent initialization was silently accepted", workerFailure);
                assertTrue(chain(workerFailure), chain(workerFailure).contains("not ready during main-thread initialization"));
                assertEquals(1, readyCreates);
                Object installed = harness.acquire("ready");
                AtomicReference<Object> fromWorker = new AtomicReference<>();
                AtomicReference<Throwable> error = new AtomicReference<>();
                Thread worker = new Thread(() -> {
                    try {
                        fromWorker.set(harness.acquire("ready"));
                        assertEquals(42, query(harness.context, "ready").getInt("value"));
                    } catch (Throwable failure) { error.set(failure); }
                });
                worker.start(); worker.join(2000);
                assertFalse("Ready lookup blocked the worker", worker.isAlive());
                if (error.get() != null) throw new AssertionError(error.get());
                assertSame(installed, fromWorker.get());
                assertEquals(1, readyCreates);
                ProviderInfo wrong = new ProviderInfo(harness.info("ready"));
                wrong.applicationInfo = new ApplicationInfo(wrong.applicationInfo);
                wrong.applicationInfo.sourceDir += ".other-generation";
                assertTrue(failure(() -> harness.providers.acquire(wrong, wrong.authority, Process.myUid() / 100000)) instanceof SecurityException);
                assertTrue(failure(() -> harness.providers.acquire(harness.info("ready"), wrong.authority, Process.myUid() / 100000 + 1)) instanceof SecurityException);
                ProviderInfo disabled = new ProviderInfo(harness.info("ready"));
                disabled.enabled = false;
                assertTrue(failure(() -> harness.providers.acquire(disabled, disabled.authority, Process.myUid() / 100000)) instanceof SecurityException);
                ProviderInfo otherProcess = new ProviderInfo(harness.info("ready"));
                otherProcess.processName += ":other";
                assertTrue(failure(() -> harness.providers.acquire(otherProcess, otherProcess.authority, Process.myUid() / 100000)) instanceof SecurityException);
                assertTrue(failure(() -> harness.providers.acquire(harness.info("ready"), harness.info("worker_parent").authority,
                        Process.myUid() / 100000)) instanceof SecurityException);
            }
        });
    }

    private interface Action { void run() throws Exception; }
    private void onMain(Action action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Exception) throw (Exception) error.get();
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private static Throwable failure(Action action) {
        try { action.run(); } catch (Throwable failure) { return failure; }
        throw new AssertionError("Provider failure was accepted as success");
    }
    private static String chain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable value = failure; value != null; value = value.getCause()) text.append(value).append('\n');
        return text.toString();
    }

    /** Routes only these test authorities; construction and attachInfo remain actual Android code. */
    private final class Harness implements AutoCloseable {
        final Context context;
        final Object singleton, original;
        final LocalProviders providers;
        final Map<String, ProviderInfo> authorities = new LinkedHashMap<>();
        Harness(Object... declarations) throws Exception {
            context = new ContextWrapper(getInstrumentation().getContext()) {
                @Override public ClassLoader getClassLoader() { return LocalProviderRuntimeTest.class.getClassLoader(); }
            };
            List<ProviderInfo> declared = new ArrayList<>();
            for (int index = 0; index < declarations.length; index += 2) {
                ProviderInfo info = new ProviderInfo();
                info.name = ((Class<?>) declarations[index]).getName();
                info.packageName = context.getPackageName();
                info.processName = info.packageName;
                info.applicationInfo = new ApplicationInfo(context.getApplicationInfo());
                info.applicationInfo.enabled = true;
                info.enabled = true;
                info.authority = authority(context, (String) declarations[index + 1]);
                info.initOrder = declarations.length - index;
                declared.add(info);
                authorities.put(info.authority, info);
            }
            providers = new LocalProviders(context, RuntimeReflection.activityThread(), context.getPackageName(), declared);
            Class<?> manager = Class.forName("android.app.ActivityManager");
            original = RuntimeReflection.invoke(null, RuntimeReflection.method(manager, "getService"));
            singleton = RuntimeReflection.field(manager, "IActivityManagerSingleton").get(null);
            Class<?> contract = Class.forName("android.app.IActivityManager");
            Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (object, method, args) -> {
                if (method.getName().equals("getContentProvider")) {
                    int index = args.length - 3;
                    ProviderInfo info = authorities.get(args[index]);
                    if (info != null) return providers.acquire(info, (String) args[index], ((Number) args[index + 1]).intValue());
                }
                try { return method.invoke(original, args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            RuntimeReflection.set(singleton, "mInstance", proxy);
        }
        ProviderInfo info(String suffix) { return authorities.get(authority(context, suffix)); }
        Object acquire(String suffix) throws Exception {
            ProviderInfo info = info(suffix);
            return providers.acquire(info, info.authority, Process.myUid() / 100000);
        }
        Object existing(Class<?> type) throws Exception {
            Map<?, ?> map = (Map<?, ?>) RuntimeReflection.get(RuntimeReflection.activityThread(), "mLocalProvidersByName");
            return map.get(new android.content.ComponentName(context.getPackageName(), type.getName()));
        }
        @Override public void close() throws Exception { RuntimeReflection.set(singleton, "mInstance", original); }
    }

    private static int cycleACreated, cycleBCreated, selfCreated, constructorAttempts, createAttempts, readyCreates;
    private static volatile Throwable workerFailure;
    private static String authority(Context context, String suffix) { return context.getPackageName() + ".mylite_test." + suffix; }
    private static Bundle query(Context context, String suffix) {
        return context.getContentResolver().call(Uri.parse("content://" + authority(context, suffix)), "identity", null, null);
    }
    public abstract static class Base extends ContentProvider {
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
    public static final class CycleA extends Base {
        @Override public boolean onCreate() { cycleACreated++; LocalProviderRuntimeTest.query(getContext(), "cycle_b"); return true; }
    }
    public static final class CycleB extends Base {
        @Override public boolean onCreate() { cycleBCreated++; LocalProviderRuntimeTest.query(getContext(), "cycle_a"); return true; }
    }
    public static final class SelfCycle extends Base {
        @Override public boolean onCreate() { selfCreated++; LocalProviderRuntimeTest.query(getContext(), "self"); return true; }
    }
    public static final class ConstructorFailure extends Base {
        public ConstructorFailure() { constructorAttempts++; throw new IllegalStateException("fixture-constructor-failure"); }
        @Override public boolean onCreate() { throw new AssertionError("Constructor failure reached onCreate"); }
    }
    public static final class CreateFailure extends Base {
        @Override public boolean onCreate() { createAttempts++; throw new IllegalStateException("fixture-oncreate-failure"); }
    }
    public static final class WorkerParent extends Base {
        @Override public boolean onCreate() {
            Thread worker = new Thread(() -> {
                try { LocalProviderRuntimeTest.query(getContext(), "ready"); }
                catch (Throwable failure) { workerFailure = failure; }
            });
            worker.start();
            try { worker.join(2000); } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            if (worker.isAlive()) throw new AssertionError("Worker/main Provider initialization deadlocked");
            return true;
        }
    }
    public static final class ReadyProvider extends Base {
        @Override public boolean onCreate() { readyCreates++; return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            Bundle result = new Bundle(); result.putInt("value", 42); return result;
        }
    }
}
