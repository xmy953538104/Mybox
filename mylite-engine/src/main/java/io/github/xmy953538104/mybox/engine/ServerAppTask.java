package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Implements the platform wire contract without exposing the host's unrestricted IAppTask. */
final class ServerAppTask extends Binder {
    static final String DESCRIPTOR = "android.app.IAppTask";
    private final ServerActivities owner;
    final ServerActivities.Task task;
    private final Map<Integer, String> methods = new LinkedHashMap<>();

    ServerAppTask(ServerActivities owner, ServerActivities.Task task) throws Exception {
        this.owner = owner; this.task = task;
        Class<?> stub = Class.forName(DESCRIPTOR + "$Stub");
        for (String name : new String[]{"finishAndRemoveTask", "getTaskInfo", "moveToFront", "startActivity", "setExcludeFromRecents"})
            methods.put(RuntimeReflection.field(stub, "TRANSACTION_" + name).getInt(null), name);
        attachInterface(null, DESCRIPTOR);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        String operation = methods.get(code);
        if (operation == null) return false;
        if (reply == null || (flags & FLAG_ONEWAY) != 0) throw new SecurityException("AppTask calls must be synchronous");
        try {
            EngineProtocol.requireCaller();
            ProcessRegistry.Identity caller = EngineServerProvider.registeredProcess(Binder.getCallingUid(), Binder.getCallingPid());
            data.enforceInterface(DESCRIPTOR);
            IBinder thread = null; String pkg = null, feature = null, type = null;
            Intent intent = null; Bundle options = null; boolean exclude = false;
            if (operation.equals("moveToFront") && Build.VERSION.SDK_INT >= 29) {
                thread = data.readStrongBinder(); pkg = data.readString();
            } else if (operation.equals("startActivity")) {
                thread = data.readStrongBinder(); pkg = data.readString();
                if (Build.VERSION.SDK_INT >= 30) feature = data.readString();
                intent = data.readTypedObject(Intent.CREATOR); type = data.readString(); options = data.readTypedObject(Bundle.CREATOR);
            } else if (operation.equals("setExcludeFromRecents")) exclude = data.readInt() != 0;
            if (data.dataAvail() != 0) throw new IllegalArgumentException("Trailing AppTask arguments");
            if (pkg != null || operation.equals("startActivity") || operation.equals("moveToFront") && Build.VERSION.SDK_INT >= 29) {
                if (!caller.packageName.equals(pkg) || caller.applicationThread == null || !caller.applicationThread.equals(thread))
                    throw new SecurityException("AppTask framework caller differs from its prepared process");
            }
            // Capture the actual caller before clearing Binder identity for the real host-only OS capability.
            long identity = Binder.clearCallingIdentity(); Object result;
            try { result = owner.appTask(this, operation, caller, intent, type, options, feature, exclude); }
            finally { Binder.restoreCallingIdentity(identity); }
            reply.writeNoException();
            if (operation.equals("getTaskInfo")) reply.writeTypedObject((android.app.ActivityManager.RecentTaskInfo) result,
                    Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            else if (operation.equals("startActivity")) reply.writeInt((Integer) result);
        } catch (EngineException failure) {
            // Custom engine exceptions are not part of Android's Parcel exception wire contract.
            reply.writeException(new IllegalStateException(failure.getMessage(), failure));
        } catch (RuntimeException failure) { reply.writeException(failure); }
        catch (Exception failure) { reply.writeException(new IllegalStateException("Actual Android AppTask call failed", failure)); }
        return true;
    }
}
