package io.github.xmy953538104.mybox.engine;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.test.InstrumentationTestCase;
import android.test.mock.MockPackageManager;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Uses the Android IPC signature with a recording OS boundary, without launching external apps. */
public final class ActivityIntentRoutingTest extends InstrumentationTestCase {
    private static final String GUEST = "io.github.xmy953538104.mybox.fixture";

    public void testPhysicalGoogleTargetsNeverReachActivityManager() throws Exception {
        for (String packageName : new String[]{"com.google.android.gms", "com.google.android.gsf", "com.android.vending"}) {
            Harness harness = new Harness();
            harness.pm.results = Collections.singletonList(activity(packageName, packageName + ".LoginActivity"));
            harness.rejected(new Intent("example.LOGIN"), ActivityNotFoundException.class);
            harness.rejected(new Intent("example.LOGIN").setPackage(packageName), ActivityNotFoundException.class);
            harness.rejected(new Intent().setComponent(new ComponentName(packageName, packageName + ".LoginActivity")),
                    EngineException.class);
        }
    }

    public void testImplicitDestinationIsPinnedAndPayloadIsPreserved() throws Exception {
        Harness harness = new Harness();
        ResolveInfo allowed = activity("com.android.settings", "com.android.settings.Settings");
        harness.pm.results = Arrays.asList(activity("com.google.android.gms", "com.google.android.gms.Login"), allowed, allowed);
        Binder payload = new Binder();
        Bundle extras = new Bundle();
        extras.putBinder("response", payload);
        Intent original = new Intent("example.SETTINGS")
                .setDataAndType(Uri.parse("example://settings/entry"), "text/plain")
                .addCategory(Intent.CATEGORY_DEFAULT).putExtras(extras).putExtra("value", 42);
        Object[] arguments = harness.arguments(original);
        arguments[harness.intentIndex + 1] = "text/plain";
        Binder resultTo = new Binder();
        int resultIndex = harness.resultIndex();
        arguments[resultIndex] = resultTo;
        assertEquals(17, ((Number) harness.invoke(arguments)).intValue());
        Intent sent = (Intent) harness.sent[harness.intentIndex];
        assertEquals(new ComponentName(allowed.activityInfo.packageName, allowed.activityInfo.name), sent.getComponent());
        assertEquals(allowed.activityInfo.packageName, sent.getPackage());
        assertEquals(original.getData(), sent.getData());
        assertEquals(original.getType(), sent.getType());
        assertEquals(original.getCategories(), sent.getCategories());
        assertEquals(42, sent.getIntExtra("value", -1));
        assertSame(payload, sent.getExtras().getBinder("response"));
        assertSame(resultTo, harness.sent[resultIndex]);
        assertEquals(getInstrumentation().getTargetContext().getPackageName(), harness.sent[1]);
        assertNull(original.getComponent());
        assertNull(original.getPackage());
        assertEquals(GUEST, arguments[1]);
        assertSame(original, arguments[harness.intentIndex]);
        assertEquals(PackageManager.MATCH_DEFAULT_ONLY, harness.pm.flags);
        assertEquals("text/plain", harness.pm.query.getType());
    }

    public void testAmbiguitySelectorsAndSystemResolverCannotEscape() throws Exception {
        Harness harness = new Harness();
        harness.pm.results = Arrays.asList(activity("com.example.first", "com.example.first.Main"),
                activity("com.example.second", "com.example.second.Main"));
        harness.rejected(new Intent("example.OPEN"), EngineException.class);
        harness.pm.results = Collections.singletonList(activity("android", "com.android.internal.app.ResolverActivity"));
        harness.rejected(new Intent("example.OPEN"), ActivityNotFoundException.class);
        harness.rejected(new Intent().setComponent(new ComponentName("android", "com.android.internal.app.ResolverActivity")),
                EngineException.class);
        harness.pm.results = Collections.singletonList(activity("com.android.settings", "com.android.settings.Settings"));
        Intent selector = new Intent("example.OPEN");
        selector.setSelector(new Intent("example.LOGIN").setPackage("com.google.android.gms"));
        harness.rejected(selector, EngineException.class);
        harness.rejected(Intent.createChooser(new Intent("example.LOGIN").setPackage("com.google.android.gms"), "Choose"),
                EngineException.class);
        harness.pm.results = Collections.emptyList();
        harness.rejected(new Intent("example.UNKNOWN"), ActivityNotFoundException.class);
    }

