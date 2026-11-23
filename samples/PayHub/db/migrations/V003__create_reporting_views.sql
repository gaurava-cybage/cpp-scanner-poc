-- V003: reporting views for operations dashboards
-- Not used by the C++ code; consumed by the Java "ops-reporting-service".

-- Payments sent to a rail but not yet confirmed as settled.
CREATE VIEW IF NOT EXISTS v_pending_settlement AS
SELECT end_to_end_id,
       rail,
       amount_minor,
       currency,
       rail_reference,
       created_at,
       CAST((julianday('now') - julianday(created_at)) * 24 * 60 AS INTEGER) AS age_minutes
FROM payments
WHERE status = 'SUBMITTED';

-- Daily volume and value per rail and status.
CREATE VIEW IF NOT EXISTS v_daily_rail_summary AS
SELECT date(created_at)  AS business_date,
       rail,
       status,
       COUNT(*)          AS payment_count,
       SUM(amount_minor) AS total_amount_minor
FROM payments
GROUP BY date(created_at), rail, status;
