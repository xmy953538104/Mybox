// SPDX-License-Identifier: Apache-2.0
package top.niunaijun.blackbox.fake.service;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import black.android.app.BRActivity;
import top.niunaijun.blackbox.app.BActivityThread;

/** Applies persisted guest locales before app/provider/activity code observes its resources. */
public final class GuestLocaleResources {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, Boolean> ACTIVITIES = new WeakHashMap<>();
    private static Application application;
    private static FileObserver observer;
    private static LocaleList lastEffective;

    private static boolean isGuest(Context context) {
        return Build.VERSION.SDK_INT >= 33 && context != null
                && context.getPackageName().equals(BActivityThread.getAppPackageName());
    }

    public static void prepare(Context context) {
        if (isGuest(context)) apply(context, effectiveLocales());
    }

    public static void initialize(Application app) {
        if (!isGuest(app) || application != null) return;
        application = app;
        lastEffective = effectiveLocales();
        apply(app, lastEffective);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (isGuest(activity)) ACTIVITIES.put(activity, Boolean.TRUE);
            }
            @Override public void onActivityStarted(Activity activity) { prepare(activity); }
            @Override public void onActivityResumed(Activity activity) { prepare(activity); }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { ACTIVITIES.remove(activity); }
        });
        app.registerComponentCallbacks(new ComponentCallbacks() {
            @Override public void onConfigurationChanged(Configuration configuration) {
                // The framework delivers system changes itself; only reapply the guest preference.
                MAIN.post(() -> refresh(false));
            }
            @Override public void onLowMemory() { }
        });
        observer = new FileObserver(GuestLocaleStore.directory(), FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
            @Override public void onEvent(int event, String path) {
                if (GuestLocaleStore.FILE_NAME.equals(path)) localesChanged();
            }
        };
        observer.startWatching();
    }

    public static void localesChanged() {
        // Like Android's configuration delivery, recreation happens after the setter returns.
        MAIN.post(() -> refresh(true));
    }

    private static LocaleList effectiveLocales() {
        Set<Locale> merged = new LinkedHashSet<>();
        LocaleList preference = GuestLocaleStore.read();
        LocaleList system = ILocaleManagerProxy.systemLocales();
        for (int i = 0; i < preference.size(); i++) merged.add(preference.get(i));
        for (int i = 0; i < system.size(); i++) merged.add(system.get(i));
        return new LocaleList(merged.toArray(new Locale[0]));
    }

    private static void refresh(boolean notifyActivities) {
        if (application == null) return;
        LocaleList locales = effectiveLocales();
        boolean changed = !locales.equals(lastEffective);
        lastEffective = locales;
        apply(application, locales);
        if (changed && notifyActivities) {
            application.onConfigurationChanged(new Configuration(application.getResources().getConfiguration()));
        }
        for (Activity activity : new ArrayList<>(ACTIVITIES.keySet())) {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) continue;
            apply(activity, locales);
            if (changed && notifyActivities) {
                ActivityInfo info = BRActivity.get(activity).mActivityInfo();
                int required = ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_LAYOUT_DIRECTION;
                if (info != null && (info.configChanges & required) == required) {
                    activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
                } else {
                    activity.recreate();
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void apply(Context context, LocaleList locales) {
        Resources resources = context.getResources();
        if (!locales.equals(resources.getConfiguration().getLocales())) {
            Configuration configuration = new Configuration(resources.getConfiguration());
            configuration.setLocales(locales);
            resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        }
        if (!locales.isEmpty()) LocaleList.setDefault(locales);
    }
}
