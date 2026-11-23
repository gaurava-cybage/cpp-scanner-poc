#pragma once
#include <cstdint>
#include <optional>
#include <string>

#include "Payment.h"

namespace payhub::core {

struct RailLimits {
    std::int64_t minAmountMinor = 1;
    std::optional<std::int64_t> maxAmountMinor;   // empty = no upper limit
    bool active = false;
};

// Read-only rail reference data. Implemented by storage::SqliteRailConfigRepository
// (tables rail_config, participant_banks).
class IRailConfigRepository {
public:
    virtual ~IRailConfigRepository() = default;
    virtual std::optional<RailLimits> GetLimits(Rail rail) const = 0;
    virtual bool BankSupportsRail(const std::string& ifscPrefix, Rail rail) const = 0;
};

} // namespace payhub::core
