#pragma once
#include <map>
#include <optional>
#include <set>
#include <string>
#include <utility>

#include "IRailConfigRepository.h"

namespace payhub::storage {

// Loads rail reference data once at startup (tables rail_config, participant_banks)
// and serves it from memory. Call Reload() after reference data changes.
class SqliteRailConfigRepository : public core::IRailConfigRepository {
public:
    explicit SqliteRailConfigRepository(std::string dbPath);

    void Reload();

    std::optional<core::RailLimits> GetLimits(core::Rail rail) const override;
    bool BankSupportsRail(const std::string& ifscPrefix, core::Rail rail) const override;

private:
    std::string dbPath_;
    std::map<core::Rail, core::RailLimits> limits_;
    std::set<std::pair<std::string, core::Rail>> bankRails_;
};

} // namespace payhub::storage
