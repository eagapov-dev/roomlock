package dev.roomlock.contract.v1;

import java.time.Instant;
import java.util.UUID;

/**
 * An event of one booking, published as JSON with {@link #type()} in the {@code event-type} header.
 *
 * <p>The type is the simple class name on purpose: it is the same string the {@code outbox_event.
 * type} CHECK of bookings allows, so a published event and its outbox row never disagree.
 */
public sealed interface BookingEvent
    permits BookingHeld, BookingConfirmed, BookingCancelled, RefundRequested {

  /** Kafka header the producer puts {@link #type()} in, so a consumer knows what it is reading. */
  String EVENT_TYPE_HEADER = "event-type";

  /** Unique per published event, so a consumer can drop a duplicate delivery. */
  UUID eventId();

  /** The booking the event is about; also the Kafka key, so its events keep their order. */
  UUID bookingId();

  Instant occurredAt();

  default String type() {
    return getClass().getSimpleName();
  }
}
