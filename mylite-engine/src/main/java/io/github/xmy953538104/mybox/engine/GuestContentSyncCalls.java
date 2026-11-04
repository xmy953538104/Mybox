package io.github.xmy953538104.mybox.engine;

import android.accounts.Account;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.IInterface;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** All sync state belongs to the virtual user, including calls with no authority argument. */
final class GuestContentSyncCalls {
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList(
            "requestSync", "sync", "syncAsUser", "cancelSync", "cancelSyncAsUser", "cancelRequest",
            "getSyncAutomatically", "getSyncAutomaticallyAsUser", "setSyncAutomatically", "setSyncAutomaticallyAsUser",
            "getIsSyncable", "getIsSyncableAsUser", "setIsSyncable", "setIsSyncableAsUser",
            "getMasterSyncAutomatically", "getMasterSyncAutomaticallyAsUser", "setMasterSyncAutomatically", "setMasterSyncAutomaticallyAsUser",
            "getSyncAdapterTypes", "getSyncAdapterTypesAsUser", "getSyncAdapterPackagesForAuthorityAsUser", "getSyncAdapterPackageAsUser",
            "getCurrentSyncs", "getCurrentSyncsAsUser", "isSyncActive", "isSyncPending", "isSyncPendingAsUser",
            "getSyncStatus", "getSyncStatusAsUser", "addStatusChangeListener", "removeStatusChangeListener",
            "getPeriodicSyncs", "addPeriodicSync", "removePeriodicSync"));

    static boolean handles(String name) { return METHODS.contains(name); }

    static Bundle request(String packageName, Method method, Object[] arguments) throws Exception {
        String name = method.getName(); Object[] args = arguments == null ? new Object[0] : arguments;
        Bundle input = EngineProtocol.response(); input.putString("package", packageName);
        input.putString("operation", "sync." + name.replace("AsUser", "")); input.putInt("user", 0);
        int count = args.length;
        if (name.endsWith("AsUser")) {
            // syncAsUser has its user before callingPackage; all other supported variants put it last.
            int index = name.equals("syncAsUser") ? 1 : count - 1;
            if (!(args[index] instanceof Integer) || (Integer) args[index] != 0) throw new SecurityException("Only virtual sync user zero is supported");
            Object[] reduced = new Object[count - 1];
            System.arraycopy(args, 0, reduced, 0, index); System.arraycopy(args, index + 1, reduced, index, count - index - 1);
            args = reduced; count--; name = name.replace("AsUser", "");
        }
        switch (name) {
            case "requestSync":
                require(count == 3 || count == 4); accountAuthority(input, args);
                input.putBundle("extras", (Bundle) args[2]);
                if (count == 4) attribution(packageName, args[3]); break;
            case "sync": case "cancelRequest": {
                require(count == (name.equals("sync") ? 2 : 1)); Object request = args[0];
                if (request == null) throw new IllegalArgumentException("Missing sync request");
                Class<?> type = Class.forName("android.content.SyncRequest");
                if (!type.isInstance(request)) throw new IllegalArgumentException("Invalid sync request");
                if ((Boolean) RuntimeReflection.invoke(request, type.getMethod("isPeriodic")))
                    throw new UnsupportedOperationException("Virtual periodic sync is not implemented");
                input.putParcelable("account", (Account) RuntimeReflection.invoke(request, type.getMethod("getAccount")));
                input.putString("authority", (String) RuntimeReflection.invoke(request, type.getMethod("getProvider")));
                input.putBundle("extras", (Bundle) RuntimeReflection.invoke(request, type.getMethod("getBundle")));
                input.putLong("delay", ((Number) RuntimeReflection.invoke(request, type.getMethod("getSyncRunTime"))).longValue());
                if (name.equals("sync")) attribution(packageName, args[1]); break;
            }
            case "getSyncAutomatically": case "getIsSyncable":
                require(count == 2); accountAuthority(input, args); break;
            case "setSyncAutomatically": case "setIsSyncable":
                require(count == 3 || name.equals("setIsSyncable") && count == 4);
                accountAuthority(input, args);
                if (name.equals("setSyncAutomatically")) input.putBoolean("auto", (Boolean) args[2]);
                else input.putInt("syncable", (Integer) args[2]);
                if (count == 4) attribution(packageName, args[3]); break;
            case "cancelSync": case "isSyncActive": case "isSyncPending": case "getSyncStatus": case "getPeriodicSyncs":
                require(count == 3); accountAuthority(input, args);
                if (args[2] != null) throw new UnsupportedOperationException("Component-based virtual synchronization is not implemented"); break;
            case "addPeriodicSync": case "removePeriodicSync":
                throw new UnsupportedOperationException("Virtual periodic sync is not implemented");
            case "getMasterSyncAutomatically": case "getSyncAdapterTypes": case "getCurrentSyncs": require(count == 0); break;
            case "setMasterSyncAutomatically": require(count == 1); input.putBoolean("master", (Boolean) args[0]); break;
            case "getSyncAdapterPackagesForAuthority": require(count == 1); input.putString("authority", (String) args[0]); break;
            case "getSyncAdapterPackage": require(count == 2); input.putString("type", (String) args[0]); input.putString("authority", (String) args[1]); break;
            case "addStatusChangeListener": require(count == 2); input.putInt("mask", (Integer) args[0]); input.putBinder("listener", ((IInterface) args[1]).asBinder()); break;
            case "removeStatusChangeListener": require(count == 1); input.putBinder("listener", ((IInterface) args[0]).asBinder()); break;
            default: throw new UnsupportedOperationException("Unknown virtual sync method");
        }
        return input;
    }

    static Object value(String name, Bundle result) {
        if (name.startsWith("getSyncAdapterTypes")) {
            android.os.Parcelable[] values = result.getParcelableArray("value");
            return Arrays.copyOf(values, values.length, android.content.SyncAdapterType[].class);
        }
        if (name.startsWith("getSyncAdapterPackages")) return result.getStringArray("value");
        if (name.startsWith("getSyncAdapterPackage")) return result.getString("value");
        if (name.startsWith("getCurrentSyncs") || name.equals("getPeriodicSyncs")) return result.getParcelableArrayList("value");
        if (name.startsWith("getSyncStatus")) return result.getParcelable("value");
        if (name.startsWith("getIsSyncable")) return result.getInt("value");
        if (name.startsWith("getSyncAutomatically") || name.startsWith("getMasterSyncAutomatically") || name.startsWith("isSync")) return result.getBoolean("value");
        return null;
    }
    private static void accountAuthority(Bundle input, Object[] args) {
        input.putParcelable("account", (Account) args[0]); input.putString("authority", (String) args[1]);
    }
    private static void attribution(String expected, Object actual) {
        if (!expected.equals(actual)) throw new SecurityException("Sync attribution differs from its guest");
    }
    private static void require(boolean condition) { if (!condition) throw new UnsupportedOperationException("Unknown virtual sync signature"); }
}
