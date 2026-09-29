package dev.roomlock.booking.lifecycle;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.BookingStatus;
import java.util.UUID;

/**
 * Business outcomes of a guest cancelling a booking (rule J-2). An unknown booking is not one of
 * them: it is a {@link dev.roomlock.booking.domain.ResourceNotFoundException} (rule E-4).
 */
public sealed interface CancelResult {

  /** The booking is CANCELLED with reason USER_CANCELLED. */
  record Cancelled(Booking booking) implements CancelResult {}

  /** The booking was already cancelled: cancelling twice is idempotent, the API answers 200. */
  record AlreadyCancelled(Booking booking) implements CancelResult {}

  /** The booking is past the point a guest may drop it: CONFIRMED, or in PAYING. */
  record NotCancellable(UUID bookingId, BookingStatus status) implements CancelResult {}
}
