-- V002: reference data used by the router
-- Owner: Storage module (SqliteRailConfigRepository), read-only at runtime

-- Limits and availability per clearing rail.
CREATE TABLE IF NOT EXISTS rail_config (
    rail             TEXT    PRIMARY KEY CHECK (rail IN ('IMPS', 'NEFT', 'RTGS', 'SWIFT')),
    min_amount_minor INTEGER NOT NULL DEFAULT 1,
    max_amount_minor INTEGER,                           -- NULL = no upper limit
    is_active        INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    description      TEXT
);

-- Domestic banks reachable through each rail, keyed by the first 4 characters of the IFSC.
CREATE TABLE IF NOT EXISTS participant_banks (
    ifsc_prefix   TEXT    PRIMARY KEY CHECK (length(ifsc_prefix) = 4),
    bank_name     TEXT    NOT NULL,
    supports_imps INTEGER NOT NULL DEFAULT 1,
    supports_neft INTEGER NOT NULL DEFAULT 1,
    supports_rtgs INTEGER NOT NULL DEFAULT 1
);
