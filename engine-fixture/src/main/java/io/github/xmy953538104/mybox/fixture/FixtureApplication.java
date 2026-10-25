package io.github.xmy953538104.mybox.fixture;
import android.app.Application;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
public final class FixtureApplication extends Application {
    public static FixtureApplication createdInstance;
    public static int onCreateCount;
    @Override public void onCreate() {
        super.onCreate();
        createdInstance = this; onCreateCount++;
        java.io.File fail = new java.io.File(getFilesDir(), "fail-next-bootstrap");
        if ((getPackageName() + ":bootstrap_failure").equals(getApplicationInfo().processName) && fail.isFile()) {
            try {
                if (!fail.delete()) throw new java.io.IOException("Cannot consume bootstrap failure request");
                java.nio.file.Files.write(new java.io.File(getFilesDir(), "failed-bootstrap-pid").toPath(),
                        Integer.toString(android.os.Process.myPid()).getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            throw new IllegalStateException("CI intentional guest Application bootstrap failure");
        }
        try (FileOutputStream out = openFileOutput("application-created.txt", MODE_PRIVATE)) {
            out.write(getPackageName().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception error) { throw new IllegalStateException(error); }
        io.github.xmy953538104.mybox.fixture.providers.ProviderProbe.applicationCreated(this);
    }
}
