package dev.roomlock.notification.infra;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.contract.v1.BookingCancelled;
import dev.roomlock.contract.v1.BookingConfirmed;
import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.contract.v1.BookingHeld;
import dev.roomlock.contract.v1.RefundRequested;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns a Kafka record into the event of the contract its {@code event-type} header names.
 *
 * <p>The payload is read as plain JSON rather than through a polymorphic deserializer: the type is
 * in the header, not in the body, and doing it here keeps an unreadable record an ordinary
 * exception of the listener instead of a deserialization failure the container has to be taught
 * about.
 */
@Component
class BookingEventReader {

  /** The header {@link BookingEvent#type()} travels in, as the contract describes. */
  static final String EVENT_TYPE_HEADER = "event-type";

  /** Every type of the contract, including the one notifications ignore (rule K-2). */
  private static final Map<String, Class<? extends BookingEvent>> TYPES =
      Map.of(
          "BookingHeld", BookingHeld.class,
          "BookingConfirmed", BookingConfirmed.class,
          "BookingCancelled", BookingCancelled.class,
          "RefundRequested", RefundRequested.class);

  private final ObjectMapper json;

  BookingEventReader(ObjectMapper json) {
    this.json = json;
  }

  /** The event the record carries, or {@link UnreadableEventException} if it carries none. */
  BookingEvent read(ConsumerRecord<String, String> record) {
    String type = eventType(record);
    if (type == null) {
      throw new UnreadableEventException("No " + EVENT_TYPE_HEADER + " header on the record");
    }
    Class<? extends BookingEvent> eventClass = TYPES.get(type);
    if (eventClass == null) {
      throw new UnreadableEventException(
          "Unknown " + EVENT_TYPE_HEADER + " '" + type + "', not a type of the contract");
    }
    String payload = record.value();
    if (payload == null) {
      throw new UnreadableEventException("Record of " + type + " has no payload");
    }
    try {
      return json.readValue(payload, eventClass);
    } catch (JacksonException malformed) {
      throw new UnreadableEventException(
          "Payload of " + type + " does not match the contract", malformed);
    }
  }

  private static @Nullable String eventType(ConsumerRecord<String, String> record) {
    Header header = record.headers().lastHeader(EVENT_TYPE_HEADER);
    if (header == null || header.value() == null) {
      return null;
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
