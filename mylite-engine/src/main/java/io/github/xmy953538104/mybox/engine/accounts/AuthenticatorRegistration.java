package io.github.xmy953538104.mybox.engine.accounts;

import io.github.xmy953538104.mybox.engine.CallerIdentity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Describes an authenticator discovered in a cryptographically verified virtual package. */
public final class AuthenticatorRegistration {
    public final String accountType;
    public final String packageName;
    public final String className;
    public final List<String> certificateSha256;
    public final boolean customTokens;
    public final int labelId, iconId, smallIconId, preferencesId;

    public AuthenticatorRegistration(String type, String packageName, String className,
            List<String> certificates, boolean customTokens, int label, int icon, int smallIcon, int preferences) {
        if (type == null || type.isEmpty() || packageName == null || className == null
                || certificates == null || certificates.isEmpty()) throw new IllegalArgumentException("Incomplete authenticator identity");
        accountType = type;
        this.packageName = packageName;
        this.className = className;
        certificateSha256 = Collections.unmodifiableList(new ArrayList<>(certificates));
        this.customTokens = customTokens;
        labelId = label; iconId = icon; smallIconId = smallIcon; preferencesId = preferences;
    }

    boolean owns(CallerIdentity caller) {
        return caller != null && caller.userId == 0 && packageName.equals(caller.packageName)
                && certificateSha256.equals(caller.certificateSha256);
    }
}
