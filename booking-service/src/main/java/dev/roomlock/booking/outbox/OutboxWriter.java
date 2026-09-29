package dev.roomlock.booking.outbox;

import dev.roomlock.contract.v1.BookingEvent;
import org.springframework.stereotype.Component;

/**
 * Appends an event to the outbox inside the transaction that changed the booking, so the event and
 * the change are committed together or not at all.
 */
@Component
public class OutboxWriter {

  /**
   * Writes the event as a pending row.
   *
   * @throws IllegalStateException called without a transaction to join
   */
  public void append(BookingEvent event) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
