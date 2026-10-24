package io.github.xmy953538104.mybox;

import android.app.Application;
import android.content.Context;

import io.github.xmy953538104.mybox.engine.Engine;

public final class MyboxApplication extends Application {
    private Throwable initializationFailure;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            Engine.attach(base);
        } catch (Throwable failure) {
            initializationFailure = failure;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        if (initializationFailure == null) {
            try {
                Engine.get().onCreate(this);
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
