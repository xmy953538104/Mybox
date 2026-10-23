//
// Created by Milk on 4/9/21.
//

#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include <JniHook/JniHook.h>
#include <Hook/UnixFileSystemHook.h>
#include <Hook/LibcHook.h>

struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    jmethodID loadEmptyDex;
    jmethodID loadEmptyDexL;
    int api_level;
} VMEnv;


JNIEnv *getEnv() {
    JNIEnv *env = nullptr;
    VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

JNIEnv *ensureEnvCreated() {
    JNIEnv *env = getEnv();
    if (env == NULL) {
        if (VMEnv.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    env = ensureEnvCreated();
    return (jstring) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    env = ensureEnvCreated();
    return env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);
}

jlongArray BoxCore::loadEmptyDex(JNIEnv *env) {
    env = ensureEnvCreated();
    return (jlongArray) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.loadEmptyDex);
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    BaseHook::init(env);
    UnixFileSystemHook::init(env);
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.NativeCoreClass = (jclass) env->NewGlobalRef(env->FindClass(VMCORE_CLASS));
    VMEnv.redirectPathString = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                      "(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                    "(Ljava/io/File;)Ljava/io/File;");
    VMEnv.loadEmptyDex = env->GetStaticMethodID(VMEnv.NativeCoreClass, "loadEmptyDex",
                                                "()[J");

    if (!JniHook::InitJniHook(env, api_level) && !env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass("java/lang/UnsupportedOperationException"),
                      "Mybox: ART JNI layout calibration failed; collect NativeCore logcat for this Android build");
    }
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path,
               jstring relocate_path) {
    if (!target_path || !relocate_path) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Null I/O rule");
        return;
    }
    const char *source = env->GetStringUTFChars(target_path, nullptr);
    if (!source) return;
    const char *destination = env->GetStringUTFChars(relocate_path, nullptr);
    if (!destination) {
        env->ReleaseStringUTFChars(target_path, source);
        return;
    }
    const bool added = IO::rules().add(source, destination);
    env->ReleaseStringUTFChars(target_path, source);
    env->ReleaseStringUTFChars(relocate_path, destination);
    if (!added) env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid I/O rule");
}

void addIOWhitelist(JNIEnv *env, jclass, jstring path) {
    if (!path) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Null I/O whitelist");
        return;
    }
    const char *value = env->GetStringUTFChars(path, nullptr);
    if (!value) return;
    const bool added = IO::rules().allow(value);
    env->ReleaseStringUTFChars(path, value);
    if (!added) env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid I/O whitelist");
}

void enableIO(JNIEnv *env, jclass clazz) {
    IO::init(env);
    if (!LibcHook::init()) {
        env->ThrowNew(env->FindClass("java/lang/UnsupportedOperationException"),
                      "Mybox: native filesystem hooks unavailable; inspect NativeCore logcat");
        return;
    }
    nativeHook(env);
}

static JNINativeMethod gMethods[] = {
        {"addIORule",  "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"addIOWhitelist", "(Ljava/lang/String;)V", (void *) addIOWhitelist},
        {"enableIO",   "()V",                                     (void *) enableIO},
        {"init",       "(I)V",                                    (void *) init},
};

int registerNativeMethods(JNIEnv *env, const char *className,
                          JNINativeMethod *gMethods, int numMethods) {
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == nullptr) {
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

int registerNatives(JNIEnv *env) {
    if (!registerNativeMethods(env, VMCORE_CLASS, gMethods,
                               sizeof(gMethods) / sizeof(gMethods[0])))
        return JNI_FALSE;
    return JNI_TRUE;
}

void registerMethod(JNIEnv *jenv) {
    registerNatives(jenv);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    VMEnv.vm = vm;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_EVERSION;
    }
    if (!registerNatives(env)) return JNI_ERR;
    return JNI_VERSION_1_6;
}
