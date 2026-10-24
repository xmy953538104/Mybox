package io.github.xmy953538104.googlecheck;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.UiAutomation;
import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import io.github.xmy953538104.mybox.MyboxApplication;
import io.github.xmy953538104.mybox.engine.BundledMicrog;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;

/**
 * Manual, network-dependent diagnostic. Run this class explicitly on a fresh English CI fixture.
 * It is outside the core test package and is not called by GoogleRuntimeTest. No retries, preference
 * writes, direct CheckinManager calls, screenshots, UI dumps or credential values are produced.
 */
public final class GoogleRegistrationRuntimeTest extends InstrumentationTestCase {
    private static final String GMS = "com.google.android.gms";
    private static final String REGISTRATION_TITLE = "Google device registration";
    private static final String SWITCH_TITLE = "Register device";
    // microG 32bc895: play-services-base/core/src/main/kotlin/org/microg/gms/settings/
    // SettingsContract.kt, CheckIn (lines 102-117). SettingsProvider writes these three as long.
    private static final String ANDROID_ID = "androidId";
    private static final String SECURITY_TOKEN = "securityToken";
    private static final String LAST_CHECKIN = "lastCheckin";
    private static final long REGISTRATION_WAIT_MS = 90_000;
    private final UiDiagnostics uiDiagnostics = new UiDiagnostics();

    public void testSingleUiEnablePersistsRealRegistration() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ((MyboxApplication) context.getApplicationContext()).requireEngine();
        assertEquals("This manual UI diagnostic requires the English CI locale", "en",
                context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        InstalledPackage installed = BundledMicrog.install();
        assertEquals(GMS, installed.packageName);
        File preferences = new File(installed.dataDir, "shared_prefs/checkin.xml");
        UiAutomation automation = getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo originalInfo = automation.getServiceInfo();
        int originalFlags = originalInfo.flags;
        originalInfo.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(originalInfo);
        boolean fresh = false;
        boolean menuOpened = false;
        boolean enableClicked = false;
        boolean switchEnabled = false;
        Registration state = Registration.missing();
        try {
            state = readRegistration(preferences);
            assertTrue("Existing checkin preferences could not be parsed safely", !state.exists || state.parsed);
            fresh = !state.androidId && !state.securityToken && !state.lastCheckin;
            assertTrue("Requires a fresh fixture with all three fields zero or absent; this probe never clears registration", fresh);

            Engine.get().launch(GMS);
            waitForText(automation, context.getPackageName(), "microG Settings", 30_000).recycle();
            AccessibilityNodeInfo registration = waitForText(automation, context.getPackageName(), REGISTRATION_TITLE, 15_000);
            try { clickOnce(registration); }
            finally { registration.recycle(); }
            waitForText(automation, context.getPackageName(), SWITCH_TITLE, 15_000).recycle();
            menuOpened = true;

            AccessibilityNodeInfo toggle = waitForSwitch(automation, context.getPackageName(), 10_000);
            try {
                assertFalse("Requires the real registration switch initially OFF; no disable/retry sequence is performed", toggle.isChecked());
                assertTrue("Registration switch is disabled", toggle.isEnabled());
                clickOnce(toggle);
                enableClicked = true;
            } finally { toggle.recycle(); }

            long toggleDeadline = SystemClock.elapsedRealtime() + 10_000;
            while (SystemClock.elapsedRealtime() < toggleDeadline) {
                AccessibilityNodeInfo current = findSwitch(automation, context.getPackageName());
                if (current != null) {
                    try { switchEnabled = current.isChecked(); }
                    finally { current.recycle(); }
                }
                if (switchEnabled) break;
                Thread.sleep(200);
            }
            assertTrue("The single UI click did not turn the real registration switch ON", switchEnabled);

            long deadline = SystemClock.elapsedRealtime() + REGISTRATION_WAIT_MS;
            do {
                state = readRegistration(preferences);
                if (state.complete()) break;
                Thread.sleep(250);
            } while (SystemClock.elapsedRealtime() < deadline);
            assertTrue("The UI-triggered flow did not persist three nonzero registration fields; "
                    + "no retry or state modification was performed. " + state.booleans(), state.complete());
        } finally {
            // XML can be mid-write while SharedPreferences.apply() completes. Only observations
            // are polled; the registration action is never repeated. Never print a parser cause.
            if (!state.complete()) state = readRegistration(preferences);
            System.out.println("MYLITE_GOOGLE_REGISTRATION fresh=" + fresh + "; menuOpened=" + menuOpened
                    + "; enableClicked=" + enableClicked + "; switchEnabled=" + switchEnabled
                    + "; " + state.booleans());
            System.out.println("MYLITE_REGISTRATION_UI " + uiDiagnostics.booleans());
            reportRunningProcesses(context);
            AccessibilityServiceInfo restore = automation.getServiceInfo();
            restore.flags = originalFlags;
            automation.setServiceInfo(restore);
        }
    }

