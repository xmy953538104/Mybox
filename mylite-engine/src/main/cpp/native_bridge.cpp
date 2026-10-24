// SPDX-License-Identifier: Apache-2.0
#include "native_io.h"
#include "native_install_guard.h"
#include <jni.h>
#include <cerrno>
#include <cstdio>
#include <mutex>

namespace {
std::mutex configuration_mutex;
bool configured = false;
void LogInstallDiagnostics(JNIEnv* env, int error) {
    // Run only after peers have been released. Diagnostics must not replace a pending JNI exception.
    if (env->ExceptionCheck()) return;
    jclass log = env->FindClass("android/util/Log");
    if (!log) { env->ExceptionClear(); return; }
    jmethodID write = env->GetStaticMethodID(log, "i", "(Ljava/lang/String;Ljava/lang/String;)I");
    if (!write) { env->ExceptionClear(); env->DeleteLocalRef(log); return; }
    jstring tag = env->NewStringUTF("MyliteNative");
    if (!tag) { env->ExceptionClear(); env->DeleteLocalRef(log); return; }
    auto emit = [&](const char* line) {
        jstring message = env->NewStringUTF(line);
        if (message) {
            env->CallStaticIntMethod(log, write, tag, message);
            env->DeleteLocalRef(message);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    };
    const auto& io = mybox::GetIoInstallDiagnostics();
    const auto guard = mybox::GetInstallGuardDiagnostics();
    char line[512];
    std::snprintf(line, sizeof(line),
            "I/O install diagnostic: errno=%d stage=%s tsync_called=%d tsync_result=%ld "
            "guard_stage=%s cause=%d cleanup=%d signal=%d threads=%u targets=%u ack=%u "
            "tid=%d blocked=%016llx pending=%016llx mask_errno=%d",
            error, io.stage, io.seccomp_called, io.seccomp_result, guard.stage,
            guard.cause_errno, guard.cleanup_errno, guard.signal, guard.threads, guard.targets,
            guard.acknowledged, guard.tid, static_cast<unsigned long long>(guard.blocked),
            static_cast<unsigned long long>(guard.pending), guard.mask_errno);
    emit(line);
    std::snprintf(line, sizeof(line), "I/O readiness diagnostic: checks=%u waits=%u elapsed_ns=%llu "
            "first_blocked_tid=%d first_blocked=%016llx", guard.readiness_checks, guard.readiness_waits,
            static_cast<unsigned long long>(guard.readiness_elapsed_ns), guard.readiness_first_tid,
            static_cast<unsigned long long>(guard.readiness_first_mask));
    emit(line);
    for (const auto& candidate : guard.candidates) {
        std::snprintf(line, sizeof(line), "I/O carrier diagnostic: signal=%d reason=%s tid=%d "
                "blocked=%016llx pending=%016llx", candidate.signal, candidate.reason, candidate.tid,
                static_cast<unsigned long long>(candidate.blocked), static_cast<unsigned long long>(candidate.pending));
        emit(line);
    }
    env->DeleteLocalRef(tag);
    env->DeleteLocalRef(log);
}
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBridge_nativeInitialize(
        JNIEnv* env, jclass, jobjectArray sources, jobjectArray destinations) {
    std::lock_guard<std::mutex> lock(configuration_mutex);
    if (configured) return EALREADY;
    if (!sources || !destinations || env->GetArrayLength(sources) != env->GetArrayLength(destinations)) return EINVAL;
    int length = env->GetArrayLength(sources);
    if (length <= 0 || static_cast<std::size_t>(length) > mybox::kMaxRules) return EINVAL;
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
    const int error = mybox::InstallIo();
    LogInstallDiagnostics(env, error);
    return error;
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
