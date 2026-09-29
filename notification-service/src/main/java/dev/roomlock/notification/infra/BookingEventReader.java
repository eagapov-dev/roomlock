package dev.roomlock.notification.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.contract.v1.BookingEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/** Turns a Kafka record into the event of the contract its {@code event-type} header names. */
@Component
class BookingEventReader {

  BookingEventReader(ObjectMapper json) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  /** The event the record carries, or {@link UnreadableEventException} if it carries none. */
  BookingEvent read(ConsumerRecord<String, String> record) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
