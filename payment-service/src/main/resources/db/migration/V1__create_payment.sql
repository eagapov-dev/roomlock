-- One payment per booking. The booking id is the idempotency key of both Charge and Refund
-- (rule I-1), so the unique constraint is what turns a repeated call into a replay of the stored
-- answer instead of a second charge, and what makes a redelivered RefundRequested a no-op.
-- Money is kept in minor units (grosze), exactly as the gRPC contract sends it.
CREATE TABLE payment (
  id             uuid        PRIMARY KEY,
  booking_id     uuid        NOT NULL,
  guest_id       uuid        NOT NULL,
  amount_minor   bigint      NOT NULL,
  currency       varchar(3)  NOT NULL,
  status         varchar(16) NOT NULL,
  decline_reason varchar(200),
  charged_at     timestamptz NOT NULL,
  refunded_at    timestamptz,
  CONSTRAINT payment_booking_id_unique UNIQUE (booking_id),
  CONSTRAINT payment_status_valid
    CHECK (status IN ('APPROVED', 'DECLINED', 'REFUNDED')),
  CONSTRAINT payment_amount_minor_positive CHECK (amount_minor > 0),
  CONSTRAINT payment_currency_valid CHECK (currency ~ '^[A-Z]{3}$'),
  -- A declined charge is the only one that carries a reason, and a refunded one the only one that
  -- carries the moment the money went back.
  CONSTRAINT payment_decline_reason_consistent
    CHECK ((status = 'DECLINED') = (decline_reason IS NOT NULL)),
  CONSTRAINT payment_refunded_at_consistent
    CHECK ((status = 'REFUNDED') = (refunded_at IS NOT NULL))
);

-- Payments by outcome, newest last: what the acquirer stub decided over a period of time.
CREATE INDEX payment_status_charged_at_idx ON payment (status, charged_at);
