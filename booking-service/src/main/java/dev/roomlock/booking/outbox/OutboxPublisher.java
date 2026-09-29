package dev.roomlock.booking.outbox;

import dev.roomlock.booking.domain.OutboxEvent;
import dev.roomlock.booking.infra.OutboxEventRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Publishes what the outbox holds, oldest first.
 *
 * <p>Send first, mark second: a crash between the two sends the event again, and a duplicate is a
 * consumer's business (every event carries an id for exactly that). The other order could lose an
 * event, which no consumer can repair.
 */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxEventRepository events;
  private final EventSender sender;
  private final Limit batch;
  private final Clock clock;
  private final OutboxMetrics metrics;

  public OutboxPublisher(
      OutboxEventRepository events,
      EventSender sender,
      OutboxProperties properties,
      Clock clock,
      OutboxMetrics metrics) {
    this.events = events;
    this.sender = sender;
    this.batch = Limit.of(properties.batchSize());
    this.clock = clock;
    this.metrics = metrics;
  }

  /**
   * Publishes one batch and returns how many events went out. Stops at the first event the broker
   * refuses: the events of one booking are published in the order they were written, and going on
   * after a refusal would break it.
   */
  public int publishBatch() {
    List<OutboxEvent> pending = events.findByPublishedAtIsNullOrderByCreatedAt(batch);
    int published = 0;
    for (OutboxEvent event : pending) {
      if (!publish(event)) {
        break;
      }
      published++;
    }
    metrics.published(published);
    return published;
  }

  private boolean publish(OutboxEvent event) {
    try {
      sender.send(event);
    } catch (EventSendFailedException refused) {
      events.recordAttempt(event.getId());
      metrics.failed();
      log.warn(
          "Outbox stopped at {} of booking {} after {} attempt(s)",
          event.getType(),
          event.getAggregateId(),
          event.getAttempts() + 1,
          refused);
      return false;
    }
    if (events.markPublished(event.getId(), clock.instant()) == 0) {
      log.info(
          "Event {} of booking {} was published twice", event.getType(), event.getAggregateId());
    }
    return true;
  }
}
