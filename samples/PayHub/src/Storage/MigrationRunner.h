#pragma once
#include <filesystem>
#include <string>
#include <vector>

struct sqlite3;

namespace payhub::storage {

// Applies versioned SQL scripts (db/migrations/V###__name.sql) in order at startup.
// Applied versions are tracked in the schema_version table, so each script runs once.
class MigrationRunner {
public:
    MigrationRunner(std::string dbPath, std::filesystem::path migrationsDir);

    // Returns the number of scripts applied in this run.
    int Run();

private:
    std::vector<std::filesystem::path> FindScripts() const;
    static int ParseVersion(const std::filesystem::path& script);
    static std::string ReadFile(const std::filesystem::path& script);

    bool EnsureVersionTable(sqlite3* db);
    int CurrentVersion(sqlite3* db);
    bool Apply(sqlite3* db, int version, const std::filesystem::path& script);

    std::string dbPath_;
    std::filesystem::path migrationsDir_;
};

} // namespace payhub::storage
