package io.github.xmy953538104.mybox.fixture.activities;

/** Checkpoint A must reject this declaration before any guest construction. */
public final class SingleTaskDeniedActivity extends android.app.Activity {
    @Override protected void onCreate(android.os.Bundle saved) {
        throw new AssertionError("singleTask was enabled before its task planner was implemented");
    }
}
