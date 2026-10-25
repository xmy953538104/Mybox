package io.github.xmy953538104.mybox.engine.packages;

import com.android.apksig.ApkSigner;
import com.android.apksig.SigningCertificateLineage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Small binary manifests and real APK signatures; no Android runtime or APK build is needed. */
final class SignedApkFixture {
    private static final String NS = "http://schemas.android.com/apk/res/android";
    private static final byte[] PAYLOAD = "package-transaction-test-payload".getBytes(StandardCharsets.UTF_8);
    String packageName = "org.mybox.fixture";
    int version = 1;
    String split;
    String dependency;
    String configFor;
    int minSdk = 26;
    boolean isolated;
    boolean component;
    final Map<String, byte[]> entries = new LinkedHashMap<>();
    final Map<String, Boolean> libraries = new LinkedHashMap<>();

    SignedApkFixture version(int value) { version = value; return this; }
    SignedApkFixture split(String value) { split = value; return this; }
    SignedApkFixture dependency(String value) { dependency = value; return this; }
    SignedApkFixture configFor(String value) { configFor = value; return this; }
    SignedApkFixture packageName(String value) { packageName = value; return this; }
    SignedApkFixture entry(String name, byte[] value) { entries.put(name, value); return this; }
    SignedApkFixture library(String name, boolean required) { libraries.put(name, required); return this; }

    File write(File directory, String alias) throws Exception {
        return write(directory, alias, null, true, 28, false);
    }

    File writeRotated(File directory, String original, String current, boolean installedData, int rotationSdk) throws Exception {
        return write(directory, current, original, installedData, rotationSdk, false);
    }

    File writeMultiSigned(File directory, String first, String second) throws Exception {
        return write(directory, second, first, true, 28, true);
    }

