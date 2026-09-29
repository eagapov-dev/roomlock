package dev.roomlock.booking.outbox;

import dev.roomlock.booking.infra.OutboxEventRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Publishes what the outbox holds, oldest first. */
@Component
public class OutboxPublisher {

  public OutboxPublisher(
      OutboxEventRepository events,
      EventSender sender,
      OutboxProperties properties,
      Clock clock,
      OutboxMetrics metrics) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  /**
   * Publishes one batch and returns how many events went out. Stops at the first event the broker
   * refuses, so the events of one booking keep the order they were written in.
   */
  public int publishBatch() {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
