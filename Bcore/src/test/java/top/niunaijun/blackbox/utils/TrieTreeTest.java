package top.niunaijun.blackbox.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrieTreeTest {
    @Test public void choosesLongestDirectoryAndRejectsSimilarPackageNames() {
        TrieTree paths = new TrieTree();
        paths.add("/data/data/example.app");
        paths.add("/data/data/example.app/lib");
        assertEquals("/data/data/example.app/lib", paths.search("/data/data/example.app/lib/arm64/a.so"));
        assertEquals("/data/data/example.app", paths.search("/data/data/example.app/files/a"));
        assertEquals("/data/data/example.app", paths.search("/data/data/example.app"));
        assertEquals("/data/data/example.app", paths.search("/data/data/example.app/library/a"));
        assertNull(paths.search("/data/data/example.app.other/files/a"));
        assertNull(paths.search("/prefix/data/data/example.app/files/a"));
        assertNull(paths.search("/data/data/example"));
        assertNull(paths.search(""));
        paths.add("/sdcard/Pictures");
        assertEquals("/sdcard/Pictures", paths.search("/sdcard/Pictures/photo.jpg"));
        assertNull(paths.search("/sdcard/PicturesBackup/photo.jpg"));
    }
}
