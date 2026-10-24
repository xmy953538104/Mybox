package io.github.xmy953538104.mybox;

import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Selects one public-API probe so unrelated account UI cannot preempt a launch test. */
public final class FixtureRun {
    public static void prepare(InstalledPackage installed, String mode) throws java.io.IOException {
        File files = new File(installed.dataDir, "files");
        if (!files.isDirectory() && !files.mkdirs()) throw new java.io.IOException("Cannot prepare fixture mode");
        File pending = new File(files, "fixture-mode.tmp");
        Files.write(pending.toPath(), mode.getBytes(StandardCharsets.UTF_8));
        Files.move(pending.toPath(), new File(files, "fixture-mode").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private FixtureRun() { }
}
