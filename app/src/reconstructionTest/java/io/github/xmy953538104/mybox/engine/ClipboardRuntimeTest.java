package io.github.xmy953538104.mybox.engine;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
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
        try {
            FixtureRun.prepare(installed, "clipboard");
            engine.launch(name);
            File done = new File(files, "clipboard-result-" + run + ".json");
            JSONObject foreground = await(new File(files, "clipboard-foreground-" + run + ".json"), done);
            assertEquals(run, foreground.getString("run"));
            for (String check : new String[]{"realSentinel", "hasRealClip", "readRealClip", "writeRealClip", "foregroundFocused", "listenerRoundTrip", "foreignPackageDenied"}) {
                assertTrue(check + ": " + foreground, foreground.getBoolean(check));
            }
            if (Build.VERSION.SDK_INT >= 29) assertTrue("Other virtual user", foreground.getBoolean("otherUserDenied"));
            if (Build.VERSION.SDK_INT >= 34) {
                assertTrue("Foreign device", foreground.getBoolean("otherDeviceDenied"));
                assertTrue("Unmapped attribution", foreground.getBoolean("unmappedAttributionDenied"));
            }
            // This physical host Activity has the same UID. The guest must not borrow its focus.
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Files.write(new File(files, "clipboard-background-" + run).toPath(), new byte[]{1});
            JSONObject background = await(done, done);
            assertEquals(run, background.getString("run"));
            assertTrue("Guest still focused", background.getBoolean("backgroundUnfocused"));
            assertTrue("Background guest borrowed host UID focus", background.getBoolean("backgroundDenied"));
            assertTrue(background.toString(), background.getBoolean("backgroundReadDenied"));
            assertTrue(background.toString(), background.getBoolean("backgroundWriteDenied"));
        } finally {
            if (Build.VERSION.SDK_INT >= 28) hostClipboard.clearPrimaryClip();
            else hostClipboard.setPrimaryClip(ClipData.newPlainText("", ""));
        }
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
