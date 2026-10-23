// SPDX-License-Identifier: Apache-2.0
package top.niunaijun.blackbox.fake.service;

import android.app.LocaleManager;
import android.content.Context;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.Reflector;

/** Android 13+ per-app locale state must belong to the guest, never the shared host UID. */
public final class ILocaleManagerProxy extends BinderInvocationStub {
    private static ILocaleManagerProxy instance;
    private final IBinder service;

    public ILocaleManagerProxy() {
        this(BRServiceManager.get().getService(Context.LOCALE_SERVICE));
    }

    private ILocaleManagerProxy(IBinder service) {
        super(service);
        this.service = service;
    }

    @Override protected Object getWho() {
        try {
            return Reflector.on("android.app.ILocaleManager$Stub")
                    .method("asInterface", IBinder.class).call(service);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot obtain LocaleManager service", failure);
        }
    }

    @Override protected void inject(Object base, Object proxy) {
        try {
            // Also repair the instance cached by the bootstrap context before hook installation.
            LocaleManager cached = BlackBoxCore.getContext().getSystemService(LocaleManager.class);
            if (cached != null) Reflector.with(cached).field("mService").set(proxy);
            replaceSystemService(Context.LOCALE_SERVICE);
            instance = this;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot install guest LocaleManager", failure);
        }
    }

    static LocaleList systemLocales() {
        if (instance == null) throw new IllegalStateException("Guest LocaleManager is unavailable");
        try {
            return Reflector.with(instance.getBase()).method("getSystemLocales").call();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read system locales", failure);
        }
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "asBinder": return this;
            case "getSystemLocales": return systemLocales();
            case "getApplicationLocales":
                GuestLocaleStore.requireOwner((String) args[0], (Integer) args[1]);
                return GuestLocaleStore.read();
            case "setApplicationLocales":
                GuestLocaleStore.requireOwner((String) args[0], (Integer) args[1]);
                // Android 13 has three arguments; Android 14+ adds the system backup delegate flag.
                GuestLocaleStore.write((LocaleList) args[2]);
                GuestLocaleResources.localesChanged();
                return null;
            case "getOverrideLocaleConfig":
                GuestLocaleStore.requireOwner((String) args[0], (Integer) args[1]);
                return null;
            case "setOverrideLocaleConfig":
                GuestLocaleStore.requireOwner((String) args[0], (Integer) args[1]);
                // shortcut: dynamic supported-language overrides need separate filtering and persistence.
                throw new UnsupportedOperationException("Dynamic guest LocaleConfig overrides are not implemented");
            case "toString": case "hashCode": case "equals":
                return super.invoke(proxy, method, args);
            default:
                throw new UnsupportedOperationException("Unsupported guest LocaleManager method: " + method.getName());
        }
    }

    @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // Raw transactions must not escape to the real service and mutate host locale preferences.
        throw new RemoteException("Raw guest LocaleManager transactions are unsupported");
    }

    @Override public boolean isBadEnv() {
        return BRServiceManager.get().getService(Context.LOCALE_SERVICE) != this;
    }
}
