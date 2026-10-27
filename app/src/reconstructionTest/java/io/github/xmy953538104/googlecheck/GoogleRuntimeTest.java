package io.github.xmy953538104.googlecheck;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.BundledMicrog;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Properties;

/** Explicitly run only by the workflow that bundles the matching source-built microG artifact. */
public final class GoogleRuntimeTest extends InstrumentationTestCase {
    /** Navigates the real English menus, stopping before any sign-in or account input. */
    public void testSettingsMenusReachLoginIntroductionAndReturn() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        assertEquals("The menu diagnostic requires the English CI locale", "en",
                context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        InstalledPackage installed = BundledMicrog.install();
        android.content.pm.ActivityInfo settings = Engine.get().packages().findLaunchActivity(LOGIN_GMS);
        assertNotNull("The pinned service has no settings launcher", settings);
        assertEquals("The pinned launcher does not resolve to microG Settings", SETTINGS_ACTIVITY,
                settings.targetActivity == null ? settings.name : settings.targetActivity);
        // Server records retain the requested launcher alias, while Android creates its target.
        String settingsRecord = settings.name;
        android.app.UiAutomation automation = getInstrumentation().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
        int originalFlags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        LoginMenuObservation observation = new LoginMenuObservation(context);
        ActivityInspector inspector = null;
        android.os.Bundle login = null;
        try {
            Engine.get().launch(LOGIN_GMS);
            inspector = new ActivityInspector();
            inspector.awaitResumed(settingsRecord, installed);
            assertFalse("A prior login Activity is still open; no menu input was sent", inspector.hasActivity(LOGIN_ACTIVITY));
            android.view.accessibility.AccessibilityNodeInfo accounts = awaitLoginControl(automation, context, "Google Accounts", null);
            try { clickLoginControl(accounts, context); }
            finally { accounts.recycle(); }
            observation.accountsOpened = true;
            android.view.accessibility.AccessibilityNodeInfo add = awaitLoginControl(automation, context, "Add Google account", null);
            try { clickLoginControl(add, context); }
            finally { add.recycle(); }
            observation.addClicked = true;

            login = inspector.awaitResumed(LOGIN_ACTIVITY, installed);
            observation.loginResumed = true;
            android.view.accessibility.AccessibilityNodeInfo next = awaitLoginControl(automation, context, "Sign in", "next_button");
            try { assertTrue("The introduction Sign in control is disabled", next.isEnabled()); }
            finally { next.recycle(); }
            awaitLoginControl(automation, context, context.getString(android.R.string.cancel), "back_button").recycle();
            android.os.Bundle same = inspector.awaitResumed(LOGIN_ACTIVITY, installed);
            assertEquals("Login Activity changed while observing its introduction", login.getLong("record_id"), same.getLong("record_id"));
            assertEquals("Login process changed while observing its introduction", login.getInt("pid"), same.getInt("pid"));
            observation.guestAlive = observation.isAlive(login);
            assertTrue("The resumed login Activity has no matching live physical guest", observation.guestAlive);
            // Pinned LoginActivity.onCreate calls createWebView(), configures it and adds it to
            // auth_root before setting these two buttons. Resumption plus those controls proves
            // this initialization returned; the invisible WebView and its contents are not read.
            observation.webViewInitialized = true;

            observation.cancelAttempted = true;
            cancelLoginIntroduction(automation, context, inspector, login);
            observation.loginClosed = true;
            assertTrue("The original guest died during normal login cancellation", observation.isAlive(login));
            // LoginActivity uses finishAndRemoveTask. Restore only the ordinary launcher after
            // the menu-driven login record has actually disappeared; never launch LoginActivity.
            Engine.get().launch(LOGIN_GMS);
            inspector.awaitResumed(settingsRecord, installed);
            awaitLoginControl(automation, context, "Google Accounts", null).recycle();
            observation.settingsRestored = true;
        } finally {
            if (observation.addClicked && !observation.cancelAttempted && inspector != null) {
                // Best-effort cancellation of only this observed live introduction. Inspection
                // never ACQUIREs a process; failure cleanup does not launch or revive a guest.
                try {
                    if (login == null) login = inspector.resumed(LOGIN_ACTIVITY, installed);
                    if (login != null && observation.isAlive(login)) {
                        observation.cancelAttempted = true;
                        cancelLoginIntroduction(automation, context, inspector, login);
                        observation.loginClosed = true;
                    }
                } catch (Exception | AssertionError ignored) { observation.cleanupFailed = true; }
            }
            observation.cleanupFailed |= observation.cancelAttempted && !observation.loginClosed;
            android.accessibilityservice.AccessibilityServiceInfo restore = automation.getServiceInfo();
            restore.flags = originalFlags;
            automation.setServiceInfo(restore);
            System.out.println("MYLITE_GOOGLE_LOGIN_INTRO " + observation.booleans());
        }
    }

