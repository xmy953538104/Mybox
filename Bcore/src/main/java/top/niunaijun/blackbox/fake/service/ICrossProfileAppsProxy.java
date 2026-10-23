// SPDX-License-Identifier: Apache-2.0
package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.content.pm.CrossProfileApps;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Objects;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.Reflector;

/** Guests have no mapped Android work/private profiles or cross-profile administration authority. */
public final class ICrossProfileAppsProxy extends BinderInvocationStub {
    private final IBinder service;

    public ICrossProfileAppsProxy() {
        this(BRServiceManager.get().getService(Context.CROSS_PROFILE_APPS_SERVICE));
    }

    private ICrossProfileAppsProxy(IBinder service) {
        super(service);
        this.service = service;
    }

    @Override protected Object getWho() {
        try {
            return Reflector.on("android.content.pm.ICrossProfileApps$Stub")
                    .method("asInterface", IBinder.class).call(service);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot obtain CrossProfileApps service", failure);
        }
    }

    @Override protected void inject(Object base, Object proxy) {
        try {
            CrossProfileApps cached = BlackBoxCore.getContext().getSystemService(CrossProfileApps.class);
            if (cached != null) Reflector.with(cached).field("mService").set(proxy);
            replaceSystemService(Context.CROSS_PROFILE_APPS_SERVICE);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot install guest CrossProfileApps", failure);
        }
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "asBinder": return this;
            case "getTargetUserProfiles":
                requireOwner((String) args[0]);
                return Collections.emptyList();
            case "canInteractAcrossProfiles":
            case "canRequestInteractAcrossProfiles":
                requireOwner((String) args[0]);
                return false;
            case "canConfigureInteractAcrossProfiles":
            case "canUserAttemptToConfigureInteractAcrossProfiles":
                // Android 11 takes only a package; later releases add the Android user ID.
                requireOwner((String) args[args.length - 1]);
                if (args.length == 2 && (Integer) args[0] != BlackBoxCore.getHostUserId()) {
                    throw new SecurityException("Cross-profile queries cannot access another Android user");
                }
                return false;
            case "startActivityAsUser":
            case "startActivityAsUserByIntent":
            case "setInteractAcrossProfilesAppOp":
            case "resetInteractAcrossProfilesAppOps":
            case "clearInteractAcrossProfilesAppOps":
                throw new SecurityException("Guest cross-profile launches and administration are not authorized");
            case "toString": case "hashCode": case "equals":
                return super.invoke(proxy, method, args);
            default:
                throw new UnsupportedOperationException("Unsupported guest CrossProfileApps method: " + method.getName());
        }
    }

    private static void requireOwner(String packageName) {
        Objects.requireNonNull(packageName, "callingPackage");
        String owner = BActivityThread.getAppPackageName();
        if (owner == null || !owner.equals(packageName)) {
            throw new SecurityException("Cross-profile queries are limited to the current guest");
        }
    }

    @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // Do not let a raw Binder caller escape the virtual profile boundary.
        throw new RemoteException("Raw guest CrossProfileApps transactions are unsupported");
    }

    @Override public boolean isBadEnv() {
        return BRServiceManager.get().getService(Context.CROSS_PROFILE_APPS_SERVICE) != this;
    }
}
