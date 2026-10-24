package io.github.xmy953538104.mybox.engine.installer;

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.IOException;
import java.util.List;

public interface InstallerBackend {
    List<String> certificates(String packageName) throws IOException;
    InstalledPackage install(List<File> apks, String expectedPackage, io.github.xmy953538104.mybox.engine.CallerIdentity installer) throws IOException;
    /** Recovery proves exact installed APK bytes before calling an interrupted commit successful. */
    InstalledPackage findPublished(List<File> apks, String expectedPackage, String installerPackage) throws IOException;
}
