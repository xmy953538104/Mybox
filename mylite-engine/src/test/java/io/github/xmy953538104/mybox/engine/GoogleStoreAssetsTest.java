package io.github.xmy953538104.mybox.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class GoogleStoreAssetsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private static final class Assets implements GoogleStoreAssets.Assets {
        String[] root = {"google-play"};
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<String> opened = new ArrayList<>();
        String broken;
        @Override public String[] list(String path) {
            return path.equals("mybox") ? root : files.keySet().toArray(new String[0]);
        }
        @Override public InputStream open(String path) throws IOException {
            opened.add(path);
            if (path.equals(broken)) throw new IOException("Fixture read failed");
            String name = path.substring(path.lastIndexOf('/') + 1);
            byte[] bytes = files.get(name);
            if (bytes == null) throw new IOException("No fixture member " + path);
            return new ByteArrayInputStream(bytes);
        }
    }

    @Test public void completeSetIsOrderedCopiedAndOwnedFilesAreRemoved() throws Exception {
        Assets source = new Assets();
        for (int i = 5; i >= 0; i--) source.files.put("split_config." + i + ".apk", new byte[]{(byte) i});
        source.files.put("base.apk", new byte[]{91});
        File cache = temporary.newFolder();
        List<File> copies;
        try (GoogleStoreAssets.Copied result = GoogleStoreAssets.fromAssets(source, cache)) {
            copies = result.files(); assertEquals(7, copies.size());
            assertArrayEquals(new byte[]{91}, Files.readAllBytes(copies.get(0).toPath()));
            assertEquals("mybox/google-play/base.apk", source.opened.get(0));
            assertEquals("mybox/google-play/split_config.0.apk", source.opened.get(1));
            for (File copy : copies) assertEquals(cache, copy.getParentFile().getParentFile());
        }
        assertEquals(0, cache.list().length);
        for (File copy : copies) assertFalse(copy.exists());
    }

    @Test public void legacyAndNoAssetSourcesKeepTheirMeaning() throws Exception {
        Assets source = new Assets(); source.root = new String[]{"google-play.apk"};
        source.files.put("google-play.apk", new byte[]{7});
        File cache = temporary.newFolder();
        try (GoogleStoreAssets.Copied result = GoogleStoreAssets.fromAssets(source, cache)) {
            assertEquals(1, result.files().size()); assertEquals(Collections.singletonList("mybox/google-play.apk"), source.opened);
        }
        source.root = new String[]{"microg-nohms.apk"};
        assertNull(GoogleStoreAssets.fromAssets(source, cache));
        assertEquals(0, cache.list().length);
    }

    @Test public void malformedSetAndConflictingSourcesNeverFallBack() throws Exception {
        File cache = temporary.newFolder();
        for (String[] names : new String[][]{{}, {"split_en.apk"}, {"base.apk", "base.apk"},
                {"base.apk", "split_EN.apk", "split_en.apk"}, {"base.apk", "nested/file.apk"},
                {"base.apk", "../split_en.apk"}, {"base.apk", "readme.txt"}, {"BASE.apk"},
                {"base.apk", "split_" + String.join("", Collections.nCopies(160, "a")) + ".apk"}}) {
            try { GoogleStoreAssets.members(names); fail(Arrays.toString(names)); } catch (IOException expected) { }
        }
        String[] excess = new String[257]; Arrays.fill(excess, "base.apk");
        try { GoogleStoreAssets.members(excess); fail("Unbounded member count"); } catch (IOException expected) { }
        Assets source = new Assets(); source.root = new String[]{"google-play", "google-play.apk"};
        source.files.put("base.apk", new byte[]{1}); source.files.put("google-play.apk", new byte[]{2});
        try { GoogleStoreAssets.fromAssets(source, cache); fail("Two asset sources were combined"); } catch (IOException expected) { }
        assertTrue(source.opened.isEmpty()); assertEquals(0, cache.list().length);
        source.files.remove("google-play.apk"); // The next case is a valid directory whose base read fails.
        source.root = new String[]{"google-play"}; source.broken = "mybox/google-play/base.apk";
        try { GoogleStoreAssets.fromAssets(source, cache); fail("Broken asset was accepted"); } catch (IOException expected) { }
        assertEquals(Collections.singletonList(source.broken), source.opened); assertEquals(0, cache.list().length);
    }

    @Test public void actualByteBudgetEmptyAndReadWriteFailuresArePreserved() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(3, GoogleStoreAssets.copyApk(new ByteArrayInputStream(new byte[3]), output, 3));
        try { GoogleStoreAssets.copyApk(new ByteArrayInputStream(new byte[4]), output, 3); fail("Byte limit bypassed"); } catch (IOException expected) { }
        try { GoogleStoreAssets.copyApk(new ByteArrayInputStream(new byte[0]), output, 3); fail("Empty APK accepted"); } catch (IOException expected) { }
        IOException failure = new IOException("Fixture write failed");
        try {
            GoogleStoreAssets.copyApk(new ByteArrayInputStream(new byte[]{1}), new java.io.OutputStream() {
                @Override public void write(int value) throws IOException { throw failure; }
            }, 3); fail("Write failure swallowed");
        } catch (IOException expected) { assertSame(failure, expected); }
        Assets source = new Assets(); source.files.put("base.apk", new byte[]{1}); source.files.put("split_en.apk", new byte[0]);
        File cache = temporary.newFolder();
        try { GoogleStoreAssets.fromAssets(source, cache); fail("Partial set survived"); } catch (IOException expected) { }
        assertEquals(0, cache.list().length);
    }

    @Test public void cleanupFailureCannotReplacePublicationOrAnEarlierInstallFailure() throws Exception {
        io.github.xmy953538104.mybox.engine.packages.PackageStore store =
                io.github.xmy953538104.mybox.engine.packages.InstallerFixturePackages.store(temporary.newFolder());
        File apk = io.github.xmy953538104.mybox.engine.packages.InstallerFixturePackages.apk(
                temporary.newFolder(), "org.mybox.fixture", 1, null, "signer-a");
        GoogleStoreAssets.Copied staged = GoogleStoreAssets.fromFiles(Collections.singletonList(apk), temporary.newFolder());
        File blocker = new File(staged.files().get(0).getParentFile(), "do-not-delete-unowned-file");
        Files.write(blocker.toPath(), new byte[]{1});
        try {
            io.github.xmy953538104.mybox.engine.packages.InstalledPackage published = store.install(staged.files());
            assertNotNull("Post-publication cleanup must be reported without throwing", staged.finish(true, null));
            assertEquals(published.baseApk, store.get(published.packageName).baseApk);
            IOException rejected = new IOException("Original installation failure");
            assertNull(staged.finish(false, rejected));
            assertEquals(1, rejected.getSuppressed().length);
            try { staged.finish(false, null); fail("Pre-publication cleanup failure was hidden"); }
            catch (IOException expected) { }
        } finally { assertTrue(blocker.delete()); staged.close(); }
        assertTrue(apk.isFile());
    }

    @Test public void physicalSourcesUseTheSameBudgetAndNeverDeleteOriginals() throws Exception {
        File source = temporary.newFile(); Files.write(source.toPath(), new byte[]{4});
        File cache = temporary.newFolder();
        try (GoogleStoreAssets.Copied result = GoogleStoreAssets.fromFiles(Collections.singletonList(source), cache)) {
            assertNotEquals(source, result.files().get(0));
            assertArrayEquals(new byte[]{4}, Files.readAllBytes(result.files().get(0).toPath()));
        }
        assertTrue(source.isFile()); assertEquals(0, cache.list().length);
        try { GoogleStoreAssets.fromFiles(Collections.nCopies(257, source), cache); fail("Physical source count was unbounded"); } catch (IOException expected) { }
    }

    @Test public void referenceImportsOnlyTheFixedStoreEntryAndKeepsTheSource() throws Exception {
        File archive = reference(GoogleStoreAssets.REFERENCE_STORE);
        byte[] original = Files.readAllBytes(archive.toPath()); File cache = temporary.newFolder();
        try (GoogleStoreAssets.Copied result = GoogleStoreAssets.fromReference(archive,cache)) {
            assertEquals(1,result.files().size());
            assertArrayEquals(new byte[]{41,42,43},Files.readAllBytes(result.files().get(0).toPath()));
        }
        assertEquals(0,cache.list().length); assertArrayEquals(original,Files.readAllBytes(archive.toPath()));
    }

    @Test public void missingDuplicateAndBadCrcReferencesLeaveNoStagedFile() throws Exception {
        File missing = reference("assets/preset_apps/other.apk"); rejectReference(missing);
        File corrupt = reference(GoogleStoreAssets.REFERENCE_STORE);
        byte[] bytes = Files.readAllBytes(corrupt.toPath());
        for (int i=0;i<bytes.length-3;i++) if (bytes[i]==41 && bytes[i+1]==42 && bytes[i+2]==43) { bytes[i]=44; break; }
        Files.write(corrupt.toPath(),bytes); rejectReference(corrupt);
        File duplicate = temporary.newFile();
        String alternate = GoogleStoreAssets.REFERENCE_STORE.replace("400013","400014");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(duplicate))) {
            stored(zip,GoogleStoreAssets.REFERENCE_STORE); stored(zip,alternate);
        }
        bytes = Files.readAllBytes(duplicate.toPath());
        byte[] old = alternate.getBytes(java.nio.charset.StandardCharsets.UTF_8), replacement = GoogleStoreAssets.REFERENCE_STORE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i=0;i<=bytes.length-old.length;i++) {
            boolean match=true; for(int j=0;j<old.length;j++) if(bytes[i+j]!=old[j]) {match=false; break;}
            if(match) System.arraycopy(replacement,0,bytes,i,replacement.length);
        }
        Files.write(duplicate.toPath(),bytes); rejectReference(duplicate);
    }

    @Test public void referenceDirectoryLimitsRejectBeforeExtraction() throws Exception {
        File valid=reference(GoogleStoreAssets.REFERENCE_STORE); byte[] source=Files.readAllBytes(valid.toPath());
        for (int mode=0;mode<5;mode++) {
            byte[] bytes=source.clone(); int end=bytes.length-22;
            if(mode==0) bytes=Arrays.copyOf(bytes,bytes.length-1);
            else if(mode==1) bytes[end+4]=1;
            else if(mode==2) { bytes[end+8]=(byte)255; bytes[end+9]=(byte)255; bytes[end+10]=(byte)255; bytes[end+11]=(byte)255; }
            else if(mode==3) { bytes[end+12]=(byte)255; bytes[end+13]=(byte)255; bytes[end+14]=(byte)255; bytes[end+15]=127; }
            else bytes[end+16]=0;
            File invalid=temporary.newFile(); Files.write(invalid.toPath(),bytes); rejectReference(invalid);
        }
        File oversized=temporary.newFile();
        try(java.io.RandomAccessFile output=new java.io.RandomAccessFile(oversized,"rw")) {output.setLength(GoogleStoreAssets.MAX_BYTES+1);}
        rejectReference(oversized);
    }
    private File reference(String name) throws Exception {
        File file=temporary.newFile();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(file))) {
            stored(zip,name); stored(zip,"lib/arm64-v8a/private-engine.so"); stored(zip,"../do-not-extract");
        }
        return file;
    }
    private static void stored(java.util.zip.ZipOutputStream zip,String name) throws Exception {
        byte[] value={41,42,43}; java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(value);
        java.util.zip.ZipEntry entry=new java.util.zip.ZipEntry(name);entry.setMethod(java.util.zip.ZipEntry.STORED);
        entry.setSize(value.length);entry.setCrc(crc.getValue());zip.putNextEntry(entry);zip.write(value);zip.closeEntry();
    }
    private void rejectReference(File source) throws Exception {
        File cache=temporary.newFolder();
        try(GoogleStoreAssets.Copied ignored=GoogleStoreAssets.fromReference(source,cache)) {fail("Malformed reference was accepted");}
        catch(IOException expected) { }
        assertTrue(source.isFile());assertEquals(0,cache.list().length);
    }
}
