package io.github.xmy953538104.mybox.engine;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.MainActivity;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Disposable-emulator test; never reads or saves the clipboard that preceded its synthetic input. */
public final class ClipboardRuntimeTest extends InstrumentationTestCase {
    public static final class ReadCounter {
        int calls;
        public ClipDescription getPrimaryClipDescription(String pkg, String tag, int user, int device) { calls++; return null; }
    }

    public void testRealMetadataRequiresTheOwningFocusedActivity() throws Exception {
        Engine engine = Engine.get();
        // An actual, unattached Android Activity cannot pass local token membership or invoke a read.
        AtomicReference<Activity> unknown = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> unknown.set(new Activity()));
        GuestRuntime unbound = new GuestRuntime(engine);
        assertFalse(unbound.activities().ownsCurrentActivity(unknown.get()));
        ReadCounter counter = new ReadCounter();
        GuestClipboardCalls boundary = new GuestClipboardCalls(counter, "fixture", "host", null, 0, 0, 34,
                () -> unbound.activities().ownsCurrentActivity(unknown.get()), () -> { });
        try {
            Method query = ReadCounter.class.getMethod("getPrimaryClipDescription", String.class, String.class, int.class, int.class);
            assertNull(boundary.invoke(null, query, new Object[]{"fixture", null, 0, 0}));
        } catch (Exception failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError(failure); }
        assertEquals("Unknown Activity reached clipboard backend", 0, counter.calls);

