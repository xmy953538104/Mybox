package io.github.xmy953538104.mybox.probe;

/** Detects accidental installation of default-process providers in a secondary process. */
public final class MainProcessProvider extends ProbeProvider {
    static volatile boolean created;

    @Override public boolean onCreate() {
        created = true;
        return super.onCreate();
    }
}
