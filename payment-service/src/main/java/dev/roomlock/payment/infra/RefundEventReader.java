package dev.roomlock.payment.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.contract.v1.RefundRequested;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * Turns a Kafka record of the refund requests topic into the {@link RefundRequested} of the
 * contract.
 */
@Component
class RefundEventReader {

  RefundEventReader(ObjectMapper json) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  /** The event the record carries, or {@link UnreadableEventException} if it carries none. */
  RefundRequested read(ConsumerRecord<String, String> record) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
