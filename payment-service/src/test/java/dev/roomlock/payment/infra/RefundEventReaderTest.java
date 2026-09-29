package dev.roomlock.payment.infra;

import static dev.roomlock.payment.support.TestData.NOW;
import static dev.roomlock.payment.support.TestData.aRefundRequested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.contract.v1.RefundRequested;
import dev.roomlock.contract.v1.Topics;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.lang.Nullable;

/**
 * Reading a record of the refund requests topic: the {@code event-type} header has to name the one
 * type the topic carries, and anything else is unreadable rather than silently dropped, so the
 * error handler can park it.
 */
class RefundEventReaderTest {

  private final ObjectMapper json =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RefundEventReader reader = new RefundEventReader(json);

  @Test
  void shouldReadTheEventWhenPayloadMatchesTheContract() throws Exception {
    // given
    RefundRequested requested = aRefundRequested().build();
    ConsumerRecord<String, String> record =
        recordOf(json.writeValueAsString(requested), requested.type());

    // when
    RefundRequested read = reader.read(record);

    // then
    assertThat(read).isEqualTo(requested);
    assertThat(read.occurredAt()).isEqualTo(NOW);
  }

  @Test
  void shouldFailWhenPayloadIsMalformed() {
    // given
    ConsumerRecord<String, String> record = recordOf("{\"eventId\": not json", "RefundRequested");

    // when, then
    assertThatThrownBy(() -> reader.read(record))
        .isInstanceOf(UnreadableEventException.class)
        .hasMessageContaining("RefundRequested");
  }

  @Test
  void shouldFailWhenEventIsOfAnotherTypeOfTheContract() {
    // given
    ConsumerRecord<String, String> record = recordOf("{}", "BookingCancelled");

    // when, then
    assertThatThrownBy(() -> reader.read(record))
        .isInstanceOf(UnreadableEventException.class)
        .hasMessageContaining("BookingCancelled");
  }

  @Test
  void shouldFailWhenEventTypeHeaderIsMissing() {
    // given
    ConsumerRecord<String, String> record = recordOf("{}", null);

    // when, then
    assertThatThrownBy(() -> reader.read(record))
        .isInstanceOf(UnreadableEventException.class)
        .hasMessageContaining(BookingEvent.EVENT_TYPE_HEADER);
  }

  private static ConsumerRecord<String, String> recordOf(
      String payload, @Nullable String eventType) {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>(Topics.REFUND_REQUESTS, 0, 0L, "key", payload);
    if (eventType != null) {
      record
          .headers()
          .add(BookingEvent.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
    }
    return record;
  }
}
