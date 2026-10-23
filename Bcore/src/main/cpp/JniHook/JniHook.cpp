// ART offsets are calibrated from this build's methods and fields before any write.
#include <jni.h>
#include <algorithm>
#include <cstring>
#include "JniHook.h"
#include "Layout.h"
#include "Log.h"

static struct {
    int api_level;
    size_t method_size;
    size_t field_size;
    size_t method_flags = art_layout::missing;
    size_t field_flags = art_layout::missing;
    size_t native_offset = art_layout::missing;
    bool ready;
} HookEnv;

static void fail(JNIEnv *env, const char *message) {
    ALOGE("%s", message);
    if (!env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass("java/lang/UnsupportedOperationException"), message);
    }
}

static void *getArtMethod(JNIEnv *env, jclass clazz, jmethodID method, bool is_static) {
    if (!method) return nullptr;
    if (HookEnv.api_level < 29) return method;
    jclass executable = env->FindClass("java/lang/reflect/Executable");
    if (!executable) return nullptr;
    jfieldID field = env->GetFieldID(executable, "artMethod", "J");
    jobject reflected = field ? env->ToReflectedMethod(clazz, method, is_static) : nullptr;
    void *result = reflected ? reinterpret_cast<void *>(env->GetLongField(reflected, field)) : nullptr;
    if (reflected) env->DeleteLocalRef(reflected);
    env->DeleteLocalRef(executable);
    return result;
}

static void *getArtField(JNIEnv *env, jobject field) {
    if (!field) return nullptr;
    if (HookEnv.api_level < 29) return env->FromReflectedField(field);
    jclass field_class = env->FindClass("java/lang/reflect/Field");
    if (!field_class) return nullptr;
    jmethodID getter = env->GetMethodID(field_class, "getArtField", "()J");
    void *result = getter ? reinterpret_cast<void *>(env->CallLongMethod(field, getter)) : nullptr;
    env->DeleteLocalRef(field_class);
    return result;
}

static uint32_t &flagsAt(void *record, size_t offset) {
    return *reinterpret_cast<uint32_t *>(static_cast<char *>(record) + offset);
}

bool JniHook::HookJniFun(JNIEnv *env, const char *class_name, const char *method_name,
                       const char *signature, void *replacement, void **original,
                       bool is_static, void *critical_replacement) {
    if (!HookEnv.ready || env->ExceptionCheck()) return false;
    jclass clazz = env->FindClass(class_name);
    if (!clazz) return false;
    jmethodID method = is_static ? env->GetStaticMethodID(clazz, method_name, signature)
                                : env->GetMethodID(clazz, method_name, signature);
    void *record = getArtMethod(env, clazz, method, is_static);
    if (!record || env->ExceptionCheck()) {
        env->DeleteLocalRef(clazz);
        return false;
    }
    auto &flags = flagsAt(record, HookEnv.method_flags);
    if (!(flags & kAccNative)) {
        fail(env, "Mybox: requested JNI hook is not a native method");
        env->DeleteLocalRef(clazz);
        return false;
    }
    if (flags & kAccCriticalNative) {
        if (!critical_replacement) {
            fail(env, "Mybox: CriticalNative hook needs a matching native ABI");
            env->DeleteLocalRef(clazz);
            return false;
        }
        replacement = critical_replacement;
    }
    std::memcpy(original, static_cast<char *>(record) + HookEnv.native_offset, sizeof(void *));
    if (!*original) {
        fail(env, "Mybox: JNI method has no resolved original entry point");
        env->DeleteLocalRef(clazz);
        return false;
    }
    uint32_t previous_flags = flags;
    if (HookEnv.api_level < 28) flags &= ~kAccFastNative;
    JNINativeMethod native = {const_cast<char *>(method_name), const_cast<char *>(signature), replacement};
    int result = env->RegisterNatives(clazz, &native, 1);
    flags = previous_flags;
    env->DeleteLocalRef(clazz);
    if (result < 0) {
        ALOGE("JNI registration failed: %s.%s", class_name, method_name);
        return false;
    }
    ALOGD("JNI hook installed: %s.%s", class_name, method_name);
    return true;
}

static void JNICALL native_offset(JNIEnv *, jclass) {}
static void JNICALL native_offset2(JNIEnv *, jclass) {}

static void JNICALL set_method_accessible(JNIEnv *env, jclass, jclass clazz, jobject method) {
    if (!HookEnv.ready) { fail(env, "Mybox: ART layout is not initialized"); return; }
    jclass method_class = env->FindClass("java/lang/reflect/Method");
    if (!method_class) return;
    jmethodID get_modifiers = env->GetMethodID(method_class, "getModifiers", "()I");
    int modifiers = get_modifiers ? env->CallIntMethod(method, get_modifiers) : 0;
    env->DeleteLocalRef(method_class);
    if (env->ExceptionCheck()) return;
    void *record = getArtMethod(env, clazz, env->FromReflectedMethod(method), (modifiers & kAccStatic) != 0);
    if (!record || env->ExceptionCheck()) return;
    auto &flags = flagsAt(record, HookEnv.method_flags);
    flags = (flags & ~(kAccPrivate | kAccProtected)) | kAccPublic;
    if (HookEnv.api_level >= 29) flags |= kAccPublicApi;
}

