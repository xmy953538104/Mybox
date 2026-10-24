package io.github.xmy953538104.mybox.engine.accounts;

import java.io.IOException;

/** One committed account snapshot. Production implementations must protect secret bytes at rest. */
public interface AccountStorage {
    byte[] read() throws IOException;
    void write(byte[] snapshot) throws IOException;
}
