package io.github.xmy953538104.mybox.fixture;
import android.app.Application;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
public final class FixtureApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        try (FileOutputStream out = openFileOutput("application-created.txt", MODE_PRIVATE)) {
            out.write(getPackageName().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
