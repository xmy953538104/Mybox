package io.github.xmy953538104.mybox.engine.installer;

import android.os.Bundle;

interface IVirtualInstallerCallback {
    void onEvent(String event, in Bundle value);
}
