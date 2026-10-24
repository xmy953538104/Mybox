package io.github.xmy953538104.mybox.engine;

import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Run as a dedicated process group: consumer, authenticator service and authenticator UI. */
public final class AccountUiTicketRuntimeTest extends InstrumentationTestCase {
    private static final String CONSUMER = "io.github.xmy953538104.mybox.uiconsumer";
    private static final String AUTH = "io.github.xmy953538104.mybox.uiauth";
    private final ArrayList<Bundle> events = new ArrayList<>();
    private final String run = UUID.randomUUID().toString();
    private Bundle consumer;
    private final Binder observer = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != FIRST_CALL_TRANSACTION) return false;
            Bundle value = data.readBundle(getClass().getClassLoader());
            synchronized (events) { events.add(value); events.notifyAll(); }
            reply.writeNoException(); return true;
        }
    };

    public void testIndependentAccountUiTicketsAndActualFailureRollback() throws Exception {
        InstalledPackage previousAuth = Engine.get().packages().get(AUTH);
        InstalledPackage auth = install(previousAuth != null && previousAuth.versionCode >= 2
                ? "account-ui-authenticator-v2.apk" : "account-ui-authenticator-v1.apk");
        InstalledPackage installedConsumer = install("account-ui-consumer.apk");
        assertFalse(auth.packageName.equals(installedConsumer.packageName));
        assertTrue(auth.appId != installedConsumer.appId);
        // Even another package signed with this same development key must not use the ticket.
        assertEquals(auth.certificateSha256, installedConsumer.certificateSha256);
        Intent initial = new Intent().setComponent(new ComponentName(CONSUMER, CONSUMER + ".ConsumerActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Bundle extras = new Bundle(); extras.putBinder("observer", observer); initial.putExtras(extras);
        Bundle launch = prepare(initial); context().startActivity(launch.getParcelable("outer_intent")); outcome(launch, true);
        consumer = await("consumer", "ready"); await("consumer-resumed", "ready");
        try {
            String interactive = tag("interactive"); request(interactive, true, false, null);
            Bundle created = await("auth-created", interactive), resumed = await("auth-resumed", interactive);
            Bundle result = await("future-result", interactive); await("auth-destroyed", interactive);
            assertEquals(AUTH, resumed.getString("package")); assertEquals(AUTH + ".AuthActivity", resumed.getString("class"));
            assertTrue(consumer.getInt("pid") != resumed.getInt("pid"));
            assertEquals(interactive, result.getString(AccountManager.KEY_ACCOUNT_NAME));
            assertTrue(created.getBoolean("ticketStripped"));
            assertEquals("authenticator:" + interactive, created.getBundle("frozen").getString("value"));

            String manual = tag("detached"); Intent delayed = returned(manual, false);
            assertNotNull(delayed.getExtras().getBinder(ServerAccountUiTickets.EXTRA));
            AtomicInteger substitutedResponseCalls = new AtomicInteger();
            Intent modified = new Intent(delayed); Bundle forgedPayload = new Bundle(); forgedPayload.putString("value", "consumer-modified");
            modified.putExtra("frozen", forgedPayload);
            modified.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response(substitutedResponseCalls));
            command(consumer, "result", manual, modified);
            Bundle frozen = await("auth-created", manual); await("auth-resumed", manual);
            await("auth-completed", manual); await("auth-destroyed", manual);
            Bundle activityResult = await("activity-result", manual);
            assertEquals(73, activityResult.getInt("request")); assertEquals(-1, activityResult.getInt("result"));
            assertEquals("authenticator:" + manual, frozen.getBundle("frozen").getString("value"));
            assertTrue(frozen.getBoolean("ticketStripped")); assertEquals(0, substitutedResponseCalls.get());
            command(consumer, "accounts", manual, null);
            assertTrue(Arrays.asList(await("accounts", manual).getStringArray("names")).contains(manual));
            assertEquals("The closed Future must not deliver a fabricated second result", 0, count("future-result", manual));
            command(consumer, "launch", tag("replay"), delayed); await("error", tag("replay"));
            assertEquals(1, count("auth-created", manual));

            String invalid = tag("invalid"); Intent protectedIntent = returned(invalid, false);
            Intent missing = new Intent(protectedIntent); missing.removeExtra(ServerAccountUiTickets.EXTRA);
            rejected("missing", missing);
            Intent forged = new Intent(protectedIntent); Bundle ticket = forged.getExtras();
            ticket.putBinder(ServerAccountUiTickets.EXTRA, new Binder()); forged.replaceExtras(ticket); rejected("forged", forged);
            rejected("target", new Intent(protectedIntent).setComponent(new ComponentName(AUTH, AUTH + ".OtherActivity")));
            rejected("grant", new Intent(protectedIntent).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            rejected("physical", new Intent(protectedIntent).setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings")));
            assertEquals(0, count("auth-created", invalid));

            String holding = tag("holding"); request(holding, true, true, null);
            Bundle ownerActivity = await("auth-resumed", holding);
            String stolen = tag("stolen"); command(ownerActivity, "launch", stolen, protectedIntent);
            await("auth-error", stolen); assertEquals(0, count("auth-created", invalid));
            command(ownerActivity, "complete", holding, null);
            await("future-result", holding); await("auth-destroyed", holding);
            command(consumer, "launch", invalid, protectedIntent);
            await("auth-completed", invalid); await("auth-destroyed", invalid);

            String single = tag("singleTask"); request(single, false, false, "singleTask");
            await("error", single); assertEquals(0, count("auth-created", single));

            // Disable the actual Android stub, producing a real negative AMS start result.
            // The virtual authenticator Activity stays enabled in its committed APK.
            String retry = tag("retry"); Intent retryIntent = returned(retry, false);
            Bundle prewarm = prepare(new Intent().setComponent(new ComponentName(AUTH, AUTH + ".AuthActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            int slot = prewarm.getInt("slot"); outcome(prewarm, false);
            ComponentName physical = new ComponentName(context(), GuestActivity.class.getName() + "$P" + slot);
            PackageManager physicalPm = context().getPackageManager(); int originalState = physicalPm.getComponentEnabledSetting(physical);
            try {
                physicalPm.setComponentEnabledSetting(physical, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                command(consumer, "launch", tag("physical-failure"), retryIntent);
                Bundle failure = await("error", tag("physical-failure"));
                assertTrue(failure.toString(), failure.getString("failure").contains("ActivityNotFoundException"));
                assertEquals(0, count("auth-created", retry));
            } finally { physicalPm.setComponentEnabledSetting(physical, originalState, PackageManager.DONT_KILL_APP); }
            command(consumer, "launch", retry, retryIntent);
            await("auth-completed", retry); await("auth-destroyed", retry); assertEquals(1, count("auth-created", retry));

            String obsolete = tag("obsolete"); Intent old = returned(obsolete, false);
            awaitNoActivities(AUTH);
            ReservedBoundary ownerBoundary = new ReservedBoundary(installedConsumer, auth);
            InstalledPackage updated = install("account-ui-authenticator-v2.apk");
            assertEquals(auth.certificateSha256, updated.certificateSha256);
            assertFalse(auth.baseApk.equals(updated.baseApk)); assertEquals(2L, updated.versionCode);
            ownerBoundary.assertGenerationRejected();
            rejected("same-signer-new-generation", old); assertEquals(0, count("auth-created", obsolete));
            command(consumer, "finish", tag("finish"), null); await("consumer-destroyed", "ready");
            awaitNoActivities(CONSUMER);
            ReservedBoundary consumerBoundary = new ReservedBoundary(installedConsumer, updated);
            InstalledPackage consumerUpdate = install("account-ui-consumer.apk");
            assertEquals(installedConsumer.versionCode, consumerUpdate.versionCode);
            assertEquals(installedConsumer.certificateSha256, consumerUpdate.certificateSha256);
            assertFalse(installedConsumer.baseApk.equals(consumerUpdate.baseApk));
            consumerBoundary.assertGenerationRejected();
            android.util.Log.i("MyliteAccountUi", "ACCOUNT_UI_TICKET_REPORT=independent-packages,real-callback,detached-return,immutable-payload,one-shot,real-start-rollback,both-reserved-generations PASS");
        } finally {
            ActivityManager manager = (ActivityManager) context().getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                Intent base = task.getTaskInfo().baseIntent;
                if (base != null && base.getType() != null && (base.getType().startsWith(CONSUMER + "/") || base.getType().startsWith(AUTH + "/"))) {
                    task.finishAndRemoveTask();
                }
            }
        }
    }

    private void rejected(String reason, Intent intent) throws Exception {
        String tag = tag(reason); command(consumer, "launch", tag, intent); await("error", tag);
    }
    private Intent returned(String tag, boolean hold) throws Exception {
        request(tag, false, hold, null); Bundle result = await("returned", tag);
        assertEquals(0, count("auth-created", tag)); return result.getParcelable(AccountManager.KEY_INTENT);
    }
    private void request(String tag, boolean interactive, boolean hold, String mode) throws Exception {
        Bundle command = new Bundle(); command.putString("action", "request"); command.putString("tag", tag);
        command.putBoolean("interactive", interactive); command.putBoolean("hold", hold); command.putString("mode", mode); send(consumer, command);
    }
    private void command(Bundle target, String action, String tag, Intent intent) throws Exception {
        Bundle command = new Bundle(); command.putString("action", action); command.putString("tag", tag);
        if (intent != null) command.putParcelable("intent", intent); send(target, command);
    }
    private void send(Bundle target, Bundle command) throws Exception {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeBundle(command); assertTrue(target.getBinder("control").transact(IBinder.FIRST_CALL_TRANSACTION, input, output, 0));
            output.readException();
        } finally { input.recycle(); output.recycle(); }
    }
    private Bundle await(String event, String tag) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        synchronized (events) {
            while (true) {
                for (Bundle value : events) if (tag.equals(value.getString("tag"))) {
                    if (event.equals(value.getString("event"))) return value;
                    if ("error".equals(value.getString("event")) || "auth-error".equals(value.getString("event"))) {
                        fail("Unexpected fixture error while awaiting " + event + ": " + value);
                    }
                }
                long remaining = deadline - SystemClock.elapsedRealtime(); if (remaining <= 0) break;
                events.wait(remaining);
            }
        }
        fail("No " + event + " for " + tag + ": " + events); return null;
    }
    private int count(String event, String tag) {
        synchronized (events) {
            int count = 0; for (Bundle value : events) if (event.equals(value.getString("event")) && tag.equals(value.getString("tag"))) count++;
            return count;
        }
    }
    private void awaitNoActivities(String packageName) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            Bundle request = EngineProtocol.response(); request.putString("operation", "inspect");
            Bundle state = EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, request);
            ArrayList<Bundle> records = state.getParcelableArrayList("records"); boolean found = false;
            for (Bundle record : records) if (packageName.equals(record.getString("package"))) found = true;
            if (!found) return;
            Thread.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Activity lifecycle was not completely acknowledged before its APK update: " + packageName);
    }

    /** Direct consume-boundary regression, using real signed package commits, without crashing a stale guest launch. */
    private static final class ReservedBoundary {
        private final ServerAccountUiTickets.Launch unclaimed, dispatched;
        private final IBinder first = new Binder(), second = new Binder();
        ReservedBoundary(InstalledPackage consumer, InstalledPackage owner) throws Exception {
            unclaimed = frozen(consumer, owner); dispatched = frozen(consumer, owner);
            unclaimed.reserve(first); dispatched.reserve(second); dispatched.dispatched(second);
        }
        void assertGenerationRejected() throws Exception {
            rejected(() -> unclaimed.consume(first));
            rejected(() -> dispatched.consume(second));
            rejected(() -> unclaimed.dispatched(first));
            rejected(() -> unclaimed.cancel(first, true));
            assertTerminal(unclaimed); assertTerminal(dispatched);
        }
        private static ServerAccountUiTickets.Launch frozen(InstalledPackage consumer, InstalledPackage owner) throws Exception {
            Class<?> principal = Class.forName(ServerAccountUiTickets.class.getName() + "$Principal");
            java.lang.reflect.Constructor<?> identity = principal.getDeclaredConstructor(InstalledPackage.class); identity.setAccessible(true);
            Class<?> ticket = Class.forName(ServerAccountUiTickets.class.getName() + "$Ticket");
            java.lang.reflect.Constructor<?> issue = ticket.getDeclaredConstructor(principal, principal, Intent.class); issue.setAccessible(true);
            Object frozen = issue.newInstance(identity.newInstance(consumer), identity.newInstance(owner),
                    new Intent().setComponent(new ComponentName(AUTH, AUTH + ".AuthActivity")));
            java.lang.reflect.Constructor<?> launch = ServerAccountUiTickets.Launch.class.getDeclaredConstructor(ticket, Engine.class);
            launch.setAccessible(true); return (ServerAccountUiTickets.Launch) launch.newInstance(frozen, Engine.get());
        }
        private static void assertTerminal(ServerAccountUiTickets.Launch launch) throws Exception {
            java.lang.reflect.Field field = ServerAccountUiTickets.Launch.class.getDeclaredField("ticket"); field.setAccessible(true);
            Object ticket = field.get(launch); java.lang.reflect.Field state = ticket.getClass().getDeclaredField("state"); state.setAccessible(true);
            assertTrue("A changed installation must permanently revoke its old reservation",
                    ((AccountUiTicketState) state.get(ticket)).terminal(SystemClock.elapsedRealtime()));
        }
        private static void rejected(Runnable operation) {
            try { operation.run(); fail("An installation changed after reserve but the ticket was accepted"); }
            catch (SecurityException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("generation")); }
        }
    }
    private static AccountAuthenticatorResponse response(AtomicInteger calls) {
        Binder trap = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                calls.incrementAndGet(); if (reply != null) reply.writeNoException(); return true;
            }
        };
        Parcel parcel = Parcel.obtain();
        try { parcel.writeStrongBinder(trap); parcel.setDataPosition(0); return AccountAuthenticatorResponse.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }
    private InstalledPackage install(String name) throws Exception {
        File file = new File(context().getCacheDir(), name);
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open(name); FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[16384]; for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
            }
            return Engine.get().install(Collections.singletonList(file));
        } finally { file.delete(); }
    }
    private Context context() { return getInstrumentation().getTargetContext(); }
    private String tag(String name) { return run + ":" + name; }
    private static Bundle prepare(Intent intent) {
        Bundle request = EngineProtocol.response(); request.putString("operation", "prepare"); request.putParcelable("intent", intent);
        return EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, request);
    }
    private static void outcome(Bundle record, boolean accepted) {
        Bundle request = ActivityEnvelope.operation(record, "start_result", null); request.putBoolean("accepted", accepted);
        EngineProtocol.request(Engine.get().server(), EngineProtocol.SERVER, EngineProtocol.HOST_ACTIVITY, request);
    }
}
