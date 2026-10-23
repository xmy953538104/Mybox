package io.github.xmy953538104.mybox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.fake.service.IActivityManagerProxy;

public final class ServiceBindingTest extends InstrumentationTestCase {
    public void testGuestBindingAndRealServiceArguments() throws Throwable {
        String probe = "io.github.xmy953538104.mybox.probe";
        Context target = getInstrumentation().getTargetContext();
        ((MyboxApplication) target.getApplicationContext()).requireEngine();
        File apk = new File(target.getCacheDir(), "binding-probe.apk");
        File report = new File(BEnvironment.getDataFilesDir(probe, 0), "service-binding-result.json");
        report.delete();
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("probe.apk")) {
                MainActivity.copyApk(input, apk);
            }
            InstallResult installed = BlackBoxCore.getBPackageManager().installPackageSetAsUser(
                    new String[]{apk.getAbsolutePath()}, InstallOption.installByStorage(), 0, target.getPackageName());
            assertNotNull(installed);
            assertTrue("Binding probe install failed: " + installed.msg, installed.success);

            // The allowlisted real service must retain its instance name and all 64 flag bits.
            RealServiceRecorder recorder = new RealServiceRecorder();
            Method method = RealServiceRecorder.class.getMethod("bindServiceInstance", Object.class,
                    Object.class, Intent.class, String.class, Object.class, long.class,
                    String.class, String.class, int.class);
            long flags = (1L << 40) | Context.BIND_AUTO_CREATE;
            Intent realService = new Intent().setPackage("com.google.android.webview");
            new ExposedBindService().invoke(recorder, method,
                    new Object[]{null, null, realService, null, null, flags, probe, probe, 0});
            assertEquals("Real isolated instance changed", probe, recorder.instanceName);
            assertEquals("Long bind flags truncated", flags, recorder.flags);
            assertEquals("Real call must use host package", target.getPackageName(), recorder.callingPackage);
            assertSame(realService, recorder.intent);

            Intent launch = new Intent().setComponent(new ComponentName(probe, probe + ".ServiceBindingProbeActivity"));
            BlackBoxCore.get().startActivity(launch, 0);
            long deadline = android.os.SystemClock.elapsedRealtime() + 45000;
            while (!report.isFile() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200);
            assertTrue("Guest binding probe did not report", report.isFile());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(report)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            assertTrue(result.toString(), result.optBoolean("passed"));
            assertTrue(result.toString(), result.optBoolean("intFlagsCrossProcess"));
            if (Build.VERSION.SDK_INT >= 34) assertTrue(result.toString(), result.optBoolean("longFlagsCrossProcess"));
        } finally {
            apk.delete();
            BlackBoxCore.get().uninstallPackageAsUser(probe, 0);
        }
    }

    private static final class ExposedBindService extends IActivityManagerProxy.BindService {
        Object invoke(Object who, Method method, Object[] args) throws Throwable { return hook(who, method, args); }
    }

    public static final class RealServiceRecorder {
        Intent intent;
        long flags;
        String instanceName;
        String callingPackage;

        public int bindServiceInstance(Object caller, Object token, Intent intent, String resolvedType,
                Object connection, long flags, String instanceName, String callingPackage, int userId) {
            this.intent = intent;
            this.flags = flags;
            this.instanceName = instanceName;
            this.callingPackage = callingPackage;
            return 1;
        }
    }
}
