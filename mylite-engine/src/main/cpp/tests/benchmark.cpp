// SPDX-License-Identifier: Apache-2.0
// Linux-only synthetic warm-cache benchmark; no original VLite binary or Android runtime.
#include "../native_io.h"
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <initializer_list>
#include <pthread.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef MYLITE_BENCH_BUILD_TYPE
#define MYLITE_BENCH_BUILD_TYPE "unknown"
#endif

namespace {
constexpr int kSamples = 3;
constexpr int kMaxWorkers = 4;
constexpr std::uint64_t kWarmupNs = 100000000;
constexpr std::uint64_t kSampleNs = 200000000;
constexpr char kLogicalRoot[] = "/mylite-benchmark/native-guest";
constexpr char kPayload[] = "mylite-benchmark-payload\n";
constexpr std::size_t kPayloadSize = sizeof(kPayload) - 1;
const char* const kModes[]{"no_filter_real", "filter_unmatched", "filter_redirected"};

struct Sample {
    std::uint64_t iterations, elapsed_ns, cpu_ns, minor_faults;
};
struct Result {
    int mode, workers, file_path_bytes, link_target_bytes, completed;
    Sample samples[kSamples];
};
struct Outcome {
    Result result{};
    int exit_code = -1, signal = 0;
    bool success = false, cleaned = false;
};
struct Files {
    char directory[256], data[256], unused[256], file[256], link[256];
    char accessed_file[256], accessed_link[256], expected_target[256];
    dev_t device;
    ino_t inode;
};
struct Run {
    const Files* files;
    pthread_barrier_t start, finish;
    std::uint64_t deadline;
};
struct Worker { Run* run; std::uint64_t iterations = 0; };

[[noreturn]] void Fail(const char* operation, int error = errno) {
    std::fprintf(stderr, "Mylite benchmark failed: %s (error=%d)\n", operation, error);
    _exit(1);
}
void Require(bool condition, const char* operation) { if (!condition) Fail(operation); }

std::uint64_t Now() {
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC_RAW, &now) != 0) Fail("clock_gettime");
    return static_cast<std::uint64_t>(now.tv_sec) * 1000000000 + now.tv_nsec;
}
std::uint64_t CpuNs(const rusage& use) {
    return (static_cast<std::uint64_t>(use.ru_utime.tv_sec) + use.ru_stime.tv_sec) * 1000000000
            + (static_cast<std::uint64_t>(use.ru_utime.tv_usec) + use.ru_stime.tv_usec) * 1000;
}
void Path(char* output, std::size_t capacity, const char* root, const char* leaf) {
    int size = std::snprintf(output, capacity, "%s/%s", root, leaf);
    if (size < 0 || static_cast<std::size_t>(size) >= capacity) Fail("fixture path overflow", ENAMETOOLONG);
}
void WriteResult(int fd, const void* buffer, std::size_t size) {
    const auto* bytes = static_cast<const char*>(buffer);
    while (size != 0) {
        ssize_t n = write(fd, bytes, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) Fail("result write", n == 0 ? EIO : errno);
        bytes += n;
        size -= static_cast<std::size_t>(n);
    }
}
Files Names(const char* root, int index) {
    Files files{};
    char leaf[32];
    std::snprintf(leaf, sizeof(leaf), "case-%d", index);
    Path(files.directory, sizeof(files.directory), root, leaf);
    Path(files.data, sizeof(files.data), files.directory, "data");
    Path(files.unused, sizeof(files.unused), files.directory, "unused");
    Path(files.file, sizeof(files.file), files.data, "payload.bin");
    Path(files.link, sizeof(files.link), files.data, "payload-link");
    return files;
}
bool Cleanup(const Files& files) {
    bool success = true;
    for (const char* path : {files.link, files.file}) {
        if (unlink(path) != 0 && errno != ENOENT) success = false;
    }
    for (const char* path : {files.data, files.directory}) {
        if (rmdir(path) != 0 && errno != ENOENT) success = false;
    }
    return success;
}
void Prepare(Files& files, int mode) {
    Require(mkdir(files.directory, 0700) == 0, "mkdir fixture");
    Require(mkdir(files.data, 0700) == 0, "mkdir data");
    int fd = open(files.file, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
    Require(fd >= 0, "create payload");
    Require(write(fd, kPayload, kPayloadSize) == static_cast<ssize_t>(kPayloadSize), "write payload");
    Require(fsync(fd) == 0, "sync payload");
    struct stat info{};
    Require(fstat(fd, &info) == 0, "identify payload");
    files.device = info.st_dev; files.inode = info.st_ino;
    Require(close(fd) == 0, "close prepared payload");
    Require(symlink(files.file, files.link) == 0, "create absolute symlink");
    const char* access_root = mode == 2 ? kLogicalRoot : files.data;
    Path(files.accessed_file, sizeof(files.accessed_file), access_root, "payload.bin");
    Path(files.accessed_link, sizeof(files.accessed_link), access_root, "payload-link");
    Path(files.expected_target, sizeof(files.expected_target), access_root, "payload.bin");
    // The unmatched case's work path is outside BOTH its rule's source and destination.
    int error = mybox::ConfiguredPaths().Add(kLogicalRoot, mode == 2 ? files.data : files.unused);
    if (error != 0) Fail("configure rule", error);
    if (mode != 0) {
        error = mybox::InstallIo();
        if (error != 0 || !mybox::IoEnabled()) Fail("install filter", error != 0 ? error : EIO);
    }
}

void Iteration(const Files& files) {
    int fd = open(files.accessed_file, O_RDONLY | O_CLOEXEC);
    Require(fd >= 0, "timed open");
    char payload[kPayloadSize];
    Require(read(fd, payload, sizeof(payload)) == static_cast<ssize_t>(sizeof(payload)), "timed read");
    Require(close(fd) == 0, "timed close");
    if (std::memcmp(payload, kPayload, sizeof(payload)) != 0) Fail("payload mismatch", EIO);
    struct stat info{};
    Require(stat(files.accessed_file, &info) == 0, "timed stat");
    if (info.st_dev != files.device || info.st_ino != files.inode || info.st_size != static_cast<off_t>(kPayloadSize))
        Fail("stat identity mismatch", EIO);
    char link[256];
    ssize_t count = readlink(files.accessed_link, link, sizeof(link));
    Require(count >= 0, "timed readlink");
    auto expected = std::strlen(files.expected_target);
    if (static_cast<std::size_t>(count) != expected || std::memcmp(link, files.expected_target, expected) != 0)
        Fail("readlink target mismatch", EIO);
}
void Barrier(pthread_barrier_t* barrier) {
    int error = pthread_barrier_wait(barrier);
    if (error != 0 && error != PTHREAD_BARRIER_SERIAL_THREAD) Fail("barrier wait", error);
}
void* Work(void* argument) {
    auto& worker = *static_cast<Worker*>(argument);
    Barrier(&worker.run->start);
    do {
        Iteration(*worker.run->files);
        ++worker.iterations;
    } while (Now() < worker.run->deadline);
    Barrier(&worker.run->finish);
    return nullptr;
}
Sample Measure(const Files& files, int workers, std::uint64_t duration) {
    Run run{}; run.files = &files;
    int error = pthread_barrier_init(&run.start, nullptr, workers + 1);
    if (error != 0) Fail("start barrier", error);
    error = pthread_barrier_init(&run.finish, nullptr, workers + 1);
    if (error != 0) Fail("finish barrier", error);
    Worker worker[kMaxWorkers]{};
    pthread_t threads[kMaxWorkers]{};
    for (int i = 0; i < workers; ++i) {
        worker[i].run = &run;
        error = pthread_create(&threads[i], nullptr, Work, &worker[i]);
        if (error != 0) Fail("create worker", error);
    }
    rusage before{}, after{};
    Require(getrusage(RUSAGE_SELF, &before) == 0, "initial rusage");
    const auto start = Now();
    run.deadline = start + duration;
    Barrier(&run.start);
    Barrier(&run.finish);
    const auto elapsed = Now() - start;
    Require(getrusage(RUSAGE_SELF, &after) == 0, "final rusage");
    std::uint64_t iterations = 0;
    for (int i = 0; i < workers; ++i) {
        error = pthread_join(threads[i], nullptr);
        if (error != 0) Fail("join worker", error);
        if (worker[i].iterations == 0) Fail("worker made no progress", EIO);
        iterations += worker[i].iterations;
    }
    error = pthread_barrier_destroy(&run.start);
    if (error != 0) Fail("destroy start barrier", error);
    error = pthread_barrier_destroy(&run.finish);
    if (error != 0) Fail("destroy finish barrier", error);
    return {iterations, elapsed, CpuNs(after) - CpuNs(before),
            static_cast<std::uint64_t>(after.ru_minflt - before.ru_minflt)};
}
void Child(Files files, int mode, int workers, int channel) {
    alarm(4); // Bound a deadlock without converting it into a misleading performance sample.
    Prepare(files, mode);
    Measure(files, workers, kWarmupNs);
    Result result{mode, workers, static_cast<int>(std::strlen(files.accessed_file)),
            static_cast<int>(std::strlen(files.expected_target)), 0, {}};
    for (int i = 0; i < kSamples; ++i) {
        result.samples[i] = Measure(files, workers, kSampleNs);
        result.completed = i + 1;
        // Snapshots retain already-completed samples even if a later sample times out.
        WriteResult(channel, &result, sizeof(result));
    }
    // The unfiltered parent owns cleanup, including a child's failure/timeout artifacts.
}
void JsonString(const char* value) {
    std::putchar('"');
    for (const auto* c = reinterpret_cast<const unsigned char*>(value); *c != 0; ++c) {
        if (*c == '"' || *c == '\\') std::printf("\\%c", *c);
        else if (*c < 32) std::printf("\\u%04x", *c);
        else std::putchar(*c);
    }
    std::putchar('"');
}
}

