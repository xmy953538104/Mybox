// Copyright 2026 Mybox contributors. SPDX-License-Identifier: Apache-2.0
#pragma once

#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

class PathRules {
    struct Rule { std::string source, destination; };
    struct Snapshot { std::vector<Rule> rules; std::vector<std::string> whitelist; };
    std::shared_ptr<const Snapshot> snapshot_ = std::make_shared<const Snapshot>();
    std::mutex writer_;

    static bool trim(std::string &path) {
        if (path.empty() || path[0] != '/') return false;
        while (path.size() > 1 && path.back() == '/') path.pop_back();
        return path.size() > 1;
    }

    static bool prefix(const char *path, size_t size, const std::string &directory) {
        const auto n = directory.size();
        return size >= n && std::memcmp(path, directory.data(), n) == 0 &&
               (size == n || path[n] == '/');
    }

public:
    bool add(std::string source, std::string destination) {
        if (!trim(source) || !trim(destination)) return false;
        std::lock_guard<std::mutex> lock(writer_);
        auto next = std::make_shared<Snapshot>(*std::atomic_load(&snapshot_));
        for (const auto &rule : next->rules) {
            if (rule.source == source) return rule.destination == destination;
        }
        next->rules.push_back({std::move(source), std::move(destination)});
        std::sort(next->rules.begin(), next->rules.end(), [](const Rule &a, const Rule &b) {
            return a.source.size() > b.source.size();
        });
        std::atomic_store(&snapshot_, std::shared_ptr<const Snapshot>(std::move(next)));
        return true;
    }

    bool allow(std::string path) {
        if (!trim(path)) return false;
        std::lock_guard<std::mutex> lock(writer_);
        auto next = std::make_shared<Snapshot>(*std::atomic_load(&snapshot_));
        if (std::find(next->whitelist.begin(), next->whitelist.end(), path) == next->whitelist.end())
            next->whitelist.push_back(std::move(path));
        std::atomic_store(&snapshot_, std::shared_ptr<const Snapshot>(std::move(next)));
        return true;
    }

    // No allocation, JNI, or filesystem calls on the libc path. Output belongs to the caller.
    bool redirect(const char *path, char *buffer, size_t capacity, const char *&result) const {
        result = path;
        if (!path || path[0] != '/') return true;
        const size_t length = std::strlen(path);
        const auto snapshot = std::atomic_load(&snapshot_);
        for (const auto &allowed : snapshot->whitelist)
            if (prefix(path, length, allowed)) return true;
        for (const auto &rule : snapshot->rules)
            if (prefix(path, length, rule.destination)) return true;
        for (const auto &rule : snapshot->rules) {
            if (!prefix(path, length, rule.source)) continue;
            const size_t suffix = length - rule.source.size();
            if (rule.destination.size() >= capacity || suffix >= capacity - rule.destination.size())
                return false;
            std::memcpy(buffer, rule.destination.data(), rule.destination.size());
            std::memcpy(buffer + rule.destination.size(), path + rule.source.size(), suffix + 1);
            result = buffer;
            return true;
        }
        return true;
    }
};
