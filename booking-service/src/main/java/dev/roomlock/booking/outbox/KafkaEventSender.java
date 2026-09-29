package dev.roomlock.booking.outbox;

import dev.roomlock.booking.domain.OutboxEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends an event to the topic of the contract that carries its type, keyed by its booking so the
 * events of one booking keep their order, with the type in the {@code event-type} header.
 */
@Component
public class KafkaEventSender implements EventSender {

  public KafkaEventSender(KafkaTemplate<String, String> kafka) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  public void send(OutboxEvent event) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  /** The topic a type of the contract belongs to. */
  static String topicOf(String type) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