    public void testExplicitNonGoogleTargetKeepsItsExistingRoute() throws Exception {
        Harness harness = new Harness();
        ComponentName target = new ComponentName("com.android.settings", "com.android.settings.Settings");
        Intent original = new Intent().setComponent(target).putExtra("value", 42);
        harness.invoke(harness.arguments(original));
        Intent sent = (Intent) harness.sent[harness.intentIndex];
        assertEquals(target, sent.getComponent());
        assertEquals(42, sent.getIntExtra("value", -1));
        assertNull(harness.pm.query);
    }

    public void testBulkStartCannotBypassSingleIntentRouting() throws Exception {
        Harness harness = new Harness("startActivities");
        Object[] arguments = harness.arguments(null);
        arguments[harness.intentIndex] = new Intent[]{new Intent("example.LOGIN")};
        try {
            harness.invoke(arguments);
            fail("Bulk launch was delegated without virtual resolution");
        } catch (EngineException expected) {
            assertNull(harness.sent);
        }
    }

    private static ResolveInfo activity(String packageName, String className) {
        ResolveInfo result = new ResolveInfo();
        result.activityInfo = new ActivityInfo();
        result.activityInfo.packageName = packageName;
        result.activityInfo.name = className;
        result.activityInfo.enabled = true;
        result.activityInfo.exported = true;
        result.activityInfo.applicationInfo = new ApplicationInfo();
        result.activityInfo.applicationInfo.packageName = packageName;
        result.activityInfo.applicationInfo.enabled = true;
        return result;
    }

    private static final class RecordingPackageManager extends MockPackageManager {
        List<ResolveInfo> results = Collections.emptyList();
        Intent query;
        int flags;
        @Override public List<ResolveInfo> queryIntentActivities(Intent intent, int requestedFlags) {
            query = new Intent(intent);
            flags = requestedFlags;
            return results;
        }
    }

    private final class Harness {
        final RecordingPackageManager pm = new RecordingPackageManager();
        final Method method;
        final int intentIndex;
        final GuestActivityCalls calls;
        Object[] sent;

        Harness() throws Exception { this("startActivity"); }
        Harness(String methodName) throws Exception {
            Context host = getInstrumentation().getTargetContext();
            File root = new File(host.getCacheDir(), "activity-routing-" + UUID.randomUUID());
            Context isolated = new ContextWrapper(host) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return root; }
                @Override public PackageManager getPackageManager() { return pm; }
            };
            Constructor<Engine> constructor = Engine.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            Engine engine = constructor.newInstance(isolated);
            Class<?> contract = Class.forName(Build.VERSION.SDK_INT >= 29
                    ? "android.app.IActivityTaskManager" : "android.app.IActivityManager");
            Method selected = null;
            for (Method candidate : contract.getMethods()) if (candidate.getName().equals(methodName)) selected = candidate;
            assertNotNull(selected);
            method = selected;
            int found = -1;
            for (int i = 0; i < method.getParameterTypes().length; i++) {
                if (method.getParameterTypes()[i] == Intent.class || method.getParameterTypes()[i] == Intent[].class) found = i;
            }
            assertTrue(found >= 0);
            intentIndex = found;
            Object physical = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, invoked, args) -> {
                if (!invoked.equals(method)) throw new AssertionError("Unexpected physical call: " + invoked.getName());
                sent = args.clone();
                return 17;
            });
            calls = new GuestActivityCalls(engine, engine.runtime(), physical);
        }

        Object[] arguments(Intent intent) {
            Class<?>[] types = method.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) args[i] = 0;
                else if (types[i] == long.class) args[i] = 0L;
                else if (types[i] == boolean.class) args[i] = false;
            }
            args[1] = GUEST;
            args[intentIndex] = intent;
            return args;
        }

        int resultIndex() {
            for (int i = intentIndex + 1; i < method.getParameterTypes().length; i++) {
                if (method.getParameterTypes()[i] == IBinder.class) return i;
            }
            throw new AssertionError("No result token in platform startActivity signature");
        }

        Object invoke(Object[] args) throws Exception {
            try { return calls.invoke(null, method, args); }
            catch (Exception failure) { throw failure; }
            catch (Error failure) { throw failure; }
            catch (Throwable failure) { throw new AssertionError(failure); }
        }

        void rejected(Intent intent, Class<? extends Exception> expected) throws Exception {
            sent = null;
            try {
                invoke(arguments(intent));
                fail("Unsafe Activity Intent reached the physical manager");
            } catch (Exception failure) {
                if (!expected.isInstance(failure)) throw failure;
                assertNull(sent);
            }
        }
    }
}
