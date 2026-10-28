package io.github.xmy953538104.mybox.engine;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;
import io.github.xmy953538104.mybox.engine.packages.SigningIdentity;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Guest-visible comparison only; installation, permissions and account ACLs use real signers. */
final class GuestSignatureQueries {
    private final Engine engine;
    private final Object physical;

    GuestSignatureQueries(Engine engine, Object physical) { this.engine = engine; this.physical = physical; }

    static boolean handles(String operation) {
        return operation.equals("checkSignatures") || operation.equals("checkUidSignatures")
                || operation.equals("checkUidSignaturesForAllUsers");
    }

    int query(String operation, Object[] args) throws Throwable {
        boolean uid = !operation.equals("checkSignatures");
        validateArguments(uid, args);
        String first = uid ? packageForUid((Integer) args[0]) : (String) args[0];
        String second = uid ? packageForUid((Integer) args[1]) : (String) args[1];
        Signers left = signers(first), right = signers(second);
        if (left == null || right == null) return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
        int result = compare(left.current, right.current);
        if (result != PackageManager.SIGNATURE_MATCH && Build.VERSION.SDK_INT >= 30)
            result = compare(left.legacy, right.legacy);
        return result;
    }

    static void validateArguments(boolean uid, Object[] args) {
        if (args == null || args.length < 2 || args.length > (uid ? 2 : 3)
                || (uid ? !(args[0] instanceof Integer) || !(args[1] instanceof Integer)
                        : args[0] != null && !(args[0] instanceof String) || args[1] != null && !(args[1] instanceof String)))
            throw new EngineException("Unknown signature query signature");
        if (!uid && args.length == 3 && !Integer.valueOf(0).equals(args[2]))
            throw new SecurityException("Signature query is outside virtual user zero");
        if (uid && ((Integer) args[0] >= 100000 || (Integer) args[1] >= 100000))
            throw new SecurityException("Signature query UID is outside virtual user zero");
    }

    private String packageForUid(int uid) throws Throwable {
        if (uid < 0) return null;
        for (InstalledPackage installed : engine.packages().list()) if (installed.appId == uid) return installed.packageName;
        // Physical app UIDs must not turn an unknown virtual UID into the host or another app.
        if (uid >= 10000) return null;
        String[] packages = (String[]) invoke("getPackagesForUid", new Class<?>[]{int.class}, uid);
        return packages == null || packages.length == 0 ? null : packages[0];
    }

    private Signers signers(String name) throws Throwable {
        if (name == null) return null;
        InstalledPackage installed = GuestPackageQueries.virtualPackage(engine, name);
        if (installed != null) {
            SigningIdentity verified = engine.packages().verifiedSigning(installed);
            if ("com.google.android.gms".equals(name)) {
                List<String> compatibility = Collections.singletonList(GoogleSignatureView.hash("SHA-256",
                        GoogleSignatureView.compatibilitySignature(engine, installed)));
                return new Signers(compatibility, compatibility);
            }
            return new Signers(verified.currentSigners, verified.legacyQuerySigners());
        }
        if (GuestPackageQueries.isGoogleIdentity(name)) return null;
        int flags = PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES;
        Class<?> flagType = Build.VERSION.SDK_INT >= 33 ? long.class : int.class;
        Object value = flagType == long.class ? (Object) Long.valueOf(flags) : Integer.valueOf(flags);
        PackageInfo info = (PackageInfo) invoke("getPackageInfo", new Class<?>[]{String.class, flagType, int.class}, name, value, 0);
        if (info == null) return null;
        Signature[] current = info.signatures, legacy = current;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            current = info.signingInfo.getApkContentsSigners();
            legacy = info.signingInfo.hasPastSigningCertificates()
                    ? new Signature[]{info.signingInfo.getSigningCertificateHistory()[0]} : current;
        }
        return new Signers(hashes(current), hashes(legacy));
    }

    private Object invoke(String operation, Class<?>[] types, Object... args) throws Throwable {
        Method method = Class.forName("android.content.pm.IPackageManager").getMethod(operation, types);
        try { return method.invoke(physical, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static List<String> hashes(Signature[] signatures) {
        List<String> result = new ArrayList<>();
        if (signatures != null) for (Signature signature : signatures) result.add(GoogleSignatureView.hash("SHA-256", signature));
        return result;
    }

    static int compare(List<String> first, List<String> second) {
        if (first.isEmpty()) return second.isEmpty() ? PackageManager.SIGNATURE_NEITHER_SIGNED : PackageManager.SIGNATURE_FIRST_NOT_SIGNED;
        if (second.isEmpty()) return PackageManager.SIGNATURE_SECOND_NOT_SIGNED;
        return first.size() == second.size() && new HashSet<>(first).equals(new HashSet<>(second))
                ? PackageManager.SIGNATURE_MATCH : PackageManager.SIGNATURE_NO_MATCH;
    }

    private static final class Signers {
        final List<String> current, legacy;
        Signers(List<String> current, List<String> legacy) { this.current = current; this.legacy = legacy; }
    }
}
