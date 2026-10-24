package io.github.xmy953538104.mybox.engine;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

public final class ManifestIntentsTest extends InstrumentationTestCase {
    private static final String GUEST = "io.github.xmy953538104.mybox.fixture";

    @Override protected void setUp() throws Exception {
        super.setUp();
        if (Engine.get().packages().get(GUEST) != null) return;
        File fixture = new File(getInstrumentation().getTargetContext().getCacheDir(), "intent-fixture.apk");
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                 FileOutputStream output = new FileOutputStream(fixture)) {
                byte[] buffer = new byte[16384];
                for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            }
            Engine.get().install(Collections.singletonList(fixture));
        } finally { fixture.delete(); }
    }

    public void testServiceFiltersComeFromSignedApk() throws Exception {
        List<ResolveInfo> matches = services(new Intent(GUEST + ".REMOTE").setPackage(GUEST), null);
        assertEquals(1, matches.size());
        assertEquals(GUEST + ".RemoteService", matches.get(0).serviceInfo.name);
        assertEquals(7, matches.get(0).priority);
        assertTrue(services(new Intent(GUEST + ".REMOTE").setPackage("another.package"), null).isEmpty());
        assertTrue(services(new Intent(GUEST + ".UNKNOWN").setPackage(GUEST), null).isEmpty());
        assertTrue(services(new Intent(GUEST + ".REMOTE").addCategory("unexpected.category"), null).isEmpty());
    }

    public void testUriAndMimeConstraintsCannotBeDropped() throws Exception {
        Intent request = new Intent(GUEST + ".DATA").setPackage(GUEST)
                .setDataAndType(Uri.parse("mybox://fixture/accepted/item"), "text/plain");
        assertEquals(1, services(request, "text/plain").size());
        assertTrue(services(request, "image/png").isEmpty());
        assertTrue(services(new Intent(request).setDataAndType(Uri.parse("mybox://fixture/rejected/item"), "text/plain"), "text/plain").isEmpty());
        assertTrue(services(new Intent(request).setDataAndType(Uri.parse("mybox://foreign/accepted/item"), "text/plain"), "text/plain").isEmpty());
    }

    private static List<ResolveInfo> services(Intent intent, String type) throws Exception {
        return ManifestIntents.query(Engine.get(), intent, type, PackageManager.GET_SERVICES, 0);
    }
}
