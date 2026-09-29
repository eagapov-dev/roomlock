CREATE TABLE outbox_event (
  id           uuid        PRIMARY KEY,
  aggregate_id uuid        NOT NULL,
  type         varchar(32) NOT NULL,
  payload      jsonb       NOT NULL,
  created_at   timestamptz NOT NULL,
  published_at timestamptz,
  attempts     integer     NOT NULL DEFAULT 0,
  CONSTRAINT outbox_event_type_valid
    CHECK (type IN ('BookingHeld', 'BookingConfirmed', 'BookingCancelled', 'RefundRequested'))
);

CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;