        Context context = getInstrumentation().getTargetContext();
        String name = "io.github.xmy953538104.mybox.fixture";
        InstalledPackage installed = engine.packages().get(name);
        if (installed == null) {
            File apk = new File(context.getCacheDir(), "clipboard-fixture.apk");
            try {
                try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                     FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[16384];
                    for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                }
                installed = engine.install(Collections.singletonList(apk));
            } finally { apk.delete(); }
        }
        File files = new File(installed.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare clipboard fixture");
        String run = UUID.randomUUID().toString();
        String sentinel = "mylite-synthetic-" + UUID.randomUUID();
        JSONObject request = new JSONObject().put("run", run).put("sentinel", sentinel);
        Files.write(new File(files, "clipboard-request.json").toPath(), request.toString().getBytes(StandardCharsets.UTF_8));
        ClipboardManager hostClipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        // A host write is setup, not a guest content-write implementation.
        hostClipboard.setPrimaryClip(ClipData.newPlainText(sentinel, "CI synthetic clipboard fixture"));
        Throwable primary = null;
        Activity hostFront = null;
        String recoveryRun = UUID.randomUUID().toString();
        try {
            FixtureRun.prepare(installed, "clipboard");
            engine.launch(name);
            File done = new File(files, "clipboard-result-" + run + ".json");
            JSONObject foreground = await(new File(files, "clipboard-foreground-" + run + ".json"), done);
            assertEquals(run, foreground.getString("run"));
            for (String check : new String[]{"realSentinel", "hasRealClip", "readRealClip", "writeRealClip", "foregroundFocused", "foreignPackageDenied", "foreignListenerDenied", "forgedCallbackDenied", "rawListenerUnsupported"}) {
                assertTrue(check + ": " + foreground, foreground.getBoolean(check));
            }
            if (Build.VERSION.SDK_INT >= 29) {
                assertTrue("Other virtual user", foreground.getBoolean("otherUserDenied"));
                assertTrue("Listener user", foreground.getBoolean("listenerUserDenied"));
            }
            if (Build.VERSION.SDK_INT >= 34) {
                assertTrue("Foreign device", foreground.getBoolean("otherDeviceDenied"));
                assertTrue("Unmapped attribution", foreground.getBoolean("unmappedAttributionDenied"));
                assertTrue("Listener device", foreground.getBoolean("listenerDeviceDenied"));
                assertTrue("Listener attribution", foreground.getBoolean("listenerTagDenied"));
            }
            hostClipboard.setPrimaryClip(ClipData.newPlainText("listener-first-" + run, "CI synthetic listener first"));
            JSONObject removed = await(new File(files, "clipboard-one-removed-" + run + ".json"), done);
            assertTrue(removed.toString(), removed.getBoolean("realListenerCallbacks"));
            hostClipboard.setPrimaryClip(ClipData.newPlainText("listener-second-" + run, "CI synthetic listener second"));
            JSONObject remaining = await(new File(files, "clipboard-remaining-" + run + ".json"), done);
            assertTrue(remaining.toString(), remaining.getBoolean("removedListenerSilent"));
            assertTrue(remaining.toString(), remaining.getBoolean("remainingListenerWorks"));
            signal(files, "block-main", run);
            await(new File(files, "clipboard-queue-ready-" + run + ".json"), done);
            try {
                hostClipboard.setPrimaryClip(ClipData.newPlainText("listener-queued-" + run, "CI synthetic queued event"));
                await(new File(files, "clipboard-queue-removed-" + run + ".json"), done);
            } finally { signal(files, "release-main", run); }
            JSONObject registered = await(new File(files, "clipboard-reregistered-" + run + ".json"), done);
            assertTrue(registered.toString(), registered.getBoolean("queuedEventInvalidated"));
            assertTrue(registered.toString(), registered.getBoolean("newRegistrationBinder"));
            // A real focused host Activity shares the UID; the guest must not borrow its focus.
            hostFront = getInstrumentation().startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity front = hostFront;
            long focusDeadline = SystemClock.elapsedRealtime() + 5_000;
            java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
            do {
                getInstrumentation().runOnMainSync(() -> focused.set(front.hasWindowFocus()));
                if (!focused.get()) Thread.sleep(20);
            } while (!focused.get() && SystemClock.elapsedRealtime() < focusDeadline);
            assertTrue("Physical host never actually had focus", focused.get());
            signal(files, "background", run);
            await(new File(files, "clipboard-background-ready-" + run + ".json"), done);
            hostClipboard.setPrimaryClip(ClipData.newPlainText("listener-background-" + run, "CI synthetic background event"));
            JSONObject background = await(done, done);
            assertEquals(run, background.getString("run"));
            for (String check : new String[]{"backgroundUnfocused", "backgroundDenied", "backgroundReadDenied", "backgroundWriteDenied",
                    "backgroundRealOsReceipt", "backgroundNotAccepted", "registeredUntilDeath"}) assertTrue(check + ": " + background, background.getBoolean(check));

            // Kill exactly the server-authenticated guest while its real OS listener is still registered.
            Bundle owner = EngineProtocol.response(); owner.putString("package", installed.packageName);
            android.content.pm.ActivityInfo launcher = engine.packages().findLaunchActivity(installed.packageName);
            owner.putString("process", launcher.processName == null ? installed.packageName : launcher.processName);
            IBinder server = engine.server();
            Bundle acquired = EngineProtocol.request(server, EngineProtocol.SERVER, EngineProtocol.ACQUIRE, owner);
            int oldPid = background.getInt("pid");
            assertTrue(oldPid > 0 && oldPid != android.os.Process.myPid());
            assertEquals("Probe PID differs from authenticated guest", oldPid, acquired.getInt("pid"));
            IBinder guest = acquired.getBinder("client"); assertNotNull(guest);
            java.util.concurrent.CountDownLatch death = new java.util.concurrent.CountDownLatch(1);
            guest.linkToDeath(death::countDown, 0);
            android.os.Process.killProcess(oldPid);
            assertTrue("Registered guest did not actually die", death.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue("Guest death killed the server", server.isBinderAlive());
            JSONObject recovery = new JSONObject().put("run", recoveryRun).put("sentinel", sentinel).put("recovery", true);
            Files.write(new File(files, "clipboard-request.json").toPath(), recovery.toString().getBytes(StandardCharsets.UTF_8));
            hostClipboard.setPrimaryClip(ClipData.newPlainText(sentinel, "CI synthetic clipboard fixture"));
            FixtureRun.prepare(installed, "clipboard"); engine.launch(name);
            File recovered = new File(files, "clipboard-result-" + recoveryRun + ".json");
            JSONObject ready = await(new File(files, "clipboard-foreground-" + recoveryRun + ".json"), recovered);
            assertTrue("Guest PID did not change after real death", oldPid != ready.getInt("pid"));
            hostClipboard.setPrimaryClip(ClipData.newPlainText("listener-recovery-" + recoveryRun, "CI synthetic recovered event"));
            JSONObject actual = await(recovered, recovered);
            assertTrue("New process did not receive a real callback", actual.getBoolean("realListenerCallbacks"));
        } catch (Exception | Error failure) {
            primary = failure; throw failure;
        } finally {
            Throwable cleanup = null;
            try { signal(files, "release-main", run); } catch (Throwable failure) { cleanup = failure; }
            for (String nonce : new String[]{run, recoveryRun}) {
                try { signal(files, "abort", nonce); }
                catch (Throwable failure) { if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure); }
            }
            if (hostFront != null) {
                final Activity front = hostFront;
                try { getInstrumentation().runOnMainSync(front::finish); }
                catch (Throwable failure) { if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure); }
            }
            try {
                if (Build.VERSION.SDK_INT >= 28) hostClipboard.clearPrimaryClip();
                else hostClipboard.setPrimaryClip(ClipData.newPlainText("", ""));
            } catch (Throwable failure) { if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure); }
            if (cleanup != null) {
                if (primary != null) primary.addSuppressed(cleanup);
                else throw new AssertionError("Clipboard test cleanup failed", cleanup);
            }
        }
    }

    /** Fault injection verifies ownership after an actual propagated failure; callback success is tested above against the OS. */
    public void testFailedRemoveRetainsTheExactBinderUntilCleanupAndReregister() throws Exception {
        Class<?> listener = Class.forName("android.content.IOnPrimaryClipChangedListener");
        Class<?> contract = Class.forName("android.content.IClipboard");
        android.os.Binder callback = new android.os.Binder();
        Object target = java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                (proxy, method, args) -> { if (method.getName().equals("asBinder")) return callback; throw new AssertionError("No synthetic callback"); });
        callback.attachInterface((android.os.IInterface) target, listener.getName());
        java.util.List<IBinder> added = new java.util.ArrayList<>(), removed = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean failRemove = new java.util.concurrent.atomic.AtomicBoolean(true);
        SecurityException physicalFailure = new SecurityException("Controlled removal failure");
        Object original = java.lang.reflect.Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
            if (method.getName().equals("addPrimaryClipChangedListener")) { added.add(((android.os.IInterface) args[0]).asBinder()); return null; }
            if (method.getName().equals("removePrimaryClipChangedListener")) {
                removed.add(((android.os.IInterface) args[0]).asBinder());
                if (failRemove.getAndSet(false)) throw physicalFailure;
                return null;
            }
            throw new AssertionError("Unexpected backend call");
        });
        GuestClipboardCalls calls = new GuestClipboardCalls(original, "fixture", "physical.host", null, 0, 0,
                Build.VERSION.SDK_INT, () -> true, () -> { });
        Class<?>[] addShape = Build.VERSION.SDK_INT >= 34 ? new Class<?>[]{listener, String.class, String.class, int.class, int.class}
                : Build.VERSION.SDK_INT >= 29 ? new Class<?>[]{listener, String.class, int.class} : new Class<?>[]{listener, String.class};
        Method add = contract.getMethod("addPrimaryClipChangedListener", addShape);
        Method remove = contract.getMethod("removePrimaryClipChangedListener", Build.VERSION.SDK_INT <= 28 ? new Class<?>[]{listener} : addShape);
        Object[] input = Build.VERSION.SDK_INT >= 34 ? new Object[]{target, "fixture", null, 0, 0}
                : Build.VERSION.SDK_INT >= 29 ? new Object[]{target, "fixture", 0} : new Object[]{target, "fixture"};
        Object[] removal = Build.VERSION.SDK_INT <= 28 ? new Object[]{target} : input;
        try {
            calls.invoke(null, add, input); calls.invoke(null, add, input);
            assertSame("Duplicate registration changed its OS Binder", added.get(0), added.get(1));
            try { calls.invoke(null, remove, removal); fail("Physical removal failure was swallowed"); }
            catch (SecurityException expected) { assertSame(physicalFailure, expected); }
            java.util.Map<?, ?> entries = (java.util.Map<?, ?>) RuntimeReflection.field(GuestClipboardCalls.class, "listeners").get(calls);
            assertEquals(1, entries.size());
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean) RuntimeReflection.field(entries.get(callback).getClass(), "active").get(entries.get(callback))).get());
            calls.invoke(null, add, input);
            assertSame("Retry lost the still-registered OS Binder", removed.get(0), removed.get(1));
            assertNotSame("Reregistration revived a stale callback capability", added.get(0), added.get(2));
            calls.invoke(null, remove, removal);
            assertSame(added.get(2), removed.get(2)); assertTrue(entries.isEmpty());
        } catch (Exception failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError(failure); }
    }

    private static void signal(File files, String phase, String run) throws Exception {
        Files.write(new File(files, "clipboard-" + phase + "-" + run).toPath(), new byte[]{1});
    }

    private static JSONObject await(File wanted, File failureReport) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (!wanted.isFile() && SystemClock.elapsedRealtime() < deadline) {
            if (failureReport.isFile()) {
                JSONObject failed = read(failureReport);
                if (failed.has("failure")) throw new AssertionError("Clipboard fixture failure: " + failed.getString("failure"));
            }
            Thread.sleep(20);
        }
        if (!wanted.isFile()) throw new AssertionError("Clipboard fixture timed out");
        JSONObject result = read(wanted);
        assertFalse("Clipboard fixture failure: " + result, result.has("failure"));
        return result;
    }

    private static JSONObject read(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}
