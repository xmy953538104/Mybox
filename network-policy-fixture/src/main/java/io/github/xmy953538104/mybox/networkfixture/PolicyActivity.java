package io.github.xmy953538104.mybox.networkfixture;

import android.app.Activity;
import android.os.Bundle;

public final class PolicyActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try { java.nio.file.Files.write(new java.io.File(getFilesDir(), "activity-created").toPath(), new byte[]{1}); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
