package io.github.xmy953538104.mybox.engine;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.UiAutomation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import io.github.xmy953538104.mybox.MyboxApplication;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Real cloud-emulator MCS transport, OS heartbeat and recovery; never claims FCM delivery. */
public final class GoogleMessagingRuntimeTest extends InstrumentationTestCase {
    private static final String GMS = "com.google.android.gms";
    private static final Pattern LOG = Pattern.compile("(?m)^\\s*(\\d+)\\.(\\d{3,9})\\s+(\\d+)\\s+\\d+\\s+[VDIWEF]\\s+"
            + "(GmsGcmMcsSvc|GmsGcmMcsInput|GmsGcmMcsOutput|MyliteGoogle|AndroidRuntime)\\s*:\\s?(.*)$");
    private static final Pattern PHYSICAL_SERVICE = Pattern.compile(
            "io\\.github\\.xmy953538104\\.mybox\\.engine\\.GuestServiceStub\\$P[0-7]S[0-3]");
    private Context context;
    private UiAutomation automation;
    private final JSONObject result = new JSONObject();

    public void testRealMessagingDefaultsHeartbeatNetworkDeathAndExplicitDisable() throws Exception {
        context = getInstrumentation().getTargetContext();
        ((MyboxApplication) context.getApplicationContext()).requireEngine();
        InstalledPackage installed = Engine.get().packages().get(GMS);
        assertNotNull("Pinned source-built microG is required", installed);
        TrustedMicrog.require(context, installed);
        automation = getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo info = automation.getServiceInfo(); int flags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; automation.setServiceInfo(info);
        boolean changedNetwork = false;
        result.put("scope", "Real MCS transport and Play registration only; no authenticated account or FCM delivery");
        try {
            result.put("phase", "initial-connection");
            long initialSince = System.currentTimeMillis() - 600_000;
            Engine.get().prepareGoogleServices();
            result.put("initialization", "Explicit normal host prepare; later death recovery is observed without wake calls");
            int initialPid = awaitLogin(initialSince, -1, 90_000, false);
            result.put("initialLogin", true).put("initialPid", initialPid);
            openMessaging(); awaitToggle(true);
            result.put("defaultEnabled", true);
            waitUntil("Actual Cloud Messaging UI did not report a connected transport", 15_000,
                    () -> hasConnectedText());
            result.put("connectedUi", true);
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_HOME);

            // This query returns only existence, never the registration token or account data.
            File database = new File(installed.dataDir, "databases/gcmstatus");
            long registrationUntil = SystemClock.elapsedRealtime() + 60_000;
            boolean playRegistration;
            do {
                playRegistration = playRegistered(database);
                if (playRegistration) break;
                Thread.sleep(250);
            } while (SystemClock.elapsedRealtime() < registrationUntil);
            result.put("playRegistrationPresent", playRegistration);

            result.put("phase", "os-heartbeat");
            assertHeartbeatAlarm(installed);
            long heartbeatSince = System.currentTimeMillis();
            waitUntil("No actual heartbeat ping followed by server ACK on the same live MCS process", 360_000,
                    () -> heartbeat(heartbeatSince, initialPid));
            result.put("physicalHeartbeatAndAck", true);

            result.put("phase", "network-reconnect");
            assertEquals("Cloud network probe requires airplane mode initially OFF", 0,
                    Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, -1));
            long networkSince = System.currentTimeMillis(); changedNetwork = true;
            shell("cmd connectivity airplane-mode enable");
            waitUntil("Airplane mode did not disconnect the actual emulator network", 20_000, () -> !networkConnected());
            result.put("actualNetworkLost", true);
            Thread.sleep(2_000);
            shell("cmd connectivity airplane-mode disable");
            waitUntil("Actual emulator network did not recover", 30_000, this::networkConnected);
            changedNetwork = false;
            int networkPid = awaitLogin(networkSince, -1, 90_000, false);
            result.put("networkReauthenticated", true).put("networkPid", networkPid);

            result.put("phase", "persistent-process-death");
            assertTrue("MCS PID is not a live, owned physical guest Service", serviceAlive(networkPid));
            long deathSince = System.currentTimeMillis();
            android.os.Process.killProcess(networkPid);
            // Only OS observations follow. No ACQUIRE, DESCRIBE, launch or host prepare can wake a VM.
            int recovered = awaitLogin(deathSince, networkPid, 90_000, true);
            result.put("automaticDeathRecovery", true).put("recoveredPid", recovered);
            assertHeartbeatAlarm(installed);

