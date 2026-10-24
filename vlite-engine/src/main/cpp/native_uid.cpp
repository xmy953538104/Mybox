// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <pthread.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

namespace {
using JniGetter = jint (*)(JNIEnv*, jclass);
using CriticalGetter = jint (*)();
constexpr int kMarkerOne = 0x4d425531;
constexpr int kMarkerTwo = 0x4d425532;
constexpr unsigned kMaxScopes = 16;
struct Frame { std::uint64_t token; int uid, pid, virtual_uid; std::int64_t generation; };
struct Scopes { unsigned depth; Frame frames[kMaxScopes]; };
std::mutex install_mutex;
std::atomic<bool> ready{false};
std::atomic<std::uint64_t> calls{0}, next_token{1};
static_assert(std::atomic<std::uint64_t>::is_always_lock_free, "CriticalNative counters must not lock");
pthread_key_t scope_key;
bool installed = false;
bool attempted = false;
bool uid_critical = false, pid_critical = false;
void* original_uid = nullptr;
void* original_pid = nullptr;
jclass binder_class = nullptr;
int real_uid = -1, entry_offset = -1;
const char* install_stage = "not started";

jint MarkerOne(JNIEnv*, jclass) { return kMarkerOne; }
jint MarkerTwo(JNIEnv*, jclass) { return kMarkerTwo; }

bool Read(const void* source, void* destination, std::size_t length) {
    iovec local{destination, length}, remote{const_cast<void*>(source), length};
    return syscall(__NR_process_vm_readv, getpid(), &local, 1, &remote, 1, 0)
            == static_cast<long>(length);
}

bool RuntimeEntry(void* entry) {
    Dl_info location{};
    if (!entry || dladdr(entry, &location) == 0 || !location.dli_fname) return false;
    const char* basename = std::strrchr(location.dli_fname, '/');
    basename = basename ? basename + 1 : location.dli_fname;
    return std::strcmp(basename, "libandroid_runtime.so") == 0;
}

jint OriginalUid(JNIEnv* env, jclass owner) {
    return uid_critical ? reinterpret_cast<CriticalGetter>(original_uid)()
            : reinterpret_cast<JniGetter>(original_uid)(env, owner);
}
jint OriginalPid(JNIEnv* env, jclass owner) {
    return pid_critical ? reinterpret_cast<CriticalGetter>(original_pid)()
            : reinterpret_cast<JniGetter>(original_pid)(env, owner);
}

jint View(jint uid, jint pid) {
    calls.fetch_add(1, std::memory_order_relaxed);
    // pthread_getspecific is a read only lookup. Allocation happens only in ordinary JNI entry.
    auto* state = static_cast<Scopes*>(pthread_getspecific(scope_key));
    if (!state || state->depth == 0) return uid;
    const Frame& frame = state->frames[state->depth - 1];
    return frame.virtual_uid >= 10000 && frame.generation > 0 && frame.uid == uid
            && frame.pid > 0 && frame.pid == pid ? frame.virtual_uid : uid;
}

// Exact ABI matters: CriticalNative has neither JNIEnv* nor jclass.
jint CriticalUid() {
    (void)ready.load(std::memory_order_acquire);
    return View(reinterpret_cast<CriticalGetter>(original_uid)(),
            reinterpret_cast<CriticalGetter>(original_pid)());
}
jint JniUid(JNIEnv* env, jclass owner) {
    (void)ready.load(std::memory_order_acquire);
    return View(OriginalUid(env, owner), OriginalPid(env, owner));
}

void Throw(JNIEnv* env, const char* type, const char* message) {
    jclass cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message);
}

Scopes* CurrentScopes(JNIEnv* env) {
    auto* state = static_cast<Scopes*>(pthread_getspecific(scope_key));
    if (!state) {
        state = static_cast<Scopes*>(std::calloc(1, sizeof(Scopes)));
        if (!state) { Throw(env, "java/lang/OutOfMemoryError", "Binder scope allocation failed"); return nullptr; }
        int error = pthread_setspecific(scope_key, state);
        if (error != 0) {
            std::free(state);
            Throw(env, "java/lang/IllegalStateException", "Binder scope TLS is unavailable");
            return nullptr;
        }
    }
    if (state->depth == kMaxScopes) {
        Throw(env, "java/lang/IllegalStateException", "Binder scope nesting limit reached");
        return nullptr;
    }
    return state;
}

