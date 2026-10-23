// SPDX-License-Identifier: Apache-2.0
package io.github.xmy953538104.mybox.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.CrossProfileApps;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Exercises framework and Binder entry points from a real bound guest process. */
public final class CrossProfileProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        JSONObject report = new JSONObject();
        try {
            CrossProfileApps manager = getSystemService(CrossProfileApps.class);
            require(manager != null, "CrossProfileApps unavailable");
            require(manager.getTargetUserProfiles().isEmpty(), "Guest exposed an Android profile");
            require(getApplicationContext().getSystemService(CrossProfileApps.class)
                    .getTargetUserProfiles().isEmpty(), "Application context exposed an Android profile");
            if (Build.VERSION.SDK_INT >= 30) {
                require(!manager.canInteractAcrossProfiles(), "Unexpected cross-profile capability");
                require(!manager.canRequestInteractAcrossProfiles(), "Unexpected profile consent request");
                try { manager.createRequestInteractAcrossProfilesIntent(); }
                catch (SecurityException expected) { report.put("consentRequestDenied", true); }
                require(report.optBoolean("consentRequestDenied"), "Consent request was accepted");
            }
            try { manager.startMainActivity(new ComponentName(this, getClass()), UserHandle.getUserHandleForUid(1000000)); }
            catch (SecurityException expected) { report.put("publicLaunchDenied", true); }
            require(report.optBoolean("publicLaunchDenied"), "Public cross-profile launch was accepted");

            Field field = CrossProfileApps.class.getDeclaredField("mService");
            field.setAccessible(true);
            Object service = field.get(manager);
            int user = (Integer) Context.class.getMethod("getUserId").invoke(this);
            int queries = 0, deniedMutations = 0;
            for (Method method : Class.forName("android.content.pm.ICrossProfileApps").getMethods()) {
                String name = method.getName();
                if (name.equals("getTargetUserProfiles") || name.equals("canInteractAcrossProfiles")
                        || name.equals("canRequestInteractAcrossProfiles")) {
                    requireDenied(method, service, "android");
                    queries++;
                } else if (name.equals("canConfigureInteractAcrossProfiles")
                        || name.equals("canUserAttemptToConfigureInteractAcrossProfiles")) {
                    if (method.getParameterTypes().length == 1) {
                        require(Boolean.FALSE.equals(method.invoke(service, getPackageName())), name);
                        requireDenied(method, service, "android");
                    } else {
                        require(Boolean.FALSE.equals(method.invoke(service, user, getPackageName())), name);
                        requireDenied(method, service, user, "android");
                        requireDenied(method, service, user + 1, getPackageName());
                    }
                    queries++;
                } else if (name.equals("startActivityAsUser") || name.equals("startActivityAsUserByIntent")
                        || name.equals("setInteractAcrossProfilesAppOp")
                        || name.equals("resetInteractAcrossProfilesAppOps")
                        || name.equals("clearInteractAcrossProfilesAppOps")) {
                    requireDenied(method, service, arguments(method, user));
                    deniedMutations++;
                } else if (!name.equals("asBinder")) {
                    throw new AssertionError("Uncovered CrossProfileApps entry point: " + name);
                }
            }
            require(queries >= 1 && deniedMutations >= 1, "Missing platform entry points");
            if (Build.VERSION.SDK_INT >= 30) require(queries == 5 && deniedMutations == 5, "Incomplete modern interface coverage");
            report.put("checkedQueries", queries);
            report.put("deniedMutations", deniedMutations);

            IBinder binder = ((IInterface) service).asBinder();
            require(binder.queryLocalInterface("android.content.pm.ICrossProfileApps") == service,
                    "Service Binder bypassed the guest proxy");
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                // A descriptor transaction is read-only even if the hook is missing.
                try { binder.transact(IBinder.INTERFACE_TRANSACTION, data, reply, 0); }
                catch (RemoteException expected) { report.put("rawTransactionsDenied", true); }
            } finally { data.recycle(); reply.recycle(); }
            require(report.optBoolean("rawTransactionsDenied"), "Raw transaction escaped to the real service");
            report.put("passed", true);
        } catch (Throwable failure) {
            try {
                report.put("passed", false);
                report.put("failure", failure.getClass().getName() + ": " + failure.getMessage());
            } catch (Exception ignored) { }
        }
        writeReport(report);
    }

    private Object[] arguments(Method method, int user) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) args[i] = user;
            else if (types[i] == boolean.class) args[i] = true;
            else if (types[i] == String.class) args[i] = getPackageName();
            else if (types[i] == ComponentName.class) args[i] = new ComponentName(this, getClass());
            else if (types[i] == Intent.class) args[i] = new Intent(this, getClass());
            else if (types[i] == UserHandle.class) args[i] = UserHandle.getUserHandleForUid(1000000);
            else if (types[i] == List.class) args[i] = Collections.singletonList(getPackageName());
        }
        return args;
    }

    private static void requireDenied(Method method, Object service, Object... args) throws Exception {
        try { method.invoke(service, args); }
        catch (InvocationTargetException expected) {
            if (expected.getCause() instanceof SecurityException) return;
            throw expected;
        }
        throw new AssertionError("Cross-profile access was accepted: " + method.getName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void writeReport(JSONObject report) {
        AtomicFile file = new AtomicFile(new File(getFilesDir(), "cross-profile-result.json"));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(report.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            file.finishWrite(output);
            TextView text = new TextView(this);
            text.setText(report.toString());
            setContentView(text);
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException(failure);
        }
    }
}
