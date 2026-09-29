package dev.roomlock.notification.infra;

import static dev.roomlock.notification.support.TestData.NOW;
import static dev.roomlock.notification.support.TestData.aBookingHeld;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.contract.v1.BookingHeld;
import dev.roomlock.contract.v1.Topics;
import dev.roomlock.notification.support.BookingEventPublisher;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.lang.Nullable;

/**
 * Reading a record of the contract: the {@code event-type} header picks the record class, and
 * anything else is unreadable rather than silently dropped, so the error handler can park it.
 */
class BookingEventReaderTest {

  private final ObjectMapper json =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final BookingEventReader reader = new BookingEventReader(json);

  @Test
  void shouldReadTheEventOfTheHeaderWhenPayloadMatchesTheContract() throws Exception {
    // given
    BookingHeld held = aBookingHeld().build();
    ConsumerRecord<String, String> record = recordOf(json.writeValueAsString(held), held.type());

    // when
    BookingEvent read = reader.read(record);

    // then
    assertThat(read).isEqualTo(held);
    assertThat(read.occurredAt()).isEqualTo(NOW);
  }

  @Test
  void shouldFailWhenPayloadIsMalformed() {
    // given
    ConsumerRecord<String, String> record = recordOf("{\"eventId\": not json", "BookingHeld");

    // when, then
    assertThatThrownBy(() -> reader.read(record))
        .isInstanceOf(UnreadableEventException.class)
        .hasMessageContaining("BookingHeld");
  }

  @Test
  void shouldFailWhenEventTypeIsUnknown() {
    // given
    ConsumerRecord<String, String> record = recordOf("{}", "BookingRescheduled");

    // when, then
    assertThatThrownBy(() -> reader.read(record))
        .isInstanceOf(UnreadableEventException.class)
        .hasMessageContaining("BookingRescheduled");
  }

  @Test
  void shouldFailWhenEventTypeHeaderIsMissing() {
    // given
    ConsumerRecord<String, String> record = recordOf("{}", null);

    // when, then
    assertThatThrownBy(() -> reader.read(record))
        .isInstanceOf(UnreadableEventException.class)
        .hasMessageContaining(BookingEventPublisher.EVENT_TYPE_HEADER);
  }

  private static ConsumerRecord<String, String> recordOf(
      String payload, @Nullable String eventType) {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>(Topics.BOOKING_EVENTS, 0, 0L, "key", payload);
    if (eventType != null) {
      record
          .headers()
          .add(BookingEventPublisher.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
    }
    return record;
  }
}
