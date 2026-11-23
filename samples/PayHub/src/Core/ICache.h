#pragma once
#include <optional>

namespace payhub::core {

// Generic cache abstraction. Implemented by storage::LruCache<K, V>.
// Used for idempotency (duplicate payment IDs) and fast status lookups.
template <typename K, typename V>
class ICache {
public:
    virtual ~ICache() = default;
    virtual std::optional<V> Get(const K& key) = 0;
    virtual void Put(const K& key, const V& value) = 0;
};

} // namespace payhub::core
