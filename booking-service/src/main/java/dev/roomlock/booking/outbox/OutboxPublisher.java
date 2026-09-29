package dev.roomlock.booking.outbox;

import dev.roomlock.booking.domain.OutboxEvent;
import dev.roomlock.booking.infra.OutboxEventRepository;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
  private final int stuckAfter;
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
    this.stuckAfter = properties.stuckAfter();
    this.clock = clock;
    this.metrics = metrics;
  }

  /**
   * Publishes one batch and returns how many events went out.
   *
   * <p>A refused event stops its own booking and nothing else. Order has to hold per booking - a
   * cancellation must not overtake the hold it cancels - but there is no order between bookings, so
   * one event the broker keeps refusing may not stop the whole outbox behind it.
   */
  public int publishBatch() {
    List<OutboxEvent> pending = events.findByPublishedAtIsNullOrderByCreatedAt(batch);
    Set<UUID> stopped = new HashSet<>();
    int published = 0;
    for (OutboxEvent event : pending) {
      if (stopped.contains(event.getAggregateId())) {
        continue;
      }
      if (publish(event)) {
        published++;
      } else {
        stopped.add(event.getAggregateId());
      }
    }
    metrics.published(published);
    return published;
  }

  /**
   * An event the broker has refused {@code stuckAfter} times is no longer an outage: it is waiting
   * for someone to look at it. It keeps being retried, because an outage can outlast any cap and
   * dropping a committed event is worse, but it is reported as an error and counted by the gauge
   * the dashboard watches.
   */
  private void reportRefusal(OutboxEvent event, EventSendFailedException refused) {
    int attempts = event.getAttempts() + 1;
    if (attempts >= stuckAfter) {
      log.error(
          "Outbox event {} ({} of booking {}) has been refused {} times and is holding up that"
              + " booking",
          event.getId(),
          event.getType(),
          event.getAggregateId(),
          attempts,
          refused);
      return;
    }
    log.warn(
        "Outbox stopped at {} of booking {} after {} attempt(s)",
        event.getType(),
        event.getAggregateId(),
        attempts,
        refused);
  }

  private boolean publish(OutboxEvent event) {
    try {
      sender.send(event);
    } catch (EventSendFailedException refused) {
      events.recordAttempt(event.getId());
      metrics.failed();
      reportRefusal(event, refused);
      return false;
    }
    if (events.markPublished(event.getId(), clock.instant()) == 0) {
      log.info(
          "Event {} of booking {} was published twice", event.getType(), event.getAggregateId());
    }
    return true;
  }
}
