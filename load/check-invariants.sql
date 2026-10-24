\set ON_ERROR_STOP on
BEGIN READ ONLY;
WITH inventory AS (
    SELECT count(*) AS total,
        count(*) FILTER (WHERE status = 'AVAILABLE') AS available,
        count(*) FILTER (WHERE status = 'RESERVED') AS reserved,
        count(*) FILTER (WHERE status = 'SOLD') AS sold
    FROM seats WHERE concert_id = :'concert_id'::uuid
), duplicates AS (
    SELECT r.seat_id FROM reservations r JOIN seats s ON s.id = r.seat_id
    WHERE s.concert_id = :'concert_id'::uuid AND r.status IN ('ACTIVE', 'CONFIRMED')
    GROUP BY r.seat_id HAVING count(*) > 1
), mismatches AS (
    SELECT s.id FROM seats s WHERE s.concert_id = :'concert_id'::uuid AND (
        (s.status = 'SOLD' AND NOT EXISTS (
            SELECT 1 FROM reservations r JOIN payments p ON p.reservation_id = r.id
            WHERE r.seat_id = s.id AND r.status = 'CONFIRMED' AND p.status = 'SUCCESS'))
        OR (s.status = 'RESERVED' AND NOT EXISTS (
            SELECT 1 FROM reservations r WHERE r.seat_id = s.id AND r.status = 'ACTIVE'))
        OR (s.status = 'AVAILABLE' AND EXISTS (
            SELECT 1 FROM reservations r WHERE r.seat_id = s.id AND r.status IN ('ACTIVE', 'CONFIRMED')))
    )
)
SELECT total, available, reserved, sold,
    1 / CASE WHEN total > 0 AND available + reserved + sold = total
        AND sold <= total AND reserved + sold <= total
        AND NOT EXISTS (SELECT 1 FROM duplicates)
        AND NOT EXISTS (SELECT 1 FROM mismatches) THEN 1 ELSE 0 END AS invariants_passed
FROM inventory;
ROLLBACK;
