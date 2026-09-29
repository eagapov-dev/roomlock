package dev.roomlock.booking.outbox;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.contract.v1.BookingCancelled;
import dev.roomlock.contract.v1.BookingHeld;
import dev.roomlock.contract.v1.CancellationReason;
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

  BookingEvents(OutboxProperties properties, Clock clock) {
    this.currency = properties.currency();
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

  BookingCancelled cancelled(Booking booking, CancelReason reason) {
    return new BookingCancelled(
        UUID.randomUUID(),
        booking.getId(),
        booking.getRoomId(),
        booking.getGuestId(),
        reasonOf(reason),
        clock.instant());
  }

  /** The reason of the contract that mirrors the one of the domain; the names are the same (D3). */
  static CancellationReason reasonOf(CancelReason reason) {
    return CancellationReason.valueOf(reason.name());
  }
}