    private static final String LOGIN_GMS = "com.google.android.gms";
    private static final String SETTINGS_ACTIVITY = "org.microg.gms.ui.MainSettingsActivity";
    private static final String LOGIN_ACTIVITY = "org.microg.gms.auth.login.LoginActivity";

    /** Uses the existing authenticated host Activity inspector, never the process ACQUIRE API. */
    static final class ActivityInspector {
        private final android.os.IBinder server;
        private final Method request;
        private final String descriptor;
        private final int operation, version;

        ActivityInspector() throws Exception {
            Class<?> protocol = Class.forName("io.github.xmy953538104.mybox.engine.EngineProtocol");
            Method connection = Engine.class.getDeclaredMethod("server");
            connection.setAccessible(true);
            server = (android.os.IBinder) connection.invoke(Engine.get());
            request = protocol.getDeclaredMethod("request", android.os.IBinder.class, String.class, int.class, android.os.Bundle.class);
            request.setAccessible(true);
            descriptor = (String) constant(protocol, "SERVER");
            operation = (Integer) constant(protocol, "HOST_ACTIVITY");
            version = (Integer) constant(protocol, "VERSION");
        }

        private static Object constant(Class<?> owner, String name) throws Exception {
            java.lang.reflect.Field field = owner.getDeclaredField(name);
            field.setAccessible(true); return field.get(null);
        }

        private java.util.ArrayList<android.os.Bundle> records() throws Exception {
            android.os.Bundle input = new android.os.Bundle();
            input.putInt("protocol", version); input.putString("operation", "inspect");
            android.os.Bundle result;
            try { result = (android.os.Bundle) request.invoke(null, server, descriptor, operation, input); }
            catch (java.lang.reflect.InvocationTargetException failure) {
                throw new AssertionError("Existing Activity inspection failed; diagnostic payload omitted");
            }
            java.util.ArrayList<android.os.Bundle> records = result.getParcelableArrayList("records");
            assertNotNull("Activity inspection returned no records", records);
            return records;
        }

        boolean hasActivity(String activity) throws Exception {
            for (android.os.Bundle record : records()) if (LOGIN_GMS.equals(record.getString("package"))
                    && activity.equals(record.getString("activity"))) return true;
            return false;
        }

        android.os.Bundle resumed(String activity, InstalledPackage installed) throws Exception {
            android.os.Bundle found = null;
            for (android.os.Bundle record : records()) {
                if (!installed.packageName.equals(record.getString("package")) || activity != null && !activity.equals(record.getString("activity"))
                        || !"RESUMED".equals(record.getString("state"))) continue;
                assertNull("Multiple matching resumed Activities; observation is ambiguous", found);
                assertEquals("The observed Activity belongs to another package generation",
                        installed.baseApk.getAbsolutePath(), record.getString("package_generation"));
                assertTrue("The observed Activity identity is incomplete", record.getLong("record_id") > 0
                        && record.getLong("generation") > 0 && record.getInt("pid") > 0 && record.getInt("slot", -1) >= 0);
                found = record;
            }
            return found;
        }