    private AccessibilityNodeInfo waitForText(UiAutomation automation, String host, String wanted,
                                                    long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            AccessibilityNodeInfo found = find(automation, host, wanted, false);
            if (found != null) return found;
            Thread.sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Expected English microG navigation element did not appear; UI contents omitted");
    }

    private AccessibilityNodeInfo waitForSwitch(UiAutomation automation, String host, long timeoutMs)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            AccessibilityNodeInfo found = findSwitch(automation, host);
            if (found != null) return found;
            Thread.sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("The checkable microG registration switch did not appear; UI contents omitted");
    }

    private AccessibilityNodeInfo findSwitch(UiAutomation automation, String host) {
        return find(automation, host, null, true);
    }

    private AccessibilityNodeInfo find(UiAutomation automation, String host, String wanted,
                                               boolean switchOnly) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        uiDiagnostics.root(root, host);
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        AccessibilityNodeInfo found = null;
        int matches = 0;
        int visited = 0;
        try {
            while (!nodes.isEmpty() && visited++ < 1500) {
                AccessibilityNodeInfo node = nodes.removeFirst();
                try {
                    CharSequence owner = node.getPackageName();
                    boolean allowed = owner != null && (host.contentEquals(owner) || GMS.contentEquals(owner));
                    String id = node.getViewIdResourceName();
                    CharSequence text = node.getText();
                    uiDiagnostics.node(node, host, allowed);
                    boolean match = switchOnly
                            ? node.isCheckable() && id != null && id.endsWith(":id/switch_widget")
                            : text != null && wanted.contentEquals(text);
                    if (allowed && node.isVisibleToUser() && match) {
                        matches++;
                        if (found == null) found = AccessibilityNodeInfo.obtain(node);
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) nodes.addLast(child);
                    }
                } finally { node.recycle(); }
            }
            if (!nodes.isEmpty()) uiDiagnostics.traversalCapped = true;
            if (switchOnly && matches > 1) throw new AssertionError("Registration page has multiple switch candidates; no click performed");
            AccessibilityNodeInfo result = found;
            found = null;
            return result;
        } finally {
            if (found != null) found.recycle();
            while (!nodes.isEmpty()) nodes.removeFirst().recycle();
        }
    }

    private static void reportRunningProcesses(Context context) {
        boolean querySucceeded = false, serverPresent = false, guestPresent = false, guestVisible = false;
        try {
            // This is an OS observation in the host test process. GuestProbe.describe() performs
            // ACQUIRE and must not be used here: a failure diagnostic must never launch a guest.
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            querySucceeded = processes != null;
            if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.uid != android.os.Process.myUid()) continue;
                if ((context.getPackageName() + ":engine_server").equals(process.processName)) serverPresent = true;
                String guestPrefix = context.getPackageName() + ":engine_p";
                if (process.processName != null && process.processName.startsWith(guestPrefix)) {
                    guestPresent = true;
                    guestVisible |= process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                }
            }
        } catch (RuntimeException unavailable) { /* Report only the boolean; do not replace the test failure. */ }
        System.out.println("MYLITE_REGISTRATION_PROCESSES querySucceeded=" + querySucceeded
                + "; serverPresent=" + serverPresent + "; guestPresent=" + guestPresent
                + "; guestVisibleImportance=" + guestVisible);
    }

    /** Fixed comparisons only: no node text, descriptions, package strings, bounds or IDs are retained. */
    private static final class UiDiagnostics {
        boolean rootEverPresent, lastRootPresent, lastRootHost, lastRootGms, lastRootOther;
        boolean hostNodeSeen, gmsNodeSeen, allowedVisibleNodeSeen, viewIdSeen, switchIdSeen;
        boolean titleExactSeen, titleCaseFoldSeen, titleDescriptionSeen;
        boolean titleAllowedSeen, titleVisibleSeen, titleAllowedVisibleSeen;
        boolean menuSeen, menuAllowedVisibleSeen, traversalCapped;
        void root(AccessibilityNodeInfo node, String host) {
            lastRootPresent = node != null;
            rootEverPresent |= lastRootPresent;
            CharSequence owner = node == null ? null : node.getPackageName();
            lastRootHost = owner != null && host.contentEquals(owner);
            lastRootGms = owner != null && GMS.contentEquals(owner);
            lastRootOther = owner != null && !lastRootHost && !lastRootGms;
        }
        void node(AccessibilityNodeInfo node, String host, boolean allowed) {
            CharSequence owner = node.getPackageName();
            hostNodeSeen |= owner != null && host.contentEquals(owner);
            gmsNodeSeen |= owner != null && GMS.contentEquals(owner);
            boolean visible = node.isVisibleToUser();
            allowedVisibleNodeSeen |= allowed && visible;
            String id = node.getViewIdResourceName();
            viewIdSeen |= id != null;
            switchIdSeen |= id != null && id.endsWith(":id/switch_widget");
            CharSequence text = node.getText();
            boolean title = text != null && "microG Settings".contentEquals(text);
            titleExactSeen |= title;
            titleCaseFoldSeen |= text != null && "microG Settings".equalsIgnoreCase(text.toString());
            titleAllowedSeen |= title && allowed;
            titleVisibleSeen |= title && visible;
            titleAllowedVisibleSeen |= title && allowed && visible;
            CharSequence description = node.getContentDescription();
            titleDescriptionSeen |= description != null && "microG Settings".contentEquals(description);
            boolean menu = text != null && REGISTRATION_TITLE.contentEquals(text);
            menuSeen |= menu;
            menuAllowedVisibleSeen |= menu && allowed && visible;
        }
        String booleans() {
            return "rootEverPresent=" + rootEverPresent + "; lastRootPresent=" + lastRootPresent
                    + "; lastRootHost=" + lastRootHost + "; lastRootGms=" + lastRootGms + "; lastRootOther=" + lastRootOther
                    + "; hostNodeSeen=" + hostNodeSeen + "; gmsNodeSeen=" + gmsNodeSeen
                    + "; allowedVisibleNodeSeen=" + allowedVisibleNodeSeen + "; viewIdSeen=" + viewIdSeen
                    + "; switchIdSeen=" + switchIdSeen + "; titleExactSeen=" + titleExactSeen
                    + "; titleCaseFoldSeen=" + titleCaseFoldSeen + "; titleDescriptionSeen=" + titleDescriptionSeen
                    + "; titleAllowedSeen=" + titleAllowedSeen + "; titleVisibleSeen=" + titleVisibleSeen
                    + "; titleAllowedVisibleSeen=" + titleAllowedVisibleSeen + "; menuSeen=" + menuSeen
                    + "; menuAllowedVisibleSeen=" + menuAllowedVisibleSeen + "; traversalCapped=" + traversalCapped;
        }
    }

    private static void clickOnce(AccessibilityNodeInfo target) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(target);
        try {
            for (int depth = 0; node != null && depth < 6; depth++) {
                if (node.isVisibleToUser() && node.isEnabled() && node.isClickable()) {
                    assertTrue("The single accessibility click was rejected",
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                    return;
                }
                AccessibilityNodeInfo parent = node.getParent();
                node.recycle();
                node = parent;
            }
            throw new AssertionError("Expected microG control has no clickable ancestor; no input injected");
        } finally { if (node != null) node.recycle(); }
    }

    private static Registration readRegistration(File file) {
        if (!file.exists()) return Registration.missing();
        // Read only the actual installed package's private file. No ContentResolver or microG API
        // is invoked here, and even invalid XML/number exception messages could contain a secret.
        if (!file.isFile() || file.length() > 256 * 1024) return Registration.invalid();
        try (InputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            boolean map = false;
            int seen = 0;
            boolean androidId = false, securityToken = false, lastCheckin = false;
            for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event != XmlPullParser.START_TAG) continue;
                if (parser.getDepth() == 1) {
                    if (!"map".equals(parser.getName())) return Registration.invalid();
                    map = true;
                } else if (parser.getDepth() == 2) {
                    String key = parser.getAttributeValue(null, "name");
                    int bit = ANDROID_ID.equals(key) ? 1 : SECURITY_TOKEN.equals(key) ? 2 : LAST_CHECKIN.equals(key) ? 4 : 0;
                    if (bit == 0) continue;
                    if (!"long".equals(parser.getName()) || (seen & bit) != 0) return Registration.invalid();
                    boolean nonzero = Long.parseLong(parser.getAttributeValue(null, "value")) != 0;
                    if (bit == 1) androidId = nonzero;
                    else if (bit == 2) securityToken = nonzero;
                    else lastCheckin = nonzero;
                    seen |= bit;
                }
            }
            return map ? new Registration(true, true, androidId, securityToken, lastCheckin) : Registration.invalid();
        } catch (Exception unreadable) {
            return Registration.invalid(); // Deliberately no raw message, cause, XML or credential output.
        }
    }

    private static final class Registration {
        final boolean exists, parsed, androidId, securityToken, lastCheckin;
        Registration(boolean exists, boolean parsed, boolean androidId, boolean securityToken, boolean lastCheckin) {
            this.exists = exists; this.parsed = parsed; this.androidId = androidId;
            this.securityToken = securityToken; this.lastCheckin = lastCheckin;
        }
        static Registration missing() { return new Registration(false, false, false, false, false); }
        static Registration invalid() { return new Registration(true, false, false, false, false); }
        boolean complete() { return parsed && androidId && securityToken && lastCheckin; }
        String booleans() {
            return "prefsPresent=" + exists + "; prefsParsed=" + parsed + "; androidIdNonzero=" + androidId
                    + "; securityTokenNonzero=" + securityToken + "; lastCheckinNonzero=" + lastCheckin;
        }
    }
}
