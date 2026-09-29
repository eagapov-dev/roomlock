package dev.roomlock.booking.outbox;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.CancelReason;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the rest of the service uses the outbox for: recording that a booking was held or lost its
 * room. Every method has to run in the transaction that changed the booking (rule K-1), which
 * {@code MANDATORY} enforces.
 */
@Component
public class BookingOutbox {

  private final BookingEvents events;
  private final OutboxWriter outbox;

  BookingOutbox(BookingEvents events, OutboxWriter outbox) {
    this.events = events;
    this.outbox = outbox;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordHeld(Booking booking) {
    outbox.append(events.held(booking));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordCancelled(Booking booking, CancelReason reason) {
    outbox.append(events.cancelled(booking, reason));
  }
}
