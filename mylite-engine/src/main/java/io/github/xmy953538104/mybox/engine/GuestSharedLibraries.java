package io.github.xmy953538104.mybox.engine;

import android.content.pm.ApplicationInfo;
import android.content.pm.SharedLibraryInfo;
import android.os.Build;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Feed Android's own library metadata to LoadedApk, rather than merging system JARs into guest code. */
final class GuestSharedLibraries {
    static Object[] queryArguments(Object[] arguments, String host, String guest, int hostUser) {
        if (arguments == null || arguments.length != 3 || !(arguments[0] instanceof String)
                || !(arguments[1] instanceof Integer || arguments[1] instanceof Long)
                || !(arguments[2] instanceof Integer)) throw new EngineException("Unknown shared-library query signature");
        int user = (Integer) arguments[2];
        if (host.equals(arguments[0])) {
            if (user != hostUser) throw new SecurityException("Shared-library host query targets another user");
        } else if (guest == null || !guest.equals(arguments[0]) || user != 0) {
            throw new SecurityException("Shared-library query differs from the bound guest");
        }
        Object[] physical = arguments.clone(); physical[0] = host; physical[2] = hostUser;
        // The real host's permissions decide library visibility; do not request the privileged match view.
        if (arguments[1] instanceof Long) physical[1] = (Long) arguments[1] & ~0x00400000L;
        else physical[1] = (Integer) arguments[1] & ~0x00400000;
        return physical;
    }

    static void apply(Engine engine, ApplicationInfo application) throws Exception {
        Map<String, Boolean> requested = engine.packages().sharedLibraries(application.packageName);
        if (application.targetSdkVersion < 28) requested.putIfAbsent("org.apache.http.legacy", false);
        if (requested.isEmpty()) return;
        List<SharedLibraryInfo> platform = engine.hostContext().getPackageManager().getSharedLibraries(0);
        if (platform == null) throw new EngineException("Android shared library inventory is unavailable");
        List<SharedLibraryInfo> selected = new ArrayList<>(); LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Map.Entry<String, Boolean> requirement : requested.entrySet()) {
            SharedLibraryInfo match = null;
            for (SharedLibraryInfo candidate : platform) {
                if (candidate.getType() == SharedLibraryInfo.TYPE_BUILTIN && requirement.getKey().equals(candidate.getName())) { match = candidate; break; }
            }
            if (match == null) {
                if (requirement.getValue()) throw new UnsupportedOperationException("Required system shared library is unavailable: " + requirement.getKey());
                continue;
            }
            @SuppressWarnings("unchecked") List<String> code = (List<String>) RuntimeReflection.invoke(match, RuntimeReflection.method(SharedLibraryInfo.class, "getAllCodePaths"));
            if (code == null || code.isEmpty()) throw new EngineException("System shared library has no code paths");
            for (String path : code) {
                if (path == null || !new File(path).isFile()) throw new EngineException("System shared library file is unavailable");
                paths.add(path);
            }
            selected.add(match);
        }
        application.sharedLibraryFiles = paths.isEmpty() ? null : paths.toArray(new String[0]);
        if (Build.VERSION.SDK_INT >= 29) RuntimeReflection.set(application, "sharedLibraryInfos", selected);
    }
}
