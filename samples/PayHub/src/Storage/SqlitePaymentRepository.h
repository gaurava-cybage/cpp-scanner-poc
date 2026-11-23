#pragma once
#include <mutex>
#include <optional>
#include <string>

#include "IPaymentRepository.h"

struct sqlite3;

namespace payhub::storage {

// SQLite-backed payment store.
// Tables: payments (current state), payment_audit (append-only status history).
// Schema is created by MigrationRunner from db/migrations/V001__create_payment_tables.sql.
class SqlitePaymentRepository : public core::IPaymentRepository {
public:
    explicit SqlitePaymentRepository(const std::string& dbPath);
    ~SqlitePaymentRepository() override;

    SqlitePaymentRepository(const SqlitePaymentRepository&) = delete;
    SqlitePaymentRepository& operator=(const SqlitePaymentRepository&) = delete;

    bool Save(const core::Payment& payment) override;
    bool UpdateStatus(const std::string& endToEndId, core::PaymentStatus status,
                      const std::string& railReference) override;
    std::optional<core::Payment> FindById(const std::string& endToEndId) override;

private:
    bool Execute(const char* sql);
    bool AppendAudit(const std::string& endToEndId, core::PaymentStatus status);

    sqlite3* db_ = nullptr;
    std::mutex mutex_;
};

} // namespace payhub::storage
