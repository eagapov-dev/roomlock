package dev.roomlock.booking.payment;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.BookingStatus;
import java.util.UUID;

/**
 * Business outcomes of paying for a booking (rule J-2). An unknown booking is not one of them: it
 * is a {@link dev.roomlock.booking.domain.ResourceNotFoundException} (rule E-4).
 */
public sealed interface PayResult {

  /** The charge was approved and the room belongs to the guest. */
  record Confirmed(Booking booking) implements PayResult {}

  /**
   * The booking was already confirmed, by an earlier request or by the reconciliation sweep. Paying
   * twice is idempotent and must not charge again, so the API answers 200.
   */
  record AlreadyConfirmed(Booking booking) implements PayResult {}

  /** The provider refused the charge and the booking is CANCELLED with PAYMENT_DECLINED. */
  record Declined(UUID bookingId, String reason) implements PayResult {}

  /**
   * The booking is past the point it could be paid for: CANCELLED, and so no longer holds a room.
   */
  record NotPayable(UUID bookingId, BookingStatus status) implements PayResult {}

  /**
   * The provider did not answer. The booking stays PAYING with {@code payment_started_at} set and
   * the sweep finishes it, so the client is told to come back rather than that anything failed.
   */
  record ProviderUnavailable(UUID bookingId) implements PayResult {}
}