static void JNICALL set_field_accessible(JNIEnv *env, jclass, jclass, jobject field) {
    if (!HookEnv.ready) { fail(env, "Mybox: ART layout is not initialized"); return; }
    void *record = getArtField(env, field);
    if (!record || env->ExceptionCheck()) return;
    auto &flags = flagsAt(record, HookEnv.field_flags);
    flags = (flags & ~(kAccPrivate | kAccProtected | kAccFinal)) | kAccPublic;
    if (HookEnv.api_level >= 29) flags |= kAccPublicApi;
}

bool JniHook::InitJniHook(JNIEnv *env, int api_level) {
    if (HookEnv.ready) return true;
    HookEnv.api_level = api_level;
    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (!clazz) return false;
    JNINativeMethod natives[] = {
        {const_cast<char *>("nativeOffset"), const_cast<char *>("()V"), reinterpret_cast<void *>(native_offset)},
        {const_cast<char *>("nativeOffset2"), const_cast<char *>("()V"), reinterpret_cast<void *>(native_offset2)},
        {const_cast<char *>("setAccessible"), const_cast<char *>("(Ljava/lang/Class;Ljava/lang/reflect/Method;)V"), reinterpret_cast<void *>(set_method_accessible)},
        {const_cast<char *>("setAccessible"), const_cast<char *>("(Ljava/lang/Class;Ljava/lang/reflect/Field;)V"), reinterpret_cast<void *>(set_field_accessible)},
    };
    if (env->RegisterNatives(clazz, natives, sizeof(natives) / sizeof(natives[0])) < 0) return false;
    jmethodID method_id = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID method_id2 = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");
    jfieldID field_id = env->GetStaticFieldID(clazz, "NATIVE_OFFSET", "I");
    jfieldID field_id2 = env->GetStaticFieldID(clazz, "NATIVE_OFFSET_2", "I");
    if (!method_id || !method_id2 || !field_id || !field_id2 || env->ExceptionCheck()) return false;
    auto method = reinterpret_cast<uintptr_t>(getArtMethod(env, clazz, method_id, true));
    auto method2 = reinterpret_cast<uintptr_t>(getArtMethod(env, clazz, method_id2, true));
    jobject field_object = env->ToReflectedField(clazz, field_id, true);
    jobject field_object2 = env->ToReflectedField(clazz, field_id2, true);
    auto field = reinterpret_cast<uintptr_t>(getArtField(env, field_object));
    auto field2 = reinterpret_cast<uintptr_t>(getArtField(env, field_object2));
    if (field_object) env->DeleteLocalRef(field_object);
    if (field_object2) env->DeleteLocalRef(field_object2);
    env->DeleteLocalRef(clazz);
    if (!method || !method2 || !field || !field2 || env->ExceptionCheck()) return false;
    HookEnv.method_size = std::max(method, method2) - std::min(method, method2);
    HookEnv.field_size = std::max(field, field2) - std::min(field, field2);
    if (HookEnv.method_size < sizeof(void *) || HookEnv.method_size > 256 ||
        HookEnv.field_size < sizeof(uint32_t) || HookEnv.field_size > 128) {
        ALOGE("Unexpected ART record sizes: method=%zu field=%zu", HookEnv.method_size, HookEnv.field_size);
        return false;
    }
    HookEnv.native_offset = art_layout::findOffset(reinterpret_cast<void *>(method), HookEnv.method_size,
                                                   reinterpret_cast<uintptr_t>(native_offset));
    size_t native_offset2_pos = art_layout::findOffset(reinterpret_cast<void *>(method2), HookEnv.method_size,
                                                       reinterpret_cast<uintptr_t>(native_offset2));
    // Ignore runtime flags that changed across ART versions; validate both calibration records.
    constexpr uint32_t method_flags = kAccPublic | kAccStatic | kAccFinal | kAccNative;
    constexpr uint32_t field_flags = kAccPublic | kAccStatic | kAccFinal;
    HookEnv.method_flags = art_layout::findOffset(reinterpret_cast<void *>(method), HookEnv.method_size,
                                                 method_flags, uint32_t(0xffff));
    HookEnv.field_flags = art_layout::findOffset(reinterpret_cast<void *>(field), HookEnv.field_size,
                                                field_flags, uint32_t(0xffff));
    if (HookEnv.native_offset == art_layout::missing || HookEnv.native_offset != native_offset2_pos ||
        HookEnv.method_flags == art_layout::missing || HookEnv.field_flags == art_layout::missing ||
        (flagsAt(reinterpret_cast<void *>(method2), HookEnv.method_flags) & 0xffff) != method_flags ||
        (flagsAt(reinterpret_cast<void *>(field2), HookEnv.field_flags) & 0xffff) != field_flags) {
        ALOGE("ART calibration failed: native=%zu method_flags=%zu field_flags=%zu",
              HookEnv.native_offset, HookEnv.method_flags, HookEnv.field_flags);
        return false;
    }
    HookEnv.ready = true;
    ALOGD("ART JNI layout ready: API %d native=%zu method_flags=%zu field_flags=%zu",
          api_level, HookEnv.native_offset, HookEnv.method_flags, HookEnv.field_flags);
    return true;
}
