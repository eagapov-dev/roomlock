package dev.roomlock.booking.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.domain.OutboxEvent;
import dev.roomlock.booking.infra.OutboxEventRepository;
import dev.roomlock.contract.v1.BookingEvent;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends an event to the outbox inside the transaction that changed the booking, so the event and
 * the change are committed together or not at all - the point of an outbox.
 */
@Component
public class OutboxWriter {

  private final OutboxEventRepository events;
  private final ObjectMapper json;
  private final Clock clock;

  OutboxWriter(OutboxEventRepository events, ObjectMapper json, Clock clock) {
    this.events = events;
    this.json = json;
    this.clock = clock;
  }

  /**
   * Writes the event as a pending row. {@code MANDATORY} on purpose: an event written in a
   * transaction of its own could survive a booking that rolled back, which is exactly what an
   * outbox exists to prevent.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(BookingEvent event) {
    events.save(
        OutboxEvent.pending(event.bookingId(), event.type(), payloadOf(event), clock.instant()));
  }

  private String payloadOf(BookingEvent event) {
    try {
      return json.writeValueAsString(event);
    } catch (JsonProcessingException notSerializable) {
      throw new IllegalStateException(
          "Event " + event.type() + " of booking " + event.bookingId() + " is not serializable",
          notSerializable);
    }
  }
}
