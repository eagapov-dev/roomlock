package dev.roomlock.payment.infra;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.contract.v1.RefundRequested;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns a Kafka record of the refund requests topic into the {@link RefundRequested} of the
 * contract.
 *
 * <p>The payload is read as plain JSON rather than through a polymorphic deserializer: the type is
 * in the {@code event-type} header, not in the body, and doing it here keeps an unreadable record
 * an ordinary exception of the listener instead of a deserialization failure the container has to
 * be taught about.
 *
 * <p>The topic carries one type of the contract, so anything else on it - even another booking
 * event - is unreadable here and is parked rather than ignored: a refund that ends up as the wrong
 * type is worth looking at.
 */
@Component
class RefundEventReader {

  /** The only type of the contract this topic carries (rule K-2). */
  private static final String TYPE = RefundRequested.class.getSimpleName();

  private final ObjectMapper json;

  RefundEventReader(ObjectMapper json) {
    this.json = json;
  }

  /** The event the record carries, or {@link UnreadableEventException} if it carries none. */
  RefundRequested read(ConsumerRecord<String, String> record) {
    String type = eventType(record);
    if (type == null) {
      throw new UnreadableEventException(
          "No " + BookingEvent.EVENT_TYPE_HEADER + " header on the record");
    }
    if (!TYPE.equals(type)) {
      throw new UnreadableEventException(
          "Unexpected "
              + BookingEvent.EVENT_TYPE_HEADER
              + " '"
              + type
              + "', the refund requests topic carries "
              + TYPE);
    }
    String payload = record.value();
    if (payload == null) {
      throw new UnreadableEventException("Record of " + TYPE + " has no payload");
    }
    try {
      return json.readValue(payload, RefundRequested.class);
    } catch (JacksonException malformed) {
      throw new UnreadableEventException(
          "Payload of " + TYPE + " does not match the contract", malformed);
    }
  }

  private static @Nullable String eventType(ConsumerRecord<String, String> record) {
    Header header = record.headers().lastHeader(BookingEvent.EVENT_TYPE_HEADER);
    if (header == null || header.value() == null) {
      return null;
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
