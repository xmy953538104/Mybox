package io.github.xmy953538104.mybox.engine.accounts;

import android.os.Bundle;
import io.github.xmy953538104.mybox.engine.accounts.IVirtualAccountResponse;

interface IVirtualAccountService {
    Bundle execute(String operation, in Bundle request, IVirtualAccountResponse response);
}
