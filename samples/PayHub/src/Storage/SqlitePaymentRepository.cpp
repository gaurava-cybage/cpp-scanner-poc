#include "SqlitePaymentRepository.h"

#include <stdexcept>

#include <sqlite3.h>

#include "Logger.h"

namespace payhub::storage {

namespace {
core::PaymentStatus ParseStatus(const std::string& value) {
    if (value == "SUBMITTED") return core::PaymentStatus::Submitted;
    if (value == "SETTLED")   return core::PaymentStatus::Settled;
    if (value == "REJECTED")  return core::PaymentStatus::Rejected;
    if (value == "FAILED")    return core::PaymentStatus::Failed;
    return core::PaymentStatus::Received;
}

core::Rail ParseRail(const std::string& value) {
    if (value == "IMPS")  return core::Rail::IMPS;
    if (value == "NEFT")  return core::Rail::NEFT;
    if (value == "RTGS")  return core::Rail::RTGS;
    if (value == "SWIFT") return core::Rail::SWIFT;
    return core::Rail::Unassigned;
}
} // namespace

SqlitePaymentRepository::SqlitePaymentRepository(const std::string& dbPath) {
    if (sqlite3_open(dbPath.c_str(), &db_) != SQLITE_OK) {
        throw std::runtime_error("Cannot open payment database: " + dbPath);
    }
    Execute("PRAGMA journal_mode=WAL;");
    PHUB_LOG_INFO("Payment database opened: " + dbPath);
}

SqlitePaymentRepository::~SqlitePaymentRepository() {
    if (db_ != nullptr) {
        sqlite3_close(db_);
    }
}

bool SqlitePaymentRepository::Execute(const char* sql) {
    char* error = nullptr;
    if (sqlite3_exec(db_, sql, nullptr, nullptr, &error) != SQLITE_OK) {
        PHUB_LOG_ERROR(std::string("SQLite error: ") + (error ? error : "unknown"));
        sqlite3_free(error);
        return false;
    }
    return true;
}

bool SqlitePaymentRepository::AppendAudit(const std::string& endToEndId, core::PaymentStatus status) {
    sqlite3_stmt* stmt = nullptr;
    sqlite3_prepare_v2(db_, "INSERT INTO payment_audit (end_to_end_id, status) VALUES (?, ?);", -1, &stmt, nullptr);
    sqlite3_bind_text(stmt, 1, endToEndId.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, core::ToString(status), -1, SQLITE_STATIC);
    bool ok = sqlite3_step(stmt) == SQLITE_DONE;
    sqlite3_finalize(stmt);
    return ok;
}

bool SqlitePaymentRepository::Save(const core::Payment& payment) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!Execute("BEGIN TRANSACTION;")) {
        return false;
    }

    sqlite3_stmt* stmt = nullptr;
    sqlite3_prepare_v2(db_,
        "INSERT OR REPLACE INTO payments (end_to_end_id, debtor_account, creditor_account, creditor_agent,"
        " amount_minor, currency, priority, rail, status, rail_reference, screening_score)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);",
        -1, &stmt, nullptr);
    sqlite3_bind_text(stmt, 1, payment.EndToEndId().c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, payment.DebtorAccount().c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 3, payment.CreditorAccount().c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 4, payment.CreditorAgent().c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int64(stmt, 5, payment.AmountMinor());
    sqlite3_bind_text(stmt, 6, payment.Currency().c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 7, core::ToString(payment.GetPriority()), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 8, core::ToString(payment.GetRail()), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 9, core::ToString(payment.Status()), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 10, payment.RailReference().c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(stmt, 11, payment.ScreeningScore());
    bool ok = sqlite3_step(stmt) == SQLITE_DONE;
    sqlite3_finalize(stmt);

    ok = ok && AppendAudit(payment.EndToEndId(), payment.Status());
    Execute(ok ? "COMMIT;" : "ROLLBACK;");
    return ok;
}

bool SqlitePaymentRepository::UpdateStatus(const std::string& endToEndId, core::PaymentStatus status,
                                           const std::string& railReference) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!Execute("BEGIN TRANSACTION;")) {
        return false;
    }

    sqlite3_stmt* stmt = nullptr;
    sqlite3_prepare_v2(db_, "UPDATE payments SET status = ?, rail_reference = ?, updated_at = CURRENT_TIMESTAMP"
                       " WHERE end_to_end_id = ?;",
                       -1, &stmt, nullptr);
    sqlite3_bind_text(stmt, 1, core::ToString(status), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, railReference.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 3, endToEndId.c_str(), -1, SQLITE_TRANSIENT);
    bool ok = sqlite3_step(stmt) == SQLITE_DONE && sqlite3_changes(db_) == 1;
    sqlite3_finalize(stmt);

    ok = ok && AppendAudit(endToEndId, status);
    Execute(ok ? "COMMIT;" : "ROLLBACK;");
    return ok;
}

std::optional<core::Payment> SqlitePaymentRepository::FindById(const std::string& endToEndId) {
    std::lock_guard<std::mutex> lock(mutex_);
    sqlite3_stmt* stmt = nullptr;
    sqlite3_prepare_v2(db_,
        "SELECT debtor_account, creditor_account, creditor_agent, amount_minor, currency, priority,"
        " rail, status, rail_reference, screening_score FROM payments WHERE end_to_end_id = ?;",
        -1, &stmt, nullptr);
    sqlite3_bind_text(stmt, 1, endToEndId.c_str(), -1, SQLITE_TRANSIENT);

    if (sqlite3_step(stmt) != SQLITE_ROW) {
        sqlite3_finalize(stmt);
        return std::nullopt;
    }

    auto text = [stmt](int col) {
        const unsigned char* value = sqlite3_column_text(stmt, col);
        return value ? std::string(reinterpret_cast<const char*>(value)) : std::string();
    };

    core::Payment payment(endToEndId, text(0), text(1), text(2), sqlite3_column_int64(stmt, 3), text(4),
                          text(5) == "URGENT" ? core::Priority::Urgent : core::Priority::Normal);
    payment.SetRail(ParseRail(text(6)));
    payment.SetStatus(ParseStatus(text(7)));
    payment.SetRailReference(text(8));
    payment.SetScreeningScore(sqlite3_column_int(stmt, 9));

    sqlite3_finalize(stmt);
    return payment;
}

} // namespace payhub::storage
