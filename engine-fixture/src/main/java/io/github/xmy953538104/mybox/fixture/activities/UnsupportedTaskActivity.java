package io.github.xmy953538104.mybox.fixture.activities;

public final class UnsupportedTaskActivity extends android.app.Activity {
    @Override protected void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        throw new AssertionError("Unsupported singleInstancePerTask Activity was constructed");
    }
}
