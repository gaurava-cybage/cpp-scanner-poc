-- V004: initial reference data
-- Amounts in paise: 1 rupee = 100 paise, 1 lakh = 10,000,000 paise.

INSERT OR IGNORE INTO rail_config (rail, min_amount_minor, max_amount_minor, is_active, description) VALUES
    ('IMPS',  1,          50000000, 1, 'Immediate Payment Service, 24x7, up to 5 lakh'),
    ('NEFT',  1,          NULL,     1, 'National Electronic Funds Transfer, half-hourly batches'),
    ('RTGS',  20000000,   NULL,     1, 'Real Time Gross Settlement, minimum 2 lakh'),
    ('SWIFT', 1,          NULL,     1, 'Cross-border payments via SWIFT');

INSERT OR IGNORE INTO participant_banks (ifsc_prefix, bank_name, supports_imps, supports_neft, supports_rtgs) VALUES
    ('DEMO', 'Demo Bank (own bank)',      1, 1, 1),
    ('ALFA', 'Alfa Cooperative Bank',     1, 1, 0),
    ('BETA', 'Beta Small Finance Bank',   1, 1, 1),
    ('GAMA', 'Gama Regional Rural Bank',  0, 1, 0),
    ('DLTA', 'Delta Payments Bank',       1, 1, 0);
