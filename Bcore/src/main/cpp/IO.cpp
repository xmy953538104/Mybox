// Based on BlackBox by Milk; modified for Mybox. SPDX-License-Identifier: Apache-2.0
#include "IO.h"

PathRules &IO::rules() {
    static PathRules rules;
    return rules;
}

jstring IO::redirectPath(JNIEnv *env, jstring path) {
    return BoxCore::redirectPathString(env, path);
}

jobject IO::redirectPath(JNIEnv *env, jobject path) {
    return BoxCore::redirectPathFile(env, path);
}

void IO::init(JNIEnv *) {
    rules();
}