        android.os.Bundle awaitResumed(String activity, InstalledPackage installed) throws Exception {
            long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            do {
                android.os.Bundle record = resumed(activity, installed);
                if (record != null) return record;
                Thread.sleep(100);
            } while (android.os.SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("The expected guest Activity did not acknowledge RESUMED; UI contents omitted");
        }

        void diagnoseActivityWait(InstalledPackage installed) throws Exception {
            android.os.Bundle input = new android.os.Bundle(); input.putInt("protocol", version);
            input.putString("operation", "diagnose_activity_wait"); input.putString("package", installed.packageName);
            input.putString("package_generation", installed.baseApk.getAbsolutePath());
            try { request.invoke(null, server, descriptor, operation, input); }
            catch (java.lang.reflect.InvocationTargetException failure) {
                throw new AssertionError("Authenticated Activity wait diagnosis failed; payload omitted", failure.getCause());
            }
        }

        void awaitGone(long id) throws Exception {
            long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            do {
                boolean present = false;
                for (android.os.Bundle record : records()) present |= record.getLong("record_id") == id;
                if (!present) return;
                Thread.sleep(100);
            } while (android.os.SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("The canceled login Activity record did not disappear");
        }
    }

    private static final class LoginMenuObservation {
        private final Context context;
        boolean accountsOpened, addClicked, loginResumed, webViewInitialized, guestAlive;
        boolean cancelAttempted, loginClosed, settingsRestored, cleanupFailed;
        LoginMenuObservation(Context context) { this.context = context; }

        boolean isAlive(android.os.Bundle record) {
            android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            assertNotNull("Physical process observation is unavailable", processes);
            for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pid == record.getInt("pid")) return process.uid == android.os.Process.myUid()
                        && process.pid != android.os.Process.myPid()
                        && (context.getPackageName() + ":engine_p" + record.getInt("slot")).equals(process.processName);
            }
            return false;
        }

        String booleans() {
            return "accountsOpened=" + accountsOpened + "; addClicked=" + addClicked
                    + "; loginResumed=" + loginResumed + "; webViewInitialized=" + webViewInitialized
                    + "; guestAlive=" + guestAlive + "; cancelAttempted=" + cancelAttempted + "; loginClosed=" + loginClosed
                    + "; settingsRestored=" + settingsRestored + "; cleanupFailed=" + cleanupFailed;
        }
    }

    private static android.view.accessibility.AccessibilityNodeInfo awaitLoginControl(android.app.UiAutomation automation,
            Context context, String text, String id) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        do {
            android.view.accessibility.AccessibilityNodeInfo node = findLoginControl(automation, context, text, id);
            if (node != null) return node;
            Thread.sleep(150);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("The expected visible microG menu or introduction control is absent; UI contents omitted");
    }

