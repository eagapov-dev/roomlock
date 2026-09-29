-- Record of the deliveries this service made, one row per booking event it notified about.
-- The unique event_id is also the deduplication key: Kafka redelivers, the constraint does not let
-- the same event be recorded twice, so a guest is told about it once.
CREATE TABLE notification (
  id          uuid          PRIMARY KEY,
  event_id    uuid          NOT NULL,
  event_type  varchar(32)   NOT NULL,
  booking_id  uuid          NOT NULL,
  guest_id    uuid          NOT NULL,
  kind        varchar(16)   NOT NULL,
  channel     varchar(16)   NOT NULL,
  subject     varchar(200)  NOT NULL,
  body        varchar(1000) NOT NULL,
  occurred_at timestamptz   NOT NULL,
  handled_at  timestamptz   NOT NULL,
  sent_at     timestamptz,
  CONSTRAINT notification_event_id_unique UNIQUE (event_id),
  CONSTRAINT notification_kind_valid
    CHECK (kind IN ('HOLD', 'CONFIRMATION', 'CANCELLATION')),
  CONSTRAINT notification_channel_valid
    CHECK (channel IN ('LOG', 'EMAIL', 'SMS')),
  CONSTRAINT notification_event_type_valid
    CHECK (event_type IN ('BookingHeld', 'BookingConfirmed', 'BookingCancelled'))
);

-- Every delivery of one booking, newest last: what a guest was told and when.
CREATE INDEX notification_booking_id_idx ON notification (booking_id, occurred_at);
