package io.github.xmy953538104.mybox.engine;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

/** A temporary boundary after LoadedApk prepares its final policy, before guest constructors run. */
@android.annotation.TargetApi(30)
final class GuestApplicationFactory extends AppComponentFactory {
    private final AppComponentFactory original;
    private GuestApplicationFactory(AppComponentFactory original) { this.original = original; }

    static Application makeApplication(Object loadedApk) throws Exception {
        RuntimeReflection.invoke(loadedApk, RuntimeReflection.method(loadedApk.getClass(), "getClassLoader"));
        java.lang.reflect.Field field = RuntimeReflection.field(loadedApk.getClass(), "mAppComponentFactory");
        AppComponentFactory original = (AppComponentFactory) field.get(loadedApk);
        if (original == null) throw new EngineException("Guest component factory is missing");
        GuestApplicationFactory boundary = new GuestApplicationFactory(original);
        field.set(loadedApk, boundary);
        try {
            return (Application) RuntimeReflection.invoke(loadedApk, RuntimeReflection.method(loadedApk.getClass(),
                    "makeApplication", boolean.class, Instrumentation.class), false, null);
        } finally {
            // Preserve a factory deliberately replaced by the guest during construction.
            if (field.get(loadedApk) == boundary) field.set(loadedApk, original);
        }
    }

    @Override public Application instantiateApplication(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try { GuestNetworkSecurity.applicationCreating(); }
        catch (Exception failure) { throw new EngineException("Cannot apply the framework Application network policy", failure); }
        return original.instantiateApplication(loader, name);
    }
    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return original.instantiateActivity(loader, name, intent);
    }
    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return original.instantiateService(loader, name, intent);
    }
    @Override public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return original.instantiateReceiver(loader, name, intent);
    }
    @Override public ContentProvider instantiateProvider(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return original.instantiateProvider(loader, name);
    }
    @Override public ClassLoader instantiateClassLoader(ClassLoader loader, ApplicationInfo info) {
        return original.instantiateClassLoader(loader, info);
    }
}