    private File write(File directory, String alias, String original, boolean installedData, int rotationSdk,
                       boolean multipleCurrentSigners) throws Exception {
        File unsigned = File.createTempFile("fixture-unsigned-", ".apk", directory);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(unsigned))) {
            stored(zip, "AndroidManifest.xml", manifest());
            stored(zip, "assets/fixture.txt", PAYLOAD);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) stored(zip, entry.getKey(), entry.getValue());
        }
        File signed = File.createTempFile("fixture-signed-", ".apk", directory);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = SignedApkFixture.class.getResourceAsStream("/package-test-signers.p12")) {
            if (input == null) throw new IllegalStateException("Missing public fixture signing keys");
            keys.load(input, "test-only".toCharArray());
        }
        PrivateKey key = (PrivateKey) keys.getKey(alias, "test-only".toCharArray());
        X509Certificate cert = (X509Certificate) keys.getCertificate(alias);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(alias, key, Collections.singletonList(cert)).build();
        List<ApkSigner.SignerConfig> signers = new ArrayList<>();
        SigningCertificateLineage lineage = null;
        if (original != null) {
            PrivateKey oldKey = (PrivateKey) keys.getKey(original, "test-only".toCharArray());
            X509Certificate oldCert = (X509Certificate) keys.getCertificate(original);
            signers.add(new ApkSigner.SignerConfig.Builder(original, oldKey, Collections.singletonList(oldCert)).build());
            if (!multipleCurrentSigners) lineage = new SigningCertificateLineage.Builder(
                    new SigningCertificateLineage.SignerConfig.Builder(oldKey, oldCert).build(),
                    new SigningCertificateLineage.SignerConfig.Builder(key, cert).build())
                    .setOriginalCapabilities(new SigningCertificateLineage.SignerCapabilities.Builder()
                            .setInstalledData(installedData).build()).build();
        }
        signers.add(signer);
        ApkSigner.Builder builder = new ApkSigner.Builder(signers).setInputApk(unsigned).setOutputApk(signed)
                .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true)
                .setV3SigningEnabled(lineage != null).setV4SigningEnabled(false);
        if (lineage != null) builder.setSigningCertificateLineage(lineage).setMinSdkVersionForRotation(rotationSdk);
        builder.build().sign();
        return signed;
    }

    static byte[] payload() { return PAYLOAD.clone(); }

    private static void stored(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private byte[] manifest() {
        Xml xml = new Xml();
        List<Attr> attributes = new ArrayList<>(Arrays.asList(Attr.text("", "package", packageName), Attr.number("versionCode", version)));
        if (split != null) attributes.add(Attr.text("", "split", split));
        if (configFor != null) attributes.add(Attr.text("", "configForSplit", configFor));
        if (isolated) attributes.add(new Attr(NS, "isolatedSplits", 0x12, 1, null));
        xml.start("manifest", attributes.toArray(new Attr[0]));
        xml.start("uses-sdk", Attr.number("minSdkVersion", minSdk), Attr.number("targetSdkVersion", 28));
        xml.end("uses-sdk");
        if (dependency != null) {
            xml.start("uses-split", Attr.text(NS, "name", dependency));
            xml.end("uses-split");
        }
        xml.start("application");
        for (Map.Entry<String, Boolean> library : libraries.entrySet()) {
            xml.start("uses-library", Attr.text(NS, "name", library.getKey()), new Attr(NS, "required", 0x12, library.getValue() ? 1 : 0, null));
            xml.end("uses-library");
        }
        if (split == null || component) {
            // A resource-valued enabled flag must be resolved by Android, not assumed by our XML scan.
            xml.start("activity", Attr.text(NS, "name", ".FirstActivity"), new Attr(NS, "enabled", 1, 0x7f010000, null));
            launcher(xml);
            xml.end("activity");
            xml.start("activity-alias", Attr.text(NS, "name", ".SecondAlias"));
            launcher(xml);
            xml.end("activity-alias");
        }
        xml.end("application");
        xml.end("manifest");
        return xml.encode();
    }

    private static void launcher(Xml xml) {
        xml.start("intent-filter");
        xml.start("action", Attr.text(NS, "name", "android.intent.action.MAIN")); xml.end("action");
        xml.start("category", Attr.text(NS, "name", "android.intent.category.LAUNCHER")); xml.end("category");
        xml.end("intent-filter");
    }

    private static final class Attr {
        final String ns, name, text;
        final int type, value;
        Attr(String ns, String name, int type, int value, String text) {
            this.ns = ns; this.name = name; this.type = type; this.value = value; this.text = text;
        }
        static Attr text(String ns, String name, String value) { return new Attr(ns, name, 3, 0, value); }
        static Attr number(String name, int value) { return new Attr(NS, name, 0x10, value, null); }
    }

    private static final class Xml {
        final List<String> strings = new ArrayList<>();
        final ByteArrayOutputStream nodes = new ByteArrayOutputStream();
        int index(String value) {
            if (value == null || value.isEmpty()) return -1;
            int index = strings.indexOf(value);
            if (index < 0) { index = strings.size(); strings.add(value); }
            return index;
        }
        void start(String tag, Attr... attrs) {
            word(nodes, 0x102); word(nodes, 16); integer(nodes, 36 + 20 * attrs.length);
            integer(nodes, 1); integer(nodes, -1);
            integer(nodes, -1); integer(nodes, index(tag));
            word(nodes, 20); word(nodes, 20); word(nodes, attrs.length);
            word(nodes, 0); word(nodes, 0); word(nodes, 0);
            for (Attr attr : attrs) {
                integer(nodes, index(attr.ns)); integer(nodes, index(attr.name));
                int value = attr.type == 3 ? index(attr.text) : attr.value;
                integer(nodes, attr.type == 3 ? value : -1);
                word(nodes, 8); nodes.write(0); nodes.write(attr.type); integer(nodes, value);
            }
        }
        void end(String tag) {
            word(nodes, 0x103); word(nodes, 16); integer(nodes, 24);
            integer(nodes, 1); integer(nodes, -1); integer(nodes, -1); integer(nodes, index(tag));
        }
        byte[] encode() {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            List<Integer> offsets = new ArrayList<>();
            for (String value : strings) {
                byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                if (value.length() >= 128 || utf8.length >= 128) throw new IllegalArgumentException("Test string too long");
                offsets.add(data.size()); data.write(value.length()); data.write(utf8.length);
                data.write(utf8, 0, utf8.length); data.write(0);
            }
            while ((data.size() & 3) != 0) data.write(0);
            ByteArrayOutputStream chunks = new ByteArrayOutputStream();
            word(chunks, 1); word(chunks, 28); integer(chunks, 28 + 4 * strings.size() + data.size());
            integer(chunks, strings.size()); integer(chunks, 0); integer(chunks, 0x100);
            integer(chunks, 28 + 4 * strings.size()); integer(chunks, 0);
            for (int offset : offsets) integer(chunks, offset);
            byte[] pool = data.toByteArray(); chunks.write(pool, 0, pool.length);
            word(chunks, 0x180); word(chunks, 8); integer(chunks, 8 + 4 * strings.size());
            for (String value : strings) integer(chunks, resourceId(value));
            byte[] body = nodes.toByteArray(); chunks.write(body, 0, body.length);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            word(output, 3); word(output, 8); integer(output, 8 + chunks.size());
            byte[] content = chunks.toByteArray(); output.write(content, 0, content.length);
            return output.toByteArray();
        }
        private static int resourceId(String name) {
            if ("versionCode".equals(name)) return 0x0101021b;
            if ("minSdkVersion".equals(name)) return 0x0101020c;
            if ("targetSdkVersion".equals(name)) return 0x01010270;
            if ("name".equals(name)) return 0x01010003;
            if ("enabled".equals(name)) return 0x0101000e;
            return 0;
        }
    }

    private static void word(ByteArrayOutputStream output, int value) {
        output.write(value & 255); output.write((value >>> 8) & 255);
    }
    private static void integer(ByteArrayOutputStream output, int value) {
        word(output, value); word(output, value >>> 16);
    }
}
