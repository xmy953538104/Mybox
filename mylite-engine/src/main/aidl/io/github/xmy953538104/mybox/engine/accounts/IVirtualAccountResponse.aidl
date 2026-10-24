package io.github.xmy953538104.mybox.engine.accounts;

import android.os.Bundle;

// Synchronous callbacks preserve the real Binder PID for identity validation.
interface IVirtualAccountResponse {
    void onResult(in Bundle result);
    void onError(int code, String message);
}
