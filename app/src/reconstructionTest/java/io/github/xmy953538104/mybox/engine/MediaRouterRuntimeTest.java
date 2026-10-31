package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import io.github.xmy953538104.mybox.FixtureRun;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;

public final class MediaRouterRuntimeTest extends InstrumentationTestCase {
    public void testRealLegacyAndModernRegistrationRoutesCallbackAndLocalBoundary() throws Exception {
        Context host=getInstrumentation().getTargetContext(); Engine engine=Engine.get();
        InstalledPackage installed=engine.packages().get("io.github.xmy953538104.mybox.fixture");
        if(installed==null) {
            File apk=new File(host.getCacheDir(),"media-fixture.apk");
            try {
                try(InputStream input=getInstrumentation().getContext().getAssets().open("engine-fixture.apk");
                        FileOutputStream output=new FileOutputStream(apk)) {
                    byte[] buffer=new byte[16384];int count;
                    while((count=input.read(buffer))!=-1) output.write(buffer,0,count);
                }
                installed=engine.install(Collections.singletonList(apk));
            } finally { Files.deleteIfExists(apk.toPath()); }
        }
        File result=new File(installed.dataDir,"files/media-router-result.json");
        File retained=new File(host.getFilesDir(),"media-router-runtime-result.json");
        Files.deleteIfExists(result.toPath());Files.deleteIfExists(retained.toPath());
        String nonce=UUID.randomUUID().toString();FixtureRun.prepare(installed,"media-router:"+nonce);
        engine.launch(installed.packageName);
        long deadline=SystemClock.elapsedRealtime()+15000;
        while(!result.isFile() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50);
        assertTrue("No actual MediaRouter result",result.isFile());
        Files.copy(result.toPath(),retained.toPath(),StandardCopyOption.REPLACE_EXISTING);
        JSONObject actual=new JSONObject(new String(Files.readAllBytes(result.toPath()),StandardCharsets.UTF_8));
        assertFalse("Real MediaRouter failure: "+actual,actual.has("error"));
        assertEquals(nonce,actual.getString("nonce"));assertEquals(installed.packageName,actual.getString("package"));
        assertTrue(actual.getInt("pid")>0);assertEquals(actual.getInt("pid"),actual.getInt("callbackPid"));
        for(String field:new String[]{"legacyRouteCount","modernRouteCount","callbackRouteCount"}) assertTrue(field,actual.getInt(field)>0);
        for(String field:new String[]{"legacyDefault","descriptorMatches","localFacade","foreignDenied","rawDenied","cachedInstanceSame","registered","unregistered"})
            assertTrue(field,actual.getBoolean(field));
        System.out.println("MYLITE_MEDIA realRegistrations=true realRoutes=true realCallback=true foreignAndRawDenied=true nonce="+nonce);
    }
}
