package top.niunaijun.blackbox.fake.service;

import android.content.pm.FeatureInfo;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class PackageFeaturesTest {
    @Test public void onlyUnsupportedIncrementalDeliveryIsRemoved() {
        FeatureInfo camera = new FeatureInfo(); camera.name = "android.hardware.camera";
        FeatureInfo incremental = new FeatureInfo(); incremental.name = "android.software.incremental_delivery";
        FeatureInfo gles = new FeatureInfo(); gles.reqGlEsVersion = 0x30000;
        List<FeatureInfo> source = Arrays.asList(camera, incremental, gles);
        List<FeatureInfo> result = IPackageManagerProxy.supportedFeatures(source);
        assertEquals(3, source.size());
        assertEquals(2, result.size());
        assertSame(camera, result.get(0));
        assertSame(gles, result.get(1));
    }
}
