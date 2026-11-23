-- Ad-hoc operations queries, run manually by the support team.
-- Not referenced anywhere in the C++ code.

-- 1. Payments stuck in SUBMITTED for more than 30 minutes.
SELECT end_to_end_id, rail, amount_minor, rail_reference, age_minutes
FROM v_pending_settlement
WHERE age_minutes > 30
ORDER BY age_minutes DESC;

-- 2. Today's totals per rail.
SELECT rail, SUM(payment_count) AS payments, SUM(total_amount_minor) / 100.0 AS total_rupees
FROM v_daily_rail_summary
WHERE business_date = date('now')
GROUP BY rail;

-- 3. Rejected payments with high screening scores (possible sanctions hits) in the last 7 days.
SELECT p.end_to_end_id, p.debtor_account, p.creditor_account, p.screening_score, p.created_at
FROM payments p
WHERE p.status = 'REJECTED'
  AND p.screening_score >= 80
  AND p.created_at >= datetime('now', '-7 days')
ORDER BY p.screening_score DESC;

-- 4. Full status history of one payment.
SELECT a.status, a.created_at
FROM payment_audit a
WHERE a.end_to_end_id = :end_to_end_id
ORDER BY a.id;