int main() {
    const auto benchmark_start = Now();
    const int inherited_seccomp = prctl(PR_GET_SECCOMP, 0L, 0L, 0L, 0L);
    const int inherited_seccomp_error = inherited_seccomp < 0 ? errno : 0;
    utsname environment{};
    if (uname(&environment) != 0) { std::perror("benchmark uname"); return 1; }
    const long page = sysconf(_SC_PAGESIZE), cpus = sysconf(_SC_NPROCESSORS_ONLN);
    if (page <= 0 || cpus <= 0) { std::fputs("Cannot determine benchmark environment\n", stderr); return 1; }
    char root[] = "/tmp/mylite-io-benchmark-XXXXXX";
    if (mkdtemp(root) == nullptr) { std::perror("benchmark mkdtemp"); return 1; }
    Outcome outcomes[6]{};
    int case_count = 0;
    bool failed = false;
    for (int index = 0; index < 6; ++index) {
        int mode = index % 3, workers = index < 3 ? 1 : kMaxWorkers;
        Files files = Names(root, index);
        int channel[2];
        if (pipe(channel) != 0) { std::perror("benchmark pipe"); failed = true; break; }
        pid_t child = fork();
        if (child == 0) {
            close(channel[0]);
            Child(files, mode, workers, channel[1]);
            _exit(0);
        }
        close(channel[1]);
        if (child < 0) { std::perror("benchmark fork"); close(channel[0]); failed = true; break; }
        // All snapshots fit in the minimum pipe capacity, so waiting before reading cannot block its writer.
        static_assert(sizeof(Result) * kSamples <= 512, "Snapshots must fit in the pipe");
        int status = 0;
        pid_t waited;
        do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
        auto& outcome = outcomes[case_count++];
        outcome.result.mode = mode; outcome.result.workers = workers;
        outcome.cleaned = Cleanup(files);
        outcome.exit_code = waited == child && WIFEXITED(status) ? WEXITSTATUS(status) : -1;
        outcome.signal = waited == child && WIFSIGNALED(status) ? WTERMSIG(status) : 0;
        Result snapshots[kSamples]{};
        std::size_t bytes = 0;
        bool valid = true;
        while (bytes < sizeof(snapshots)) {
            ssize_t n = read(channel[0], reinterpret_cast<char*>(snapshots) + bytes, sizeof(snapshots) - bytes);
            if (n < 0 && errno == EINTR) continue;
            if (n < 0) { valid = false; break; }
            if (n == 0) break;
            bytes += static_cast<std::size_t>(n);
        }
        close(channel[0]);
        if (bytes % sizeof(Result) != 0) valid = false;
        for (std::size_t i = 0; i < bytes / sizeof(Result); ++i) {
            const auto& snapshot = snapshots[i];
            if (snapshot.mode != mode || snapshot.workers != workers
                    || snapshot.completed != static_cast<int>(i) + 1) { valid = false; break; }
            outcome.result = snapshot;
        }
        outcome.success = valid && outcome.exit_code == 0 && outcome.cleaned && outcome.result.completed == kSamples;
        if (!outcome.success) {
            std::fprintf(stderr, "Benchmark case failed: mode=%s workers=%d wait_status=%d cleanup=%d\n",
                    kModes[mode], workers, status, outcome.cleaned);
            failed = true; break;
        }
    }
    const bool root_cleaned = rmdir(root) == 0;
    if (!root_cleaned) { std::perror("benchmark cleanup"); failed = true; }
    std::printf("{\"schema_version\":1,\"benchmark\":\"mylite_native_io\",\"success\":%s,\"root_cleanup_ok\":%s,\"system\":",
            failed ? "false" : "true", root_cleaned ? "true" : "false");
    JsonString(environment.sysname);
    std::printf(",\"kernel\":"); JsonString(environment.release);
    std::printf(",\"machine\":"); JsonString(environment.machine);
    std::printf(",\"compiler\":"); JsonString(__VERSION__);
    std::printf(",\"build_type\":"); JsonString(MYLITE_BENCH_BUILD_TYPE);
    std::printf(",\"page_size\":%ld,\"online_cpus\":%ld,\"inherited_seccomp_mode\":%d,\"inherited_seccomp_errno\":%d,"
            "\"baseline_means\":\"no Mylite filter; inherited host restrictions remain\",\"clock\":\"CLOCK_MONOTONIC_RAW\","
            "\"benchmark_elapsed_ns\":%llu,\"shared_readonly_fixture\":true,"
            "\"warm_cache\":true,\"warmup_ns\":%llu,\"sample_target_ns\":%llu,"
            "\"api_operations_per_iteration\":5,\"workload\":[\"open\",\"read\",\"close\",\"stat\",\"readlink\"],"
            "\"limitations\":\"Linux synthetic warm-cache aggregate; includes checks and barrier overhead; "
            "not individual-call latency, Android/device performance, disk throughput or original VLite comparison\",\"cases\":[",
            page, cpus, inherited_seccomp, inherited_seccomp_error, static_cast<unsigned long long>(Now() - benchmark_start),
            static_cast<unsigned long long>(kWarmupNs), static_cast<unsigned long long>(kSampleNs));
    for (int index = 0; index < case_count; ++index) {
        const auto& outcome = outcomes[index];
        const Result& result = outcome.result;
        std::printf("%s{\"mode\":\"%s\",\"workers\":%d,\"success\":%s,\"child_exit_code\":%d,\"child_signal\":%d,"
                "\"cleanup_ok\":%s,\"file_path_bytes\":%d,\"link_target_bytes\":%d,\"samples\":[",
                index == 0 ? "" : ",", kModes[result.mode], result.workers, outcome.success ? "true" : "false",
                outcome.exit_code, outcome.signal, outcome.cleaned ? "true" : "false", result.file_path_bytes, result.link_target_bytes);
        for (int i = 0; i < result.completed; ++i) {
            const Sample& sample = result.samples[i];
            std::printf("%s{\"iterations\":%llu,\"operations\":%llu,\"bytes_read\":%llu,\"elapsed_ns\":%llu,"
                    "\"cpu_ns\":%llu,\"minor_faults\":%llu}", i == 0 ? "" : ",",
                    static_cast<unsigned long long>(sample.iterations), static_cast<unsigned long long>(sample.iterations * 5),
                    static_cast<unsigned long long>(sample.iterations * kPayloadSize),
                    static_cast<unsigned long long>(sample.elapsed_ns), static_cast<unsigned long long>(sample.cpu_ns),
                    static_cast<unsigned long long>(sample.minor_faults));
        }
        std::printf("]}");
    }
    std::puts("]}");
    const bool flushed = std::fflush(stdout) == 0;
    return failed || !flushed || std::ferror(stdout) ? 1 : 0;
}
