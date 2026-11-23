#include "SqliteRailConfigRepository.h"

#include <stdexcept>

#include <sqlite3.h>

#include "Logger.h"

namespace payhub::storage {

namespace {
std::optional<core::Rail> ParseRail(const std::string& value) {
    if (value == "IMPS")  return core::Rail::IMPS;
    if (value == "NEFT")  return core::Rail::NEFT;
    if (value == "RTGS")  return core::Rail::RTGS;
    if (value == "SWIFT") return core::Rail::SWIFT;
    return std::nullopt;
}
} // namespace

SqliteRailConfigRepository::SqliteRailConfigRepository(std::string dbPath)
    : dbPath_(std::move(dbPath)) {
    Reload();
}

void SqliteRailConfigRepository::Reload() {
    sqlite3* db = nullptr;
    if (sqlite3_open_v2(dbPath_.c_str(), &db, SQLITE_OPEN_READONLY, nullptr) != SQLITE_OK) {
        sqlite3_close(db);
        throw std::runtime_error("Cannot open reference data: " + dbPath_);
    }

    std::map<core::Rail, core::RailLimits> limits;
    sqlite3_stmt* stmt = nullptr;
    sqlite3_prepare_v2(db, "SELECT rail, min_amount_minor, max_amount_minor, is_active FROM rail_config;",
                       -1, &stmt, nullptr);
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        auto rail = ParseRail(reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0)));
        if (!rail) continue;
        core::RailLimits entry;
        entry.minAmountMinor = sqlite3_column_int64(stmt, 1);
        if (sqlite3_column_type(stmt, 2) != SQLITE_NULL) {
            entry.maxAmountMinor = sqlite3_column_int64(stmt, 2);
        }
        entry.active = sqlite3_column_int(stmt, 3) == 1;
        limits[*rail] = entry;
    }
    sqlite3_finalize(stmt);

    std::set<std::pair<std::string, core::Rail>> bankRails;
    sqlite3_prepare_v2(db, "SELECT ifsc_prefix, supports_imps, supports_neft, supports_rtgs FROM participant_banks;",
                       -1, &stmt, nullptr);
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        const std::string prefix = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        if (sqlite3_column_int(stmt, 1) == 1) bankRails.emplace(prefix, core::Rail::IMPS);
        if (sqlite3_column_int(stmt, 2) == 1) bankRails.emplace(prefix, core::Rail::NEFT);
        if (sqlite3_column_int(stmt, 3) == 1) bankRails.emplace(prefix, core::Rail::RTGS);
    }
    sqlite3_finalize(stmt);
    sqlite3_close(db);

    limits_ = std::move(limits);
    bankRails_ = std::move(bankRails);
    PHUB_LOG_INFO("Rail reference data loaded: " + std::to_string(limits_.size()) + " rails, " +
                  std::to_string(bankRails_.size()) + " bank/rail entries");
}

std::optional<core::RailLimits> SqliteRailConfigRepository::GetLimits(core::Rail rail) const {
    auto it = limits_.find(rail);
    if (it == limits_.end()) {
        return std::nullopt;
    }
    return it->second;
}

bool SqliteRailConfigRepository::BankSupportsRail(const std::string& ifscPrefix, core::Rail rail) const {
    return bankRails_.count({ifscPrefix, rail}) == 1;
}

} // namespace payhub::storage
