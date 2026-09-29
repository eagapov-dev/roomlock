package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.DateRange;
import java.util.UUID;

/**
 * Business outcomes of a hold (rule J-2). An unknown room is not one of them: it is a {@link
 * dev.roomlock.booking.domain.ResourceNotFoundException} (rule E-4).
 */
public sealed interface HoldResult {

  /** The room is held for this guest. */
  record Held(Booking booking) implements HoldResult {}

  /** The same guest sent the same request with the same idempotency key again (rule I-1). */
  record AlreadyHeld(Booking booking) implements HoldResult {}

  /** An active booking of the room already covers a night of the stay. */
  record RoomBusy(UUID roomId, DateRange stay) implements HoldResult {}

  /** Too many holds raced for the room: every attempt lost. The client may retry. */
  record RoomContended(UUID roomId) implements HoldResult {}

  /** The idempotency key of this guest belongs to a different request (rule I-2). */
  record IdempotencyKeyReused(String idempotencyKey) implements HoldResult {}
}
