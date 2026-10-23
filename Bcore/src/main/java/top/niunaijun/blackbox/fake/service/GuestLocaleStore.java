// SPDX-License-Identifier: Apache-2.0
package top.niunaijun.blackbox.fake.service;

import android.os.LocaleList;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;

/** One persisted locale preference per virtual user and package, shared by its processes. */
final class GuestLocaleStore {
    static final String FILE_NAME = "application-locales.txt";

    static void requireOwner(String requestedPackage, int androidUser) {
        String owner = BActivityThread.getAppPackageName();
        if (owner == null || !owner.equals(requestedPackage)
                || androidUser != BlackBoxCore.getHostUserId()) {
            throw new SecurityException("App locales are limited to the current guest and Android user");
        }
    }

    static File directory() {
        String owner = BActivityThread.getAppPackageName();
        if (owner == null) throw new IllegalStateException("No guest bound for app locales");
        File directory = new File(BEnvironment.getDeDataDir(owner, BActivityThread.getUserId()), ".mybox-locales");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Cannot create guest locale storage");
        }
        return directory;
    }

    // AtomicFile is not itself a lock: serialize its readers and writers across guest processes.
    static synchronized LocaleList read() {
        File directory = directory();
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, "lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            AtomicFile file = new AtomicFile(new File(directory, FILE_NAME));
            try {
                return LocaleList.forLanguageTags(new String(file.readFully(), StandardCharsets.UTF_8));
            } catch (FileNotFoundException absent) {
                if (!file.getBaseFile().exists()) return LocaleList.getEmptyLocaleList();
                throw absent;
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read guest app locales", failure);
        }
    }

    static synchronized void write(LocaleList locales) {
        Objects.requireNonNull(locales, "locales");
        File directory = directory();
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, "lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            AtomicFile file = new AtomicFile(new File(directory, FILE_NAME));
            FileOutputStream output = null;
            try {
                output = file.startWrite();
                output.write(locales.toLanguageTags().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
                file.finishWrite(output);
            } catch (IOException failure) {
                file.failWrite(output);
                throw failure;
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist guest app locales", failure);
        }
    }
}
