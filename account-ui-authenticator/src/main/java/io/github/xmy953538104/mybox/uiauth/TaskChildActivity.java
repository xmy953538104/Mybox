package io.github.xmy953538104.mybox.uiauth;

import android.os.Bundle;

public class TaskChildActivity extends UiFixtureActivity {
    @Override public void onCreate(Bundle state) { super.onCreate(state); publish("child-created", getIntent().getStringExtra("tag"), null); }
    @Override public void onResume() { super.onResume(); publish("child-resumed", getIntent().getStringExtra("tag"), null); }
    public static final class Remote extends TaskChildActivity { }
    public static final class OtherRoot extends TaskChildActivity { }
}
