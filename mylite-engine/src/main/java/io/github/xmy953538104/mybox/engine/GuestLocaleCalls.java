package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.LocaleList;
import android.os.Parcel;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.util.Map;
import java.util.Properties;

/** Per-package language preferences never change the physical host or another installed app. */
final class GuestLocaleCalls implements InvocationHandler {
    interface Update { void apply(LocaleList locales) throws Exception; }
    private static final Object FILE_LOCK = new Object();
    private final Object original;
    private final String guest;
    private final File file;
    private final Update update;
    private IBinder local;

    GuestLocaleCalls(Object original, String guest, File file, Update update) {
        this.original = original; this.guest = guest; this.file = file; this.update = update;
    }
    @SuppressWarnings("unchecked") static GuestLocaleCalls install(Engine engine, GuestRuntime runtime) throws Exception {
        // shortcut: LocaleManager AIDL is verified for Android 14; verify other versions before enabling.
        if (android.os.Build.VERSION.SDK_INT != 34) return null;
        Object manager = engine.hostContext().getSystemService(Context.LOCALE_SERVICE);
        if (manager == null) return null;
        Object original = RuntimeReflection.get(manager, "mService");
        Class<?> contract = Class.forName("android.app.ILocaleManager");
        String guest = runtime.packageInfo().packageName;
        GuestLocaleCalls calls = new GuestLocaleCalls(original, guest,
                new File(engine.hostContext().getFilesDir(), "mylite-locales/" + guest + ".properties"),
                locales -> runtime.post(runtime::refreshLocales));
        IInterface proxy = (IInterface) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, calls);
        Binder local = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                throw new SecurityException("Raw virtual locale transactions are not supported");
            }
        };
        local.attachInterface(proxy, contract.getName()); calls.local = local;
        Map<String, IBinder> cache = (Map<String, IBinder>) RuntimeReflection.field(Class.forName("android.os.ServiceManager"), "sCache").get(null);
        cache.put("locale", local); RuntimeReflection.set(manager, "mService", proxy);
        return calls;
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("asBinder")) return local;
        if (method.getDeclaringClass() == Object.class) {
            if (name.equals("toString")) return "MyliteLocaleManager";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
        }
        if (name.equals("getSystemLocales")) {
            try { return method.invoke(original, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }
        boolean set = name.equals("setApplicationLocales"), get = name.equals("getApplicationLocales");
        boolean setOverride = name.equals("setOverrideLocaleConfig"), getOverride = name.equals("getOverrideLocaleConfig");
        if (!set && !get && !setOverride && !getOverride) throw new UnsupportedOperationException("Unknown virtual locale operation: " + name);
        if (Binder.getCallingPid() != android.os.Process.myPid() || Binder.getCallingUid() != android.os.Process.myUid()
                || android.os.Process.myUid() / 100000 != 0) throw new SecurityException("Locale preferences require the local guest in user zero");
        int count = set ? 4 : setOverride ? 3 : 2;
        if (args == null || args.length != count || !guest.equals(args[0]) || !Integer.valueOf(0).equals(args[1])) {
            throw new SecurityException("Locale preferences do not belong to this guest");
        }
        if (set && (!(args[2] instanceof LocaleList) || !(args[3] instanceof Boolean))) throw new IllegalArgumentException("Invalid application locales");
        if (setOverride && args[2] != null && !(args[2] instanceof android.app.LocaleConfig)) throw new IllegalArgumentException("Invalid locale config");
        String key = setOverride || getOverride ? "override" : "application";
        if (get) return readLocales();
        if (getOverride) {
            String tags = property(key, null, false);
            return tags == null ? null : new android.app.LocaleConfig(LocaleList.forLanguageTags(tags));
        }
        LocaleList locales = set ? (LocaleList) args[2]
                : args[2] == null ? null : ((android.app.LocaleConfig) args[2]).getSupportedLocales();
        if (setOverride && args[2] != null && locales == null) throw new IllegalArgumentException("Locale config has no supported locales");
        property(key, locales == null ? null : locales.toLanguageTags(), true);
        if (set) update.apply(locales);
        return null;
    }
    LocaleList readLocales() throws Exception {
        String tags = property("application", null, false);
        return tags == null ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tags);
    }
    private String property(String key, String value, boolean write) throws Exception {
        synchronized (FILE_LOCK) {
            File directory = file.getParentFile();
            if (!directory.mkdirs() && !directory.isDirectory()) throw new java.io.IOException("Cannot create guest locale directory");
            try (RandomAccessFile lockFile = new RandomAccessFile(new File(file.getPath() + ".lock"), "rw");
                    FileLock ignored = lockFile.getChannel().lock()) {
                AtomicFile atomic = new AtomicFile(file); Properties properties = new Properties();
                try (FileInputStream input = atomic.openRead()) { properties.load(input); }
                catch (java.io.FileNotFoundException missing) { if (file.exists()) throw missing; }
                if (!write) return properties.getProperty(key);
                if (value == null) properties.remove(key); else properties.setProperty(key, value);
                FileOutputStream output = atomic.startWrite();
                try { properties.store(output, "Mylite guest locales"); atomic.finishWrite(output); }
                catch (Exception failure) { atomic.failWrite(output); throw failure; }
                return value;
            }
        }
    }
}
