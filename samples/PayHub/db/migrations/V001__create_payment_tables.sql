-- V001: core payment tables
-- Owner: Storage module (SqlitePaymentRepository)

CREATE TABLE IF NOT EXISTS payments (
    end_to_end_id    TEXT    PRIMARY KEY,               -- ISO 20022 EndToEndId (Max35Text)
    debtor_account   TEXT    NOT NULL,
    creditor_account TEXT    NOT NULL,
    creditor_agent   TEXT    NOT NULL,                  -- IFSC (11) or BIC (8/11)
    amount_minor     INTEGER NOT NULL CHECK (amount_minor > 0),
    currency         TEXT    NOT NULL CHECK (length(currency) = 3),
    priority         TEXT    NOT NULL CHECK (priority IN ('NORMAL', 'URGENT')),
    rail             TEXT    NOT NULL,
    status           TEXT    NOT NULL,
    rail_reference   TEXT,                              -- UTR / UETR
    screening_score  INTEGER NOT NULL DEFAULT 0,
    created_at       TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_payments_status       ON payments (status);
CREATE INDEX IF NOT EXISTS ix_payments_rail_created ON payments (rail, created_at);
CREATE INDEX IF NOT EXISTS ix_payments_debtor       ON payments (debtor_account);

-- Append-only status history, one row per status change.
CREATE TABLE IF NOT EXISTS payment_audit (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    end_to_end_id TEXT    NOT NULL REFERENCES payments (end_to_end_id),
    status        TEXT    NOT NULL,
    created_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_payment_audit_e2e ON payment_audit (end_to_end_id);
