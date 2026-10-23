// Copyright 2026 Mybox contributors. SPDX-License-Identifier: Apache-2.0
#ifdef NDEBUG
#undef NDEBUG
#endif
#include "../../main/cpp/PathRules.h"
#include <cassert>
#include <iostream>
#include <thread>

static void expect(PathRules &rules, const char *path, const char *wanted) {
    char output[4096];
    const char *mapped = nullptr;
    assert(rules.redirect(path, output, sizeof(output), mapped));
    assert(std::string(mapped) == wanted);
}

int main() {
    PathRules rules;
    assert(!rules.add("", "/host"));
    assert(!rules.add("/", "/host"));
    assert(!rules.add("relative", "/host"));
    assert(rules.add("/data/data/guest/", "/data/user/0/host/blackbox/data/user/0/guest/"));
    assert(rules.add("/data/data/guest/lib", "/data/user/0/host/blackbox/app/guest/lib"));
    assert(!rules.add("/data/data/guest", "/different-target"));
    expect(rules, "/data/data/guest", "/data/user/0/host/blackbox/data/user/0/guest");
    expect(rules, "/data/data/guest/files/test", "/data/user/0/host/blackbox/data/user/0/guest/files/test");
    expect(rules, "/data/data/guest/lib/native.so", "/data/user/0/host/blackbox/app/guest/lib/native.so");
    expect(rules, "/data/data/guest.other/files/test", "/data/data/guest.other/files/test");
    expect(rules, "/unrelated/data/data/guest/files/test", "/unrelated/data/data/guest/files/test");
    expect(rules, "/data/user/0/host/blackbox/data/user/0/guest/files/test", "/data/user/0/host/blackbox/data/user/0/guest/files/test");
    expect(rules, "relative-file", "relative-file");
    expect(rules, "", "");
    const char *mapped = nullptr;
    char small[8];
    assert(rules.redirect(nullptr, small, sizeof(small), mapped) && !mapped);
    assert(!rules.redirect("/data/data/guest/files/test", small, sizeof(small), mapped));

    assert(rules.add("/sdcard", "/storage/emulated/0/Android/data/host/files/blackbox"));
    assert(rules.add("/storage/emulated/0", "/storage/emulated/0/Android/data/host/files/blackbox"));
    assert(rules.allow("/sdcard/Pictures/"));
    expect(rules, "/sdcard/Pictures/photo.jpg", "/sdcard/Pictures/photo.jpg");
    expect(rules, "/sdcard/PicturesOther/photo.jpg", "/storage/emulated/0/Android/data/host/files/blackbox/PicturesOther/photo.jpg");
    expect(rules, "/storage/emulated/0/Android/data/host/files/blackbox/Download/test", "/storage/emulated/0/Android/data/host/files/blackbox/Download/test");

    std::vector<std::thread> readers;
    for (int i = 0; i < 8; ++i) readers.emplace_back([&] {
        for (int n = 0; n < 10000; ++n)
            expect(rules, "/data/data/guest/lib/native.so", "/data/user/0/host/blackbox/app/guest/lib/native.so");
    });
    for (int n = 0; n < 200; ++n) assert(rules.add("/concurrent/" + std::to_string(n), "/host/" + std::to_string(n)));
    for (auto &reader : readers) reader.join();
    std::cout << "PathRules: boundary, longest-prefix, idempotence, whitelist, limits and concurrent snapshots passed\n";
}