            result.put("phase", "explicit-disable");
            openMessaging(); awaitToggle(true); click(toggle()); awaitToggle(false);
            waitUntil("Disabling Cloud Messaging did not remove its actual physical Service", 15_000,
                    () -> !serviceAlive(recovered));
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            openMessaging(); awaitToggle(false);
            result.put("explicitOffPersisted", true);
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_HOME);
            long disabledSince = System.currentTimeMillis();
            Engine.get().prepareGoogleServices();
            long until = SystemClock.elapsedRealtime() + 8_000;
            do {
                assertFalse("Normal host bootstrap overrode the explicit user OFF choice", serviceAlive(recovered));
                assertEquals("A server login occurred while the user disabled messaging", 0, loginPid(disabledSince));
                Thread.sleep(200);
            } while (SystemClock.elapsedRealtime() < until);
            openMessaging(); awaitToggle(false);
            result.put("normalBootstrapRespectsOff", true);

            result.put("phase", "explicit-reenable");
            long enabledSince = System.currentTimeMillis(); click(toggle()); awaitToggle(true);
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_HOME);
            int reenabled = awaitLogin(enabledSince, -1, 90_000, false);
            assertHeartbeatAlarm(installed);
            result.put("explicitReenableConnected", true).put("reenabledPid", reenabled).put("phase", "play-registration-verdict");
            assertTrue("Actual Play never received a nonempty registration from the server", playRegistration);
            result.put("phase", "passed");
        } finally {
            try {
                if (changedNetwork) {
                    shell("cmd connectivity airplane-mode disable");
                    result.put("networkRestoreRequested", true);
                }
            } finally {
                AccessibilityServiceInfo restore = automation.getServiceInfo(); restore.flags = flags;
                automation.setServiceInfo(restore);
                Files.write(new File(context.getFilesDir(), "google-messaging-result.json").toPath(),
                        result.toString().getBytes(StandardCharsets.UTF_8));
                System.out.println("MYLITE_GOOGLE_MESSAGING " + result);
            }
        }
    }

    private void assertHeartbeatAlarm(InstalledPackage installed) throws Exception {
        OsPendingStore store = new OsPendingStore(Engine.get()); OsPendingStore.Snapshot found = null;
        for (OsPendingStore.Record record : store.all()) {
            OsPendingStore.Snapshot snapshot = record.snapshot;
            if (snapshot.senderType != ServerPendingIntents.SERVICE || !GMS.equals(snapshot.creator.packageName)
                    || !installed.baseApk.getAbsolutePath().equals(snapshot.creator.generation)
                    || !"org.microg.gms.gcm.mcs.HEARTBEAT".equals(snapshot.intent.getAction())) continue;
            assertNull("Multiple actual MCS heartbeat capabilities", found);
            assertNull("Heartbeat state is still in its journal", record.journal); found = snapshot;
        }
        assertNotNull("No actual MCS Service heartbeat capability", found);
        assertNotNull(found.intent.getComponent());
        assertEquals("org.microg.gms.gcm.McsService", found.intent.getComponent().getClassName());
        PendingIntent actual = PendingIntent.getService(context, found.requestCode, store.identity(found),
                found.flags | PendingIntent.FLAG_NO_CREATE);
        assertNotNull("No real OS MCS Service PendingIntent", actual); assertTrue(actual.isService());
        AlarmRuntimeTest.assertScheduledOsRecord(automation, context, found);
    }

    private int awaitLogin(long since, int excluded, long timeout, boolean automatic) throws Exception {
        final int[] pid = {0};
        waitUntil("No successful server MCS login on a live physical guest Service", timeout, () -> {
            pid[0] = loginPid(since);
            return pid[0] > 0 && pid[0] != excluded && serviceAlive(pid[0])
                    && (!automatic || automaticDispatch(since));
        });
        return pid[0];
    }

    private boolean automaticDispatch(long since) throws Exception {
        Matcher lines = LOG.matcher(logs(since));
        while (lines.find()) if ("MyliteGoogle".equals(lines.group(4))
                && "Normal Google recovery dispatched".equals(lines.group(5).trim())) return true;
        return false;
    }

    private int loginPid(long since) throws Exception {
        int pid = 0; Matcher lines = LOG.matcher(logs(since));
        while (lines.find()) {
            if ("GmsGcmMcsSvc".equals(lines.group(4)) && "Logged in".equals(lines.group(5).trim()))
                pid = Integer.parseInt(lines.group(3));
        }
        return pid;
    }

    private boolean heartbeat(long since, int pid) throws Exception {
        boolean ping = false; Matcher lines = LOG.matcher(logs(since));
        while (lines.find()) {
            if (Integer.parseInt(lines.group(3)) != pid) continue;
            if ("GmsGcmMcsOutput".equals(lines.group(4)) && "Outgoing MCS message tag: 0".equals(lines.group(5).trim())) ping = true;
            if (ping && "GmsGcmMcsInput".equals(lines.group(4))
                    && "Incoming MCS message tag: 1".equals(lines.group(5).trim())) return serviceAlive(pid);
        }
        return false;
    }

    private String logs(long sinceWall) throws Exception {
        String since = String.format(Locale.ROOT, "%d.%03d", sinceWall / 1000, sinceWall % 1000);
        return shell("logcat -b main -d -v threadtime,epoch --uid=" + android.os.Process.myUid() + " -T " + since
                + " GmsGcmMcsSvc:D GmsGcmMcsInput:D GmsGcmMcsOutput:D MyliteGoogle:I AndroidRuntime:E *:S");
    }

    private boolean serviceAlive(int pid) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        assertNotNull("Actual process list unavailable", processes); boolean owned = false;
        for (ActivityManager.RunningAppProcessInfo process : processes)
            if (process.pid == pid && process.uid == android.os.Process.myUid()
                    && process.processName.matches(Pattern.quote(context.getPackageName()) + ":engine_p[0-7]")) owned = true;
        if (!owned) return false;
        List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(100);
        assertNotNull("Actual Service list unavailable", services); assertTrue("Actual Service list truncated", services.size() < 100);
        for (ActivityManager.RunningServiceInfo service : services)
            if (service.pid == pid && service.uid == android.os.Process.myUid() && service.started && service.service != null
                    && context.getPackageName().equals(service.service.getPackageName())
                    && PHYSICAL_SERVICE.matcher(service.service.getClassName()).matches()) return true;
        return false;
    }

    @SuppressWarnings("deprecation") private boolean networkConnected() {
        NetworkInfo network = context.getSystemService(ConnectivityManager.class).getActiveNetworkInfo();
        return network != null && network.isConnected();
    }

    private boolean playRegistered(File database) {
        if (!database.isFile()) return false;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = db.rawQuery("SELECT EXISTS(SELECT 1 FROM registrations WHERE package_name=? AND length(register_id)>0)",
                     new String[]{"com.android.vending"})) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        } catch (RuntimeException unavailable) { return false; }
    }

    private void openMessaging() throws Exception {
        Engine.get().launch(GMS);
        waitUntil("Actual microG Cloud Messaging entry unavailable", 15_000, () -> {
            AccessibilityNodeInfo entry = find("Cloud Messaging", false);
            if (entry == null) return false; click(entry); return true;
        });
        waitUntil("Actual Cloud Messaging switch unavailable", 10_000, () -> {
            AccessibilityNodeInfo toggle = find(null, true);
            if (toggle == null) return false; toggle.recycle(); return true;
        });
    }

    private AccessibilityNodeInfo toggle() {
        AccessibilityNodeInfo node = find(null, true); assertNotNull("Actual messaging switch missing", node); return node;
    }

    private void awaitToggle(boolean expected) throws Exception {
        waitUntil("Actual messaging switch state did not become " + expected, 10_000, () -> {
            AccessibilityNodeInfo node = find(null, true);
            if (node == null) return false;
            try { return node.isEnabled() && node.isChecked() == expected; } finally { node.recycle(); }
        });
    }

    private boolean hasConnectedText() {
        AccessibilityNodeInfo node = find("Connected since ", false);
        if (node == null) return false; node.recycle(); return true;
    }

    private AccessibilityNodeInfo find(String text, boolean switchOnly) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow(); if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>(); nodes.add(root);
        AccessibilityNodeInfo found = null; int visited = 0;
        try {
            while (!nodes.isEmpty()) {
                assertTrue("UI observation exceeded its bounded traversal", ++visited <= 1500);
                AccessibilityNodeInfo node = nodes.removeFirst();
                try {
                    String owner = String.valueOf(node.getPackageName()), id = node.getViewIdResourceName();
                    CharSequence label = node.getText();
                    boolean matches = switchOnly ? node.isCheckable() && id != null && id.endsWith(":id/switch_widget")
                            : label != null && ("Connected since ".equals(text) ? label.toString().startsWith(text) : text.contentEquals(label));
                    if ((context.getPackageName().equals(owner) || GMS.equals(owner)) && node.isVisibleToUser() && matches) {
                        assertNull("Ambiguous actual microG control; no click performed", found); found = AccessibilityNodeInfo.obtain(node);
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i); if (child != null) nodes.addLast(child);
                    }
                } finally { node.recycle(); }
            }
            AccessibilityNodeInfo returned = found; found = null; return returned;
        } finally {
            if (found != null) found.recycle(); while (!nodes.isEmpty()) nodes.removeFirst().recycle();
        }
    }

    private static void click(AccessibilityNodeInfo node) {
        try {
            for (int depth = 0; node != null && depth < 6; depth++) {
                if (node.isVisibleToUser() && node.isEnabled() && node.isClickable()) {
                    assertTrue("Actual microG control rejected its single click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return;
                }
                AccessibilityNodeInfo parent = node.getParent(); node.recycle(); node = parent;
            }
            fail("Actual microG control has no clickable ancestor");
        } finally { if (node != null) node.recycle(); }
    }

    private String shell(String command) throws Exception {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 2 * 1024 * 1024) throw new IllegalStateException("OS observation exceeded its bound");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private interface Observation { boolean observed() throws Exception; }
    private static void waitUntil(String failure, long timeout, Observation observation) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do { if (observation.observed()) return; Thread.sleep(250); } while (SystemClock.elapsedRealtime() < deadline);
        fail(failure);
    }
}
