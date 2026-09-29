CREATE TABLE booking (
  id                 uuid          PRIMARY KEY,
  room_id            uuid          NOT NULL REFERENCES room (id),
  guest_id           uuid          NOT NULL,
  check_in           date          NOT NULL,
  check_out          date          NOT NULL,
  status             varchar(16)   NOT NULL,
  cancel_reason      varchar(32),
  hold_expires_at    timestamptz   NOT NULL,
  payment_started_at timestamptz,
  total_price        numeric(12,2) NOT NULL,
  idempotency_key    varchar(64)   NOT NULL,
  request_hash       char(64)      NOT NULL,
  created_at         timestamptz   NOT NULL,
  updated_at         timestamptz   NOT NULL,
  CONSTRAINT booking_status_valid
    CHECK (status IN ('HELD', 'PAYING', 'CONFIRMED', 'CANCELLED')),
  CONSTRAINT booking_cancel_reason_valid
    CHECK (cancel_reason IN ('PAYMENT_DECLINED', 'HOLD_EXPIRED', 'USER_CANCELLED')),
  CONSTRAINT booking_cancel_reason_consistent
    CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL)),
  CONSTRAINT booking_guest_id_idempotency_key_key UNIQUE (guest_id, idempotency_key)
);

-- Half-open stay range for the overlap constraint (D5). The CASE keeps a reversed pair of dates
-- from failing inside daterange() with 22000 before booking_dates_valid reports it as 23514.
ALTER TABLE booking
  ADD COLUMN stay daterange
  GENERATED ALWAYS AS (
    CASE WHEN check_out > check_in THEN daterange(check_in, check_out, '[)') END
  ) STORED;

ALTER TABLE booking ADD CONSTRAINT booking_dates_valid CHECK (check_out > check_in);

CREATE INDEX booking_room_id_idx ON booking (room_id);
CREATE INDEX booking_status_hold_expires_at_idx ON booking (status, hold_expires_at);
CREATE INDEX booking_status_payment_started_at_idx ON booking (status, payment_started_at);
