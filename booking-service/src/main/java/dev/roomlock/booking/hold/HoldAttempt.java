package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.DateRange;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything a strategy needs to write one HELD row: the command plus the values the use case has
 * computed. A strategy that retries builds a fresh entity per attempt, so a rolled back attempt
 * never leaves a half-persisted one behind.
 */
public record HoldAttempt(
    UUID roomId,
    UUID guestId,
    DateRange stay,
    BigDecimal totalPrice,
    String idempotencyKey,
    String requestHash,
    Instant holdExpiresAt,
    Instant now) {

  /** A new HELD entity with its own id. */
  public Booking newBooking() {
    return Booking.hold(
        roomId, guestId, stay, totalPrice, idempotencyKey, requestHash, holdExpiresAt, now);
  }
}
