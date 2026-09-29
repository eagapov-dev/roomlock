package dev.roomlock.booking.outbox;

import dev.roomlock.booking.domain.OutboxEvent;
import dev.roomlock.contract.v1.BookingCancelled;
import dev.roomlock.contract.v1.BookingConfirmed;
import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.contract.v1.BookingHeld;
import dev.roomlock.contract.v1.RefundRequested;
import dev.roomlock.contract.v1.Topics;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends an event to the topic of the contract that carries its type, keyed by its booking so the
 * events of one booking keep their order, with the type in the {@code event-type} header.
 *
 * <p>The send is awaited: the publisher may only mark a row published once the broker has the
 * record, and a timeout has to leave the row for the next sweep.
 */
@Component
public class KafkaEventSender implements EventSender {

  /** Long enough for a broker election, short enough not to hold a sweep for a minute. */
  private static final long SEND_TIMEOUT_SECONDS = 30;

  /** The topic each type of the contract belongs to, by the name the outbox stores. */
  private static final Map<String, String> TOPICS =
      Map.of(
          BookingHeld.class.getSimpleName(), Topics.BOOKING_EVENTS,
          BookingConfirmed.class.getSimpleName(), Topics.BOOKING_EVENTS,
          BookingCancelled.class.getSimpleName(), Topics.BOOKING_EVENTS,
          RefundRequested.class.getSimpleName(), Topics.REFUND_REQUESTS);

  private final KafkaTemplate<String, String> kafka;

  public KafkaEventSender(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  @Override
  public void send(OutboxEvent event) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(
            topicOf(event.getType()), event.getAggregateId().toString(), event.getPayload());
    record
        .headers()
        .add(BookingEvent.EVENT_TYPE_HEADER, event.getType().getBytes(StandardCharsets.UTF_8));
    awaitAcknowledgement(event, record);
  }

  /** The topic a type of the contract belongs to. */
  static String topicOf(String type) {
    String topic = TOPICS.get(type);
    if (topic == null) {
      throw new IllegalArgumentException("No topic of the contract carries " + type);
    }
    return topic;
  }

  private void awaitAcknowledgement(OutboxEvent event, ProducerRecord<String, String> record) {
    try {
      kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new EventSendFailedException(failureOf(event), interrupted);
    } catch (ExecutionException | TimeoutException notAcknowledged) {
      throw new EventSendFailedException(failureOf(event), notAcknowledged);
    }
  }

  private static String failureOf(OutboxEvent event) {
    return "Broker did not acknowledge "
        + event.getType()
        + " of booking "
        + event.getAggregateId();
  }
}
