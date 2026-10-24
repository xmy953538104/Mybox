package io.github.xmy953538104.mybox.engine;

/** A failed engine operation. Failure must not be reported as a successful guest launch. */
public final class EngineException extends RuntimeException {
    public EngineException(String message) { super(message); }
    public EngineException(String message, Throwable cause) { super(message, cause); }
}