    private static android.view.accessibility.AccessibilityNodeInfo findLoginControl(android.app.UiAutomation automation,
            Context context, String text, String id) {
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayDeque<>();
        android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return null;
        nodes.add(root);
        android.view.accessibility.AccessibilityNodeInfo found = null;
        int visited = 0;
        try {
            while (!nodes.isEmpty()) {
                assertTrue("Accessibility observation exceeded its bounded node limit", visited++ < 1500);
                android.view.accessibility.AccessibilityNodeInfo node = nodes.removeFirst();
                try {
                    // Do not inspect WebView text, descendants, URLs or network content.
                    if ("android.webkit.WebView".contentEquals(node.getClassName() == null ? "" : node.getClassName())) continue;
                    String resource = node.getViewIdResourceName();
                    boolean resourceMatches = id == null || (LOGIN_GMS + ":id/" + id).equals(resource)
                            || (context.getPackageName() + ":id/" + id).equals(resource);
                    if (allowedLoginOwner(node, context) && node.isVisibleToUser() && resourceMatches) {
                        CharSequence value = node.getText();
                        if (value != null && (id == null ? text.contentEquals(value) : text.equalsIgnoreCase(value.toString()))) {
                            assertNull("Multiple matching microG controls; no input sent", found);
                            found = android.view.accessibility.AccessibilityNodeInfo.obtain(node);
                        }
                    }
                    for (int index = 0; index < node.getChildCount(); index++) {
                        android.view.accessibility.AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) nodes.addLast(child);
                    }
                } finally { node.recycle(); }
            }
            android.view.accessibility.AccessibilityNodeInfo result = found;
            found = null; return result;
        } finally {
            if (found != null) found.recycle();
            while (!nodes.isEmpty()) nodes.removeFirst().recycle();
        }
    }

    private static boolean allowedLoginOwner(android.view.accessibility.AccessibilityNodeInfo node, Context context) {
        CharSequence owner = node.getPackageName();
        return owner != null && (context.getPackageName().contentEquals(owner) || LOGIN_GMS.contentEquals(owner));
    }

    private static void clickLoginControl(android.view.accessibility.AccessibilityNodeInfo target, Context context) {
        android.view.accessibility.AccessibilityNodeInfo node = android.view.accessibility.AccessibilityNodeInfo.obtain(target);
        try {
            for (int depth = 0; node != null && depth < 6; depth++) {
                assertTrue("Navigation control belongs to another application", allowedLoginOwner(node, context));
                if (node.isVisibleToUser() && node.isEnabled() && node.isClickable()) {
                    assertTrue("The single navigation click was rejected", node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
                    return;
                }
                android.view.accessibility.AccessibilityNodeInfo parent = node.getParent();
                node.recycle(); node = parent;
            }
            throw new AssertionError("The expected navigation control has no clickable ancestor");
        } finally { if (node != null) node.recycle(); }
    }

    private static void cancelLoginIntroduction(android.app.UiAutomation automation, Context context,
            ActivityInspector inspector, android.os.Bundle login) throws Exception {
        android.view.accessibility.AccessibilityNodeInfo cancel = awaitLoginControl(automation, context,
                context.getString(android.R.string.cancel), "back_button");
        try { clickLoginControl(cancel, context); }
        finally { cancel.recycle(); }
        inspector.awaitGone(login.getLong("record_id"));
    }

    public void testPinnedMicrogLocalSelfAndRemoteBinderBoundary() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        BundledMicrog.install();
        java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
        android.os.IBinder[] endpoint = new android.os.IBinder[1];
        android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                endpoint[0] = binder; connected.countDown();
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) { }
        };
        android.content.Intent intent = new android.content.Intent().setClassName(context.getPackageName(),
                "io.github.xmy953538104.mybox.engine.MicrogIdentityProbeService");
        assertTrue("Debug identity helper service was not bound", context.bindService(intent, connection, Context.BIND_AUTO_CREATE));
        try {
            assertTrue("Debug identity helper service did not connect", connected.await(20, java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(endpoint[0]);
            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            android.os.Bundle report;
            try {
                data.writeInterfaceToken("mybox.synthetic.MicrogSelfIdentity");
                assertTrue("Identity helper rejected the test transaction",
                        endpoint[0].transact(android.os.IBinder.FIRST_CALL_TRANSACTION, data, reply, 0));
                reply.readException();
                report = reply.readBundle(getClass().getClassLoader());
            } finally { reply.recycle(); data.recycle(); }
            assertNotNull(report);
            assertNull("Identity helper failed: " + report.getString("failure"), report.getString("failure"));
            for (String field : new String[]{"trustedHelperLoaded", "samePhysicalUid", "differentPhysicalPid",
                    "localRawIdentity", "localSelf", "remoteRegistryRejected", "incomingIdentityRestored", "hostBootstrapDenied"}) {
                assertTrue("Actual pinned helper boundary failed: " + field, report.getBoolean(field));
            }
            assertFalse("Unregistered remote caller returned normally", report.getBoolean("remoteUnexpectedlyReturned"));
            System.out.println("MYLITE_PINNED_MICROG_SELF_IDENTITY trustedHelper=true; localSelf=true; sameUidRemoteRejected=true; identityRestored=true");
        } finally { context.unbindService(connection); }
    }

    @android.annotation.SuppressLint("MissingPermission")
    public void testHostNormalPermissionsReachRealConnectivityAndWakeLock() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE));
        assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(android.Manifest.permission.WAKE_LOCK));
        android.net.ConnectivityManager connectivity = context.getSystemService(android.net.ConnectivityManager.class);
        assertNotNull(connectivity);
        android.net.NetworkInfo network = connectivity.getActiveNetworkInfo();

        android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
        assertNotNull(power);
        String tag = "MyliteHostPowerBoundary";
        android.os.PowerManager.WakeLock lock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, tag);
        lock.setReferenceCounted(false);
        try {
            lock.acquire(30000);
            assertTrue("The real PowerManager did not hold the test lock", lock.isHeld());
            assertTrue("The OS has no wake-lock record for the real host process", hasPhysicalWakeLock(tag));
        } finally {
            if (lock.isHeld()) lock.release();
        }
        assertFalse("The test lock remained held after release", lock.isHeld());
        assertFalse("The OS retained the released test wake lock", hasPhysicalWakeLock(tag));
        System.out.println("MYLITE_HOST_NORMAL_PERMISSIONS connectivityQuery=true; networkPresent=" + (network != null)
                + "; networkConnected=" + (network != null && network.isConnected())
                + "; wakeLockOsRecord=true; wakeLockReleased=true");
    }

    private boolean hasPhysicalWakeLock(String tag) throws Exception {
        StringBuilder dump = new StringBuilder();
        try (java.io.Reader reader = new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                getInstrumentation().getUiAutomation().executeShellCommand("dumpsys -t 5 power")), java.nio.charset.StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                assertTrue("Power diagnostics exceeded the bounded test buffer", dump.length() + count <= 1048576);
                dump.append(buffer, 0, count);
            }
        }
        assertTrue("The OS did not return power diagnostics", dump.indexOf("POWER MANAGER (dumpsys power)") >= 0);
        assertTrue("The OS did not return its wake-lock records", dump.indexOf("Wake Locks: size=") >= 0);
        for (String line : dump.toString().split("\n")) {
            if (line.trim().startsWith("PARTIAL_WAKE_LOCK") && line.contains("'" + tag + "'")) {
                assertTrue("The test wake lock has an unexpected physical owner",
                        line.contains("(uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid() + ")"));
                return true;
            }
        }
        return false;
    }

    public void testPinnedMicrogSignatureViewDoesNotReplaceRealSigner() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Properties pins = new Properties();
        try (InputStream source = context.getAssets().open("mybox/trusted-microg.properties")) { pins.load(source); }
        InstalledPackage installed = BundledMicrog.install();
        assertEquals(Collections.singletonList(pins.getProperty("gms.cert.sha256")), installed.certificateSha256);
        int flags = PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo real = Engine.get().packages().getPackageInfo(installed.packageName, flags);
        assertEquals(pins.getProperty("gms.cert.sha256"), hash("SHA-256", real.signatures[0]));
        Class<?> view = Class.forName("io.github.xmy953538104.mybox.engine.GoogleSignatureView");
        Method apply = view.getDeclaredMethod("apply", Engine.class, PackageInfo.class, int.class);
        apply.setAccessible(true);
        PackageInfo compatible = (PackageInfo) apply.invoke(null, Engine.get(), real, flags);
        String google = "38918a453d07199354f8b19af05ec6562ced5788";
        assertEquals(google, hash("SHA-1", compatible.signatures[0]));
        assertEquals(google, hash("SHA-1", compatible.signingInfo.getApkContentsSigners()[0]));
        assertEquals(google, hash("SHA-1", compatible.signingInfo.getSigningCertificateHistory()[0]));
        Class<?> queryType = Class.forName("io.github.xmy953538104.mybox.engine.GuestPackageQueries");
        java.lang.reflect.Constructor<?> queryConstructor = queryType.getDeclaredConstructor(Engine.class, Object.class);
        queryConstructor.setAccessible(true);
        Object query = queryConstructor.newInstance(Engine.get(), null);
        Method dispatch = queryType.getMethod("invoke", Object.class, Method.class, Object[].class); dispatch.setAccessible(true);
        Method inventoryMethod = java.util.Arrays.stream(Class.forName("android.content.pm.IPackageManager").getMethods())
                .filter(method -> method.getName().equals("getInstalledPackages")).findFirst().orElseThrow(NoSuchMethodException::new);
        Object inventoryFlags = inventoryMethod.getParameterTypes()[0] == long.class ? (Object) Long.valueOf(flags) : Integer.valueOf(flags);
        Object inventory = dispatch.invoke(query, null, inventoryMethod, new Object[]{inventoryFlags, 0});
        java.util.List<?> packages = inventory instanceof java.util.List<?> ? (java.util.List<?>) inventory
                : (java.util.List<?>) inventory.getClass().getMethod("getList").invoke(inventory);
        int matches = 0;
        for (Object entry : packages) {
            PackageInfo listed = (PackageInfo) entry;
            if (!installed.packageName.equals(listed.packageName)) continue;
            matches++;
            assertEquals(google, hash("SHA-1", listed.signatures[0]));
            assertEquals(google, hash("SHA-1", listed.signingInfo.getApkContentsSigners()[0]));
            assertEquals(installed.baseApk.getPath(), listed.applicationInfo.sourceDir);
        }
        assertEquals("Approved microG is absent or duplicated in virtual inventory", 1, matches);
        PackageInfo reloaded = Engine.get().packages().getPackageInfo(installed.packageName, flags);
        assertEquals(pins.getProperty("gms.cert.sha256"), hash("SHA-256", reloaded.signatures[0]));
        assertEquals(pins.getProperty("gms.cert.sha256"), hash("SHA-256", reloaded.signingInfo.getApkContentsSigners()[0]));
        assertEquals(context.getApplicationInfo().uid, compatible.applicationInfo.uid);
        System.out.println("MYBOX_PINNED_MICROG_SIGNATURE_VIEW=passed; Google network/account/application behavior remains a separate check");
    }

    public void testSourceBuiltMicrogSettingsActuallyResumes() throws Exception {
        BundledMicrog.install();
        Engine.get().launch("com.google.android.gms");
        long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
        boolean visible = false;
        String hostPackage = getInstrumentation().getTargetContext().getPackageName();
        String lastWindow = "No active accessibility window";
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayDeque<>();
                nodes.add(root);
                int count = 0;
                StringBuilder window = new StringBuilder();
                while (!nodes.isEmpty() && count++ < 1000) {
                    android.view.accessibility.AccessibilityNodeInfo node = nodes.removeFirst();
                    CharSequence text = node.getText();
                    if (text != null && window.length() < 3000) {
                        window.append(node.getPackageName()).append(':').append(text).append('\n');
                    }
                    if (hostPackage.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                            && text != null && "microG Settings".equalsIgnoreCase(text.toString())) visible = true;
                    for (int i = 0; i < node.getChildCount(); i++) {
                        android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) nodes.add(child);
                    }
                    node.recycle();
                }
                while (!nodes.isEmpty()) nodes.removeFirst().recycle();
                lastWindow = window.toString();
            }
            if (visible) break;
            Thread.sleep(300);
        }
        android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
        if (screenshot != null) {
            try (java.io.FileOutputStream output = getInstrumentation().getTargetContext().openFileOutput("ci-microg-settings.png", Context.MODE_PRIVATE)) {
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
            } finally { screenshot.recycle(); }
        }
        assertTrue("The pinned microG Settings UI did not become visible in the reconstructed guest. Active window: " + lastWindow, visible);
        android.os.Bundle guest = io.github.xmy953538104.mybox.engine.GuestProbe.describe("com.google.android.gms");
        assertEquals("com.google.android.gms", guest.getString("package"));
        assertEquals("BOUND", guest.getString("state"));
        assertEquals("com.google.android.gms", guest.getString("resumed_package"));
        assertEquals("org.microg.gms.ui.MainSettingsActivity", guest.getString("resumed_activity"));
        assertTrue(guest.getInt("pid") != android.os.Process.myPid());
        android.util.Log.i("MyboxFixture", "MYBOX_PINNED_MICROG_RUNTIME=" + guest);
        android.util.Log.i("MyboxFixture", "MYBOX_PINNED_MICROG_SETTINGS_VISIBLE=passed; real Google login/download/push remain separate checks");
    }

    private static String hash(String algorithm, Signature signature) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance(algorithm).digest(signature.toByteArray())) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
