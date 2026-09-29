package dev.roomlock.booking.outbox;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.booking.money.MoneyProperties;
import dev.roomlock.contract.v1.BookingCancelled;
import dev.roomlock.contract.v1.BookingConfirmed;
import dev.roomlock.contract.v1.BookingHeld;
import dev.roomlock.contract.v1.CancellationReason;
import dev.roomlock.contract.v1.RefundRequested;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a booking of the domain into the event of the contract that describes what happened. The
 * event id is generated here, so a consumer can drop a redelivery of it (rule K-3).
 */
@Component
class BookingEvents {

  private final String currency;
  private final Clock clock;

  BookingEvents(MoneyProperties money, Clock clock) {
    this.currency = money.currency();
    this.clock = clock;
  }

  BookingHeld held(Booking booking) {
    return new BookingHeld(
        UUID.randomUUID(),
        booking.getId(),
        booking.getRoomId(),
        booking.getGuestId(),
        booking.getStay().checkIn(),
        booking.getStay().checkOut(),
        booking.getTotalPrice(),
        currency,
        booking.getHoldExpiresAt(),
        clock.instant());
  }

  BookingConfirmed confirmed(Booking booking) {
    return new BookingConfirmed(
        UUID.randomUUID(),
        booking.getId(),
        booking.getRoomId(),
        booking.getGuestId(),
        booking.getStay().checkIn(),
        booking.getStay().checkOut(),
        booking.getTotalPrice(),
        currency,
        clock.instant());
  }

  BookingCancelled cancelled(Booking booking, CancelReason reason) {
    return new BookingCancelled(
        UUID.randomUUID(),
        booking.getId(),
        booking.getRoomId(),
        booking.getGuestId(),
        reasonOf(reason),
        clock.instant());
  }

  /**
   * The cancelled booking asking for its money back. Written next to the cancellation of a
   * CONFIRMED booking, never on its own: the refund is what that cancellation owes the guest.
   */
  RefundRequested refundRequested(Booking booking) {
    return new RefundRequested(
        UUID.randomUUID(),
        booking.getId(),
        booking.getGuestId(),
        booking.getTotalPrice(),
        currency,
        clock.instant());
  }

  /** The reason of the contract that mirrors the one of the domain; the names are the same (D3). */
  static CancellationReason reasonOf(CancelReason reason) {
    return CancellationReason.valueOf(reason.name());
  }
}
