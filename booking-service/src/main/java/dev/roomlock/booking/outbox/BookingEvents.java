package dev.roomlock.booking.outbox;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.contract.v1.BookingCancelled;
import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.contract.v1.BookingHeld;
import dev.roomlock.contract.v1.CancellationReason;
import org.springframework.stereotype.Component;

/** Turns a booking of the domain into the event of the contract that describes what happened. */
@Component
public class BookingEvents {

  public BookingHeld held(Booking booking) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  public BookingCancelled cancelled(Booking booking, CancelReason reason) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  /** The reason of the contract that mirrors the one of the domain. */
  static CancellationReason reasonOf(CancelReason reason) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  /** Every event carries the booking it is about, so this is also its Kafka key. */
  static BookingEvent requireBookingId(BookingEvent event) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
