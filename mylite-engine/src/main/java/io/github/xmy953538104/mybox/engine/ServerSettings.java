package io.github.xmy953538104.mybox.engine;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** A virtual permission grants access to container state, never to the physical SettingsProvider. */
final class ServerSettings {
    private final Engine engine;
    private final SettingsStore store;

    ServerSettings(Engine engine) throws IOException {
        this.engine = engine;
        store = new SettingsStore(new File(engine.hostContext().getFilesDir(), "mylite-settings/user0.properties"));
    }

    Bundle route(Bundle input, CallerIdentity caller) throws Exception {
        if (input == null || input.getInt("protocol") != EngineProtocol.VERSION || input.getInt("user", -1) != 0
                || caller.userId != 0 || !caller.packageName.equals(input.getString("package")))
            throw new SecurityException("Invalid container settings caller or user");
        String domain = input.getString("domain"), name = input.getString("name"), value = input.getString("value");
        SettingsStore.validate(domain, name, value);
        String operation = input.getString("operation");
        Bundle result = EngineProtocol.response();
        if ("get".equals(operation)) {
            result.putBoolean("present", store.contains(domain, name));
            if (result.getBoolean("present")) result.putString("value", store.get(domain, name));
        } else if ("put".equals(operation)) {
            InstalledPackage installed = engine.packages().get(caller.packageName);
            if (installed == null || installed.appId != caller.appId || !installed.certificateSha256.equals(caller.certificateSha256))
                throw new SecurityException("Container settings publisher changed");
            PackageInfo info = engine.packages().getPackageInfo(caller.packageName, PackageManager.GET_PERMISSIONS);
            if (info == null || info.requestedPermissions == null
                    || !Arrays.asList(info.requestedPermissions).contains("android.permission.WRITE_SECURE_SETTINGS"))
                throw new SecurityException("Container settings permission is not declared");
            if ("com.google.android.gms".equals(caller.packageName)) TrustedMicrog.require(engine.hostContext(), installed);
            else new InstallerPolicy(engine).requireApproved(caller);
            store.put(domain, name, value);
        } else throw new IllegalArgumentException("Unknown container settings operation");
        return result;
    }
}
