package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.Booking;

/** What one strategy did with a {@link HoldAttempt}. */
public sealed interface HoldOutcome {

  /** The HELD row is committed. */
  record Inserted(Booking booking) implements HoldOutcome {}

  /**
   * An active booking of the room covers a night of the stay. Whose booking that is stays open: the
   * loser of two simultaneous holds of one idempotency key sees the same thing.
   */
  record Busy() implements HoldOutcome {}

  /** Every attempt lost the race for the room against a concurrent hold. */
  record Contended() implements HoldOutcome {}

  /** A concurrent request inserted a booking with the same guest and idempotency key. */
  record KeyTaken() implements HoldOutcome {}
}
