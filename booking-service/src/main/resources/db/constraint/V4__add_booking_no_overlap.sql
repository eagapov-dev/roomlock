-- Database-level overlap guard; skipped by the no-constraint profile (D1).
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE booking
  ADD CONSTRAINT booking_no_overlap
  EXCLUDE USING gist (room_id WITH =, stay WITH &&)
  WHERE (status IN ('HELD', 'PAYING', 'CONFIRMED'));
