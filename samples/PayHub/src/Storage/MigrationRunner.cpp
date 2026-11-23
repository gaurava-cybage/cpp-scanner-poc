#include "MigrationRunner.h"

#include <algorithm>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <utility>

#include <sqlite3.h>

#include "Logger.h"

namespace payhub::storage {

namespace fs = std::filesystem;

MigrationRunner::MigrationRunner(std::string dbPath, fs::path migrationsDir)
    : dbPath_(std::move(dbPath)), migrationsDir_(std::move(migrationsDir)) {}

int MigrationRunner::Run() {
    sqlite3* db = nullptr;
    if (sqlite3_open(dbPath_.c_str(), &db) != SQLITE_OK) {
        throw std::runtime_error("MigrationRunner cannot open database: " + dbPath_);
    }

    int applied = 0;
    try {
        if (!EnsureVersionTable(db)) {
            throw std::runtime_error("Cannot create schema_version table");
        }
        const int current = CurrentVersion(db);

        for (const auto& script : FindScripts()) {
            const int version = ParseVersion(script);
            if (version <= current) {
                continue;
            }
            if (!Apply(db, version, script)) {
                throw std::runtime_error("Migration failed: " + script.filename().string());
            }
            ++applied;
        }
    } catch (...) {
        sqlite3_close(db);
        throw;
    }

    sqlite3_close(db);
    PHUB_LOG_INFO("Database migrations applied: " + std::to_string(applied));
    return applied;
}

std::vector<fs::path> MigrationRunner::FindScripts() const {
    std::vector<fs::path> scripts;
    for (const auto& entry : fs::directory_iterator(migrationsDir_)) {
        const auto name = entry.path().filename().string();
        if (entry.is_regular_file() && entry.path().extension() == ".sql" && !name.empty() && name[0] == 'V') {
            scripts.push_back(entry.path());
        }
    }
    std::sort(scripts.begin(), scripts.end(),
              [](const fs::path& a, const fs::path& b) { return ParseVersion(a) < ParseVersion(b); });
    return scripts;
}

// "V003__create_reporting_views.sql" -> 3
int MigrationRunner::ParseVersion(const fs::path& script) {
    const auto name = script.filename().string();
    const auto separator = name.find("__");
    if (name.size() < 2 || separator == std::string::npos) {
        throw std::runtime_error("Bad migration file name: " + name);
    }
    return std::stoi(name.substr(1, separator - 1));
}

std::string MigrationRunner::ReadFile(const fs::path& script) {
    std::ifstream in(script, std::ios::binary);
    if (!in) {
        throw std::runtime_error("Cannot read " + script.string());
    }
    std::ostringstream content;
    content << in.rdbuf();
    return content.str();
}

bool MigrationRunner::EnsureVersionTable(sqlite3* db) {
    return sqlite3_exec(db,
        "CREATE TABLE IF NOT EXISTS schema_version ("
        " version    INTEGER PRIMARY KEY,"
        " script     TEXT NOT NULL,"
        " applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);",
        nullptr, nullptr, nullptr) == SQLITE_OK;
}

int MigrationRunner::CurrentVersion(sqlite3* db) {
    sqlite3_stmt* stmt = nullptr;
    sqlite3_prepare_v2(db, "SELECT COALESCE(MAX(version), 0) FROM schema_version;", -1, &stmt, nullptr);
    int version = 0;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        version = sqlite3_column_int(stmt, 0);
    }
    sqlite3_finalize(stmt);
    return version;
}

// Each script runs in its own transaction together with its schema_version row.
bool MigrationRunner::Apply(sqlite3* db, int version, const fs::path& script) {
    const std::string sql = ReadFile(script);
    char* error = nullptr;

    sqlite3_exec(db, "BEGIN TRANSACTION;", nullptr, nullptr, nullptr);
    if (sqlite3_exec(db, sql.c_str(), nullptr, nullptr, &error) != SQLITE_OK) {
        PHUB_LOG_ERROR(script.filename().string() + ": " + (error ? error : "unknown error"));
        sqlite3_free(error);
        sqlite3_exec(db, "ROLLBACK;", nullptr, nullptr, nullptr);
        return false;
    }

    sqlite3_stmt* stmt = nullptr;
    sqlite3_prepare_v2(db, "INSERT INTO schema_version (version, script) VALUES (?, ?);", -1, &stmt, nullptr);
    sqlite3_bind_int(stmt, 1, version);
    const std::string name = script.filename().string();
    sqlite3_bind_text(stmt, 2, name.c_str(), -1, SQLITE_TRANSIENT);
    const bool ok = sqlite3_step(stmt) == SQLITE_DONE;
    sqlite3_finalize(stmt);

    sqlite3_exec(db, ok ? "COMMIT;" : "ROLLBACK;", nullptr, nullptr, nullptr);
    if (ok) {
        PHUB_LOG_INFO("Applied migration " + name);
    }
    return ok;
}

} // namespace payhub::storage
