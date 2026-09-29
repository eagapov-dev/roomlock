package dev.roomlock.booking.lifecycle;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.BookingStatus;
import java.util.UUID;

/**
 * Business outcomes of a guest cancelling a booking (rule J-2). An unknown booking is not one of
 * them: it is a {@link dev.roomlock.booking.domain.ResourceNotFoundException} (rule E-4).
 */
public sealed interface CancelResult {

  /**
   * The booking is CANCELLED with reason USER_CANCELLED. A booking that had been paid for also has
   * a {@code RefundRequested} in the outbox, written in the same transaction (rule K-1).
   */
  record Cancelled(Booking booking) implements CancelResult {}

  /** The booking was already cancelled: cancelling twice is idempotent, the API answers 200. */
  record AlreadyCancelled(Booking booking) implements CancelResult {}

  /**
   * The booking cannot be dropped right now: it is PAYING, so a charge is in flight and only the
   * payment flow may decide how it ends. A CONFIRMED booking is cancellable - it is refunded.
   */
  record NotCancellable(UUID bookingId, BookingStatus status) implements CancelResult {}
}
