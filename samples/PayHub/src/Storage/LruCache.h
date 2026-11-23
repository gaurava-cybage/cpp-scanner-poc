#pragma once
#include <cstddef>
#include <list>
#include <mutex>
#include <optional>
#include <unordered_map>
#include <utility>

#include "ICache.h"

namespace payhub::storage {

// Thread-safe, header-only LRU cache.
template <typename K, typename V>
class LruCache : public core::ICache<K, V> {
public:
    explicit LruCache(std::size_t capacity) : capacity_(capacity) {}

    std::optional<V> Get(const K& key) override {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = index_.find(key);
        if (it == index_.end()) {
            return std::nullopt;
        }
        entries_.splice(entries_.begin(), entries_, it->second);
        return it->second->second;
    }

    void Put(const K& key, const V& value) override {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = index_.find(key);
        if (it != index_.end()) {
            it->second->second = value;
            entries_.splice(entries_.begin(), entries_, it->second);
            return;
        }
        entries_.emplace_front(key, value);
        index_[key] = entries_.begin();
        if (entries_.size() > capacity_) {
            index_.erase(entries_.back().first);
            entries_.pop_back();
        }
    }

private:
    using Entry = std::pair<K, V>;
    std::size_t capacity_;
    std::list<Entry> entries_;
    std::unordered_map<K, typename std::list<Entry>::iterator> index_;
    std::mutex mutex_;
};

} // namespace payhub::storage
