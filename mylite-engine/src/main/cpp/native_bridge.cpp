// SPDX-License-Identifier: Apache-2.0
#include "native_io.h"
#include <jni.h>
#include <cerrno>
#include <mutex>

namespace {
std::mutex configuration_mutex;
bool configured = false;
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBridge_nativeInitialize(
        JNIEnv* env, jclass, jobjectArray sources, jobjectArray destinations) {
    std::lock_guard<std::mutex> lock(configuration_mutex);
    if (configured) return EALREADY;
    if (!sources || !destinations || env->GetArrayLength(sources) != env->GetArrayLength(destinations)) return EINVAL;
    int length = env->GetArrayLength(sources);
    if (length <= 0 || length > mybox::kMaxRules) return EINVAL;
    configured = true;
    for (int i = 0; i < length; ++i) {
        auto source = static_cast<jstring>(env->GetObjectArrayElement(sources, i));
        auto destination = static_cast<jstring>(env->GetObjectArrayElement(destinations, i));
        if (!source || !destination) return EINVAL;
        const char* a = env->GetStringUTFChars(source, nullptr);
        const char* b = env->GetStringUTFChars(destination, nullptr);
        if (!a || !b) {
            if (a) env->ReleaseStringUTFChars(source, a);
            if (b) env->ReleaseStringUTFChars(destination, b);
            return ENOMEM;
        }
        int error = mybox::ConfiguredPaths().Add(a, b);
        env->ReleaseStringUTFChars(source, a); env->ReleaseStringUTFChars(destination, b);
        env->DeleteLocalRef(source); env->DeleteLocalRef(destination);
        if (error != 0) return error;
    }
    return mybox::InstallIo();
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBridge_nativeQueryPath(JNIEnv* env, jclass, jstring path) {
    std::lock_guard<std::mutex> lock(configuration_mutex);
    if (!path) return nullptr;
    const char* value = env->GetStringUTFChars(path, nullptr);
    if (!value) return nullptr;
    char redirected[mybox::kPathSize];
    int result = mybox::ConfiguredPaths().Rewrite(value, redirected);
    env->ReleaseStringUTFChars(path, value);
    if (result < 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Path cannot be redirected");
        return nullptr;
    }
    return env->NewStringUTF(redirected);
}
