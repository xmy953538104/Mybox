package io.github.xmy953538104.mybox.engine.packages;

import java.io.IOException;

public final class PackageValidationException extends IOException {
    public PackageValidationException(String message) { super(message); }
    public PackageValidationException(String message, Throwable cause) { super(message, cause); }
}
