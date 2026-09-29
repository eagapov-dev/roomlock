package dev.roomlock.booking.payment;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.infra.BookingRepository;
import dev.roomlock.booking.outbox.BookingOutbox;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three status changes of the payment flow, each a conditional native UPDATE (rule DB-5) with
 * its event written beside it in the same transaction (rule K-1).
 *
 * <p>A component of its own rather than methods of {@link PaymentService}, because the boundary is
 * the point: the charge is a network round trip and must happen between these transactions, not
 * inside one of them - a call inside a transaction holds a connection of a pool of ten for as long
 * as the provider takes. Spring would also not proxy a {@code @Transactional} method a bean calls
 * on itself.
 */
@Component
class PaymentTransitions {

  private static final String BOOKING = "booking";

  private final BookingRepository bookings;
  private final BookingOutbox outbox;

  PaymentTransitions(BookingRepository bookings, BookingOutbox outbox) {
    this.bookings = bookings;
    this.outbox = outbox;
  }

  /**
   * Takes a HELD booking into PAYING and answers the booking it may now charge, or nothing when the
   * row was not HELD. No event: entering PAYING is not something a consumer of the contract hears
   * about, only the ending is.
   */
  @Transactional
  boolean startPaying(UUID bookingId, Instant now) {
    return bookings.startPaying(bookingId, now) > 0;
  }

  /**
   * Confirms the booking and records it, and answers the row as it stands afterwards. A lost UPDATE
   * writes no event and is not an error: another request or a sweep got the same answer from the
   * provider first, and the caller reads the status to see that.
   */
  @Transactional
  Booking confirm(UUID bookingId, Instant now) {
    if (bookings.confirmPaying(bookingId, now) > 0) {
      Booking confirmed = get(bookingId);
      outbox.recordConfirmed(confirmed);
      return confirmed;
    }
    return get(bookingId);
  }

  /**
   * Cancels the booking with PAYMENT_DECLINED and records it, the same way round as {@link
   * #confirm}.
   */
  @Transactional
  Booking decline(UUID bookingId, Instant now) {
    if (bookings.declinePaying(bookingId, now) > 0) {
      Booking cancelled = get(bookingId);
      outbox.recordCancelled(cancelled, CancelReason.PAYMENT_DECLINED);
      return cancelled;
    }
    return get(bookingId);
  }

  @Transactional(readOnly = true)
  Booking get(UUID bookingId) {
    return bookings
        .findById(bookingId)
        .orElseThrow(() -> new ResourceNotFoundException(BOOKING, bookingId));
  }
}