jlong Enter(JNIEnv* env, jint uid, jint pid, jint virtual_uid, jlong generation) {
    if (!ready.load(std::memory_order_acquire)) {
        Throw(env, "java/lang/IllegalStateException", "Binder native entry is unavailable"); return 0;
    }
    Scopes* state = CurrentScopes(env);
    if (!state) return 0;
    std::uint64_t token = next_token.fetch_add(1, std::memory_order_relaxed);
    if (token == 0) token = next_token.fetch_add(1, std::memory_order_relaxed);
    state->frames[state->depth++] = {token, uid, pid, virtual_uid, generation};
    return static_cast<jlong>(token);
}
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBinderIdentity_nativeInstall(
        JNIEnv* env, jclass owner, jlongArray methods, jboolean critical_uid,
        jboolean critical_pid, jint process_uid) {
    std::lock_guard<std::mutex> lock(install_mutex);
    if (installed) return 0;
    if (attempted) return EALREADY;
    attempted = true;
    install_stage = "validate input";
    if (sizeof(void*) != 8 || !methods || env->GetArrayLength(methods) != 4
            || process_uid != static_cast<jint>(getuid()) || (critical_uid && !critical_pid)) return EINVAL;
    jlong addresses[4];
    env->GetLongArrayRegion(methods, 0, 4, addresses);
    if (env->ExceptionCheck()) return EINVAL;
    for (jlong address : addresses) if (address <= 0 || address % alignof(void*) != 0) return ENOTSUP;
    JNINativeMethod markers[] = {
        {const_cast<char*>("markerOne"), const_cast<char*>("()I"), reinterpret_cast<void*>(&MarkerOne)},
        {const_cast<char*>("markerTwo"), const_cast<char*>("()I"), reinterpret_cast<void*>(&MarkerTwo)},
    };
    install_stage = "register markers";
    if (env->RegisterNatives(owner, markers, 2) != JNI_OK) return ENOTSUP;
    jmethodID first = env->GetStaticMethodID(owner, "markerOne", "()I");
    jmethodID second = env->GetStaticMethodID(owner, "markerTwo", "()I");
    if (!first || !second || env->ExceptionCheck()) return ENOTSUP;
    install_stage = "invoke markers";
    if (env->CallStaticIntMethod(owner, first) != kMarkerOne
            || env->CallStaticIntMethod(owner, second) != kMarkerTwo || env->ExceptionCheck()) return ENOTSUP;
    std::uintptr_t one[16], two[16];
    install_stage = "read marker methods";
    if (!Read(reinterpret_cast<void*>(addresses[0]), one, sizeof(one))
            || !Read(reinterpret_cast<void*>(addresses[1]), two, sizeof(two))) return EFAULT;
    int matches = 0;
    install_stage = "unique marker offset";
    for (unsigned index = 0; index < 16; ++index) {
        if (one[index] == reinterpret_cast<std::uintptr_t>(&MarkerOne)
                && two[index] == reinterpret_cast<std::uintptr_t>(&MarkerTwo)) {
            ++matches; entry_offset = static_cast<int>(index * sizeof(void*));
        }
    }
    if (matches != 1) return ENOTSUP;
    install_stage = "read Binder entries";
    if (!Read(reinterpret_cast<void*>(addresses[2] + entry_offset), &original_uid, sizeof(original_uid))
            || !Read(reinterpret_cast<void*>(addresses[3] + entry_offset), &original_pid, sizeof(original_pid))) return EFAULT;
    install_stage = "validate libandroid_runtime entries";
    if (!RuntimeEntry(original_uid) || !RuntimeEntry(original_pid)) return ENOTSUP;
    install_stage = "retain Binder class";
    jclass binder = env->FindClass("android/os/Binder");
    if (!binder) return ENOTSUP;
    binder_class = static_cast<jclass>(env->NewGlobalRef(binder));
    env->DeleteLocalRef(binder);
    if (!binder_class) return ENOMEM;
    uid_critical = critical_uid; pid_critical = critical_pid; real_uid = process_uid;
    install_stage = "create scope TLS";
    int error = pthread_key_create(&scope_key, &std::free);
    if (error != 0) return error;
    // Publish immutable originals and TLS before ART can invoke the replacement on any thread.
    ready.store(true, std::memory_order_release);
    JNINativeMethod replacement{const_cast<char*>("getCallingUid"), const_cast<char*>("()I"),
            uid_critical ? reinterpret_cast<void*>(&CriticalUid) : reinterpret_cast<void*>(&JniUid)};
    install_stage = "register Binder replacement";
    if (env->RegisterNatives(binder_class, &replacement, 1) != JNI_OK) return ENOTSUP;
    installed = true;
    install_stage = "installed";
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBinderIdentity_nativeRestore(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(install_mutex);
    if (!installed) return 0;
    JNINativeMethod original{const_cast<char*>("getCallingUid"), const_cast<char*>("()I"), original_uid};
    if (env->RegisterNatives(binder_class, &original, 1) != JNI_OK) return ENOTSUP;
    installed = false;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBinderIdentity_nativeEntryOffset(JNIEnv*, jclass) { return entry_offset; }
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBinderIdentity_nativeInstallStage(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(install_mutex);
    return env->NewStringUTF(install_stage);
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBinderIdentity_nativeHookCalls(JNIEnv*, jclass) {
    return static_cast<jlong>(calls.load(std::memory_order_relaxed));
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBinderIdentity_nativeRawUid(JNIEnv* env, jclass) {
    return ready.load(std::memory_order_acquire) ? OriginalUid(env, binder_class) : -1;
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBinderIdentity_nativeRawPid(JNIEnv* env, jclass) {
    return ready.load(std::memory_order_acquire) ? OriginalPid(env, binder_class) : -1;
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBinderIdentity_nativeEnter(
        JNIEnv* env, jclass, jint uid, jint pid, jint virtual_uid, jlong generation) {
    if (!ready.load(std::memory_order_acquire) || uid != real_uid || pid <= 0 || pid == getpid()
            || virtual_uid < 10000 || generation <= 0
            || uid != OriginalUid(env, binder_class) || pid != OriginalPid(env, binder_class)) {
        Throw(env, "java/lang/SecurityException", "Binder scope does not match a verified remote application caller");
        return 0;
    }
    return Enter(env, uid, pid, virtual_uid, generation);
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBinderIdentity_nativeEnterRaw(JNIEnv* env, jclass) {
    return Enter(env, -1, -1, -1, -1);
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_xmy953538104_mybox_engine_NativeBinderIdentity_nativeLeave(JNIEnv* env, jclass, jlong token) {
    auto* state = static_cast<Scopes*>(pthread_getspecific(scope_key));
    if (!state || state->depth == 0 || state->frames[state->depth - 1].token != static_cast<std::uint64_t>(token)) {
        if (state) state->depth = 0;  // Fail closed: a mismatched scope must never leave an older identity active.
        Throw(env, "java/lang/IllegalStateException", "Binder scopes must close in nesting order");
        return;
    }
    --state->depth;
}
