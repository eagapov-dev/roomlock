-- One publisher at a time. The outbox has to publish the events of a booking in the order they were
-- written, which two instances sweeping the same rows cannot promise, so an instance takes this lease
-- before it sweeps and renews it while it works. A lease that expires is taken over, so a dead
-- instance costs one lease period rather than a stuck outbox.
CREATE TABLE outbox_lease (
  id          text        PRIMARY KEY,
  holder      uuid        NOT NULL,
  acquired_at timestamptz NOT NULL,
  expires_at  timestamptz NOT NULL
);
