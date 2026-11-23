#include "PaymentStore.h"
#include "IReceiptPrinter.h"
#include "sqlite3.h"
#include <cstdio>
#include <cstring>

namespace tinypay {

PaymentStore::PaymentStore() : dbConnection(nullptr) {
    sqlite3* db = nullptr;
    int rc = sqlite3_open("payments.db", &db);
    if (rc == SQLITE_OK) {
        sqlite3_exec(db,
            "CREATE TABLE IF NOT EXISTS merchant (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            nullptr, nullptr, nullptr);
        dbConnection = db;
    }
}

PaymentStore::~PaymentStore() {
    if (dbConnection) {
        sqlite3_close((sqlite3*)dbConnection);
    }
}

long long PaymentStore::save(const char* payer, double amount) {
    sqlite3* db = (sqlite3*)dbConnection;
    if (!db) return -1;

    // INSERT held in const char* - T7 case
    const char* insertSql = "INSERT INTO payment (payer, amount, status) VALUES (?, ?, 'PENDING')";
    sqlite3_stmt* stmt = nullptr;

    int rc = sqlite3_prepare_v2(db, insertSql, -1, &stmt, nullptr);
    if (rc == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, payer, -1, SQLITE_STATIC);
        sqlite3_bind_double(stmt, 2, amount);
        sqlite3_step(stmt);
        sqlite3_finalize(stmt);
    }

    return 1;
}

int PaymentStore::getStatus(long long paymentId) {
    sqlite3* db = (sqlite3*)dbConnection;
    if (!db) return 0;

    // SELECT built at runtime - T7 case (dynamic)
    char query[256];
    snprintf(query, sizeof(query), "SELECT status FROM payment WHERE id = %lld", paymentId);

    sqlite3_stmt* stmt = nullptr;
    int rc = sqlite3_prepare_v2(db, query, -1, &stmt, nullptr);
    if (rc == SQLITE_OK) {
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            int status = sqlite3_column_int(stmt, 0);
            sqlite3_finalize(stmt);
            return status;
        }
        sqlite3_finalize(stmt);
    }

    return 0;
}

void PaymentStore::print(int paymentId) {
    printf("Payment %d\n", paymentId);
}

}
