package io.github.xmy953538104.mybox;

import android.app.Application;
import android.content.Context;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

public final class MyboxApplication extends Application {
    private Throwable initializationFailure;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            BlackBoxCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override public String getHostPackageName() { return base.getPackageName(); }
                @Override public boolean isEnableDaemonService() { return false; }
                @Override public boolean isEnableLauncherActivity() { return false; }
            });
        } catch (Throwable failure) {
            initializationFailure = failure;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        if (initializationFailure == null) {
            try {
                BlackBoxCore.get().doCreate();
            } catch (Throwable failure) {
                initializationFailure = failure;
            }
        }
    }

    public void requireEngine() {
        if (initializationFailure != null) {
            throw new IllegalStateException("容器初始化失败", initializationFailure);
        }
    }
}
